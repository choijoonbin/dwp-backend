package com.dwp.services.people.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class HrisIntegrationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public HrisIntegrationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long upsertSource(
            Long tenantId,
            Long actorId,
            String sourceKey,
            String sourceType,
            String name) {
        String sql = """
                INSERT INTO int_source_systems (
                    tenant_id, source_key, system_type, name,
                    authoritative_domains, lifecycle_state, created_by, updated_by)
                VALUES (
                    :tenantId, :sourceKey, :sourceType, :name,
                    CAST(:domains AS jsonb), 'ACTIVE', :actorId, :actorId)
                ON CONFLICT (tenant_id, source_key) DO UPDATE SET
                    system_type = EXCLUDED.system_type,
                    name = EXCLUDED.name,
                    authoritative_domains = EXCLUDED.authoritative_domains,
                    lifecycle_state = 'ACTIVE',
                    version = int_source_systems.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                RETURNING source_system_id
                """;
        return requiredLong(sql, params(tenantId, actorId)
                .addValue("sourceKey", sourceKey)
                .addValue("sourceType", sourceType)
                .addValue("name", name)
                .addValue("domains", "[\"PERSON\",\"WORKER\",\"ASSIGNMENT\"]"));
    }

    public void upsertReferenceConnector(Long tenantId, Long actorId, long sourceSystemId) {
        String sql = """
                INSERT INTO int_connector_instances (
                    tenant_id, source_system_id, connector_key, connector_type,
                    auth_mode, capabilities, lifecycle_state, health_state,
                    created_by, updated_by)
                VALUES (
                    :tenantId, :sourceSystemId, 'workday-reference-file', 'FILE_IMPORT',
                    'NONE', CAST('["FULL","DELTA","EFFECTIVE_DATED"]' AS jsonb),
                    'ACTIVE', 'HEALTHY', :actorId, :actorId)
                ON CONFLICT (tenant_id, connector_key) DO UPDATE SET
                    source_system_id = EXCLUDED.source_system_id,
                    lifecycle_state = 'ACTIVE',
                    health_state = 'HEALTHY',
                    last_health_checked_at = CURRENT_TIMESTAMP,
                    version = int_connector_instances.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                """;
        jdbc.update(sql, params(tenantId, actorId).addValue("sourceSystemId", sourceSystemId));
    }

    public HrisDtos.ConnectorInstance createConnector(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            HrisDtos.CreateConnectorRequest request) {
        UUID connectorId = jdbc.queryForObject("""
                INSERT INTO int_connector_instances (
                    tenant_id, source_system_id, connector_key, connector_type,
                    endpoint_uri, auth_mode, credential_reference, schedule_expression,
                    capabilities, lifecycle_state, health_state, created_by, updated_by)
                VALUES (
                    :tenantId, :sourceSystemId, :connectorKey, :connectorType,
                    :endpointUri, :authMode, :credentialReference, :scheduleExpression,
                    CAST(:capabilities AS jsonb), 'DRAFT', 'UNKNOWN', :actorId, :actorId)
                RETURNING connector_instance_id
                """, params(tenantId, actorId)
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("connectorKey", request.connectorKey().trim().toLowerCase())
                .addValue("connectorType", request.connectorType())
                .addValue("endpointUri", trimToNull(request.endpointUri()))
                .addValue("authMode", request.authMode())
                .addValue("credentialReference", trimToNull(request.credentialReference()))
                .addValue("scheduleExpression", trimToNull(request.scheduleExpression()))
                .addValue("capabilities", "[\"FULL\",\"DELTA\",\"EFFECTIVE_DATED\"]"),
                UUID.class);
        if (connectorId == null) throw new IllegalStateException("Connector identifier was not returned.");
        return findConnector(tenantId, connectorId)
                .orElseThrow(() -> new IllegalStateException("Created connector is missing."));
    }

    public Optional<HrisDtos.ConnectorInstance> findConnector(Long tenantId, UUID connectorId) {
        return jdbc.query(connectorSelect() + """
                 WHERE connector.tenant_id = :tenantId
                   AND connector.connector_instance_id = :connectorId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("connectorId", connectorId), this::mapConnector)
                .stream().findFirst();
    }

    public boolean updateConnector(
            Long tenantId,
            Long actorId,
            UUID connectorId,
            HrisDtos.UpdateConnectorRequest request) {
        return jdbc.update("""
                UPDATE int_connector_instances
                   SET endpoint_uri = :endpointUri,
                       credential_reference = :credentialReference,
                       schedule_expression = :scheduleExpression,
                       lifecycle_state = :lifecycleState,
                       health_state = CASE
                           WHEN endpoint_uri IS DISTINCT FROM :endpointUri
                             OR credential_reference IS DISTINCT FROM :credentialReference
                           THEN 'UNKNOWN' ELSE health_state END,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorId
                 WHERE tenant_id = :tenantId
                   AND connector_instance_id = :connectorId
                   AND version = :version
                """, params(tenantId, actorId)
                .addValue("connectorId", connectorId)
                .addValue("endpointUri", trimToNull(request.endpointUri()))
                .addValue("credentialReference", trimToNull(request.credentialReference()))
                .addValue("scheduleExpression", trimToNull(request.scheduleExpression()))
                .addValue("lifecycleState", request.lifecycleState())
                .addValue("version", request.version())) == 1;
    }

    public void recordConfigurationCheck(
            Long tenantId,
            Long actorId,
            UUID connectorId,
            String healthState) {
        jdbc.update("""
                UPDATE int_connector_instances
                   SET health_state = :healthState,
                       last_health_checked_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorId
                 WHERE tenant_id = :tenantId
                   AND connector_instance_id = :connectorId
                """, params(tenantId, actorId)
                .addValue("connectorId", connectorId)
                .addValue("healthState", healthState));
    }

    public void auditConnector(
            Long tenantId,
            Long actorId,
            UUID connectorId,
            String action,
            String correlationId,
            String snapshot) {
        jdbc.update("""
                INSERT INTO sys_people_audit_events (
                    tenant_id, actor_type, actor_id, action, target_type, target_id,
                    outcome, correlation_id, after_snapshot)
                VALUES (
                    :tenantId, 'USER', :actorId, :action, 'HRIS_CONNECTOR', :connectorId,
                    'SUCCESS', :correlationId, CAST(:snapshot AS jsonb))
                """, params(tenantId, actorId)
                .addValue("connectorId", connectorId.toString())
                .addValue("action", action)
                .addValue("correlationId", correlationId)
                .addValue("snapshot", snapshot));
    }

    public void upsertMappingProfile(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            String sourceSchemaVersion,
            JsonNode mappingDefinition) {
        String sql = """
                INSERT INTO int_mapping_profiles (
                    tenant_id, source_system_id, profile_key, adapter_type,
                    source_schema_version, target_schema_version, mapping_definition,
                    lifecycle_state, mapping_sha256, activated_at, activated_by,
                    created_by, updated_by)
                VALUES (
                    :tenantId, :sourceSystemId, 'workday-reference-v1', 'WORKDAY_REFERENCE',
                    :sourceSchemaVersion, 'dwp.workforce-projection.v1',
                    CAST(:mappingDefinition AS jsonb), 'ACTIVE', :mappingSha256,
                    CURRENT_TIMESTAMP, :actorId, :actorId, :actorId)
                ON CONFLICT (tenant_id, source_system_id, profile_key) DO UPDATE SET
                    source_schema_version = EXCLUDED.source_schema_version,
                    target_schema_version = EXCLUDED.target_schema_version,
                    mapping_definition = EXCLUDED.mapping_definition,
                    mapping_sha256 = EXCLUDED.mapping_sha256,
                    lifecycle_state = 'ACTIVE',
                    activated_at = CURRENT_TIMESTAMP,
                    activated_by = EXCLUDED.activated_by,
                    version = int_mapping_profiles.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                """;
        jdbc.update(sql, params(tenantId, actorId)
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("sourceSchemaVersion", sourceSchemaVersion)
                .addValue("mappingDefinition", mappingDefinition.toString())
                .addValue("mappingSha256", sha256(mappingDefinition.toString())));
    }

    public Receipt acquireReceipt(
            Long tenantId,
            long sourceSystemId,
            String idempotencyKey,
            String payloadSha256) {
        String insert = """
                INSERT INTO int_ingestion_receipts (
                    tenant_id, source_system_id, idempotency_key, payload_sha256)
                VALUES (:tenantId, :sourceSystemId, :idempotencyKey, :payloadSha256)
                ON CONFLICT (tenant_id, source_system_id, idempotency_key) DO NOTHING
                RETURNING ingestion_receipt_id, sync_run_id, lifecycle_state, payload_sha256
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource("tenantId", tenantId)
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("payloadSha256", payloadSha256);
        List<Receipt> inserted = jdbc.query(insert, parameters, (rs, rowNum) -> new Receipt(
                rs.getLong("ingestion_receipt_id"),
                (UUID) rs.getObject("sync_run_id"),
                rs.getString("lifecycle_state"),
                rs.getString("payload_sha256"),
                true));
        if (!inserted.isEmpty()) return inserted.get(0);
        String select = """
                SELECT ingestion_receipt_id, sync_run_id, lifecycle_state, payload_sha256
                  FROM int_ingestion_receipts
                 WHERE tenant_id = :tenantId
                   AND source_system_id = :sourceSystemId
                   AND idempotency_key = :idempotencyKey
                """;
        return jdbc.query(select, parameters, (rs, rowNum) -> new Receipt(
                        rs.getLong("ingestion_receipt_id"),
                        (UUID) rs.getObject("sync_run_id"),
                        rs.getString("lifecycle_state"),
                        rs.getString("payload_sha256"),
                        false))
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Ingestion receipt disappeared."));
    }

    public void startRun(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            long receiptId,
            UUID syncRunId,
            String correlationId,
            String syncMode,
            String watermark,
            UUID connectorInstanceId,
            UUID mappingProfileId,
            UUID retryOfSyncRunId,
            int pageCount) {
        String runSql = """
                INSERT INTO int_sync_runs (
                    sync_run_id, tenant_id, source_system_id, correlation_id,
                    sync_mode, lifecycle_state, requested_watermark, started_at,
                    connector_instance_id, mapping_profile_id, retry_of_sync_run_id, page_count,
                    created_by, updated_by)
                VALUES (
                    :syncRunId, :tenantId, :sourceSystemId, :correlationId,
                    :syncMode, 'RUNNING', :watermark, CURRENT_TIMESTAMP,
                    :connectorInstanceId, :mappingProfileId, :retryOfSyncRunId, :pageCount,
                    :actorId, :actorId)
                """;
        MapSqlParameterSource parameters = params(tenantId, actorId)
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("syncRunId", syncRunId)
                .addValue("correlationId", correlationId)
                .addValue("syncMode", syncMode)
                .addValue("watermark", watermark)
                .addValue("connectorInstanceId", connectorInstanceId)
                .addValue("mappingProfileId", mappingProfileId)
                .addValue("retryOfSyncRunId", retryOfSyncRunId)
                .addValue("pageCount", pageCount);
        jdbc.update(runSql, parameters);
        jdbc.update("""
                UPDATE int_ingestion_receipts
                   SET sync_run_id = :syncRunId
                 WHERE tenant_id = :tenantId AND ingestion_receipt_id = :receiptId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("receiptId", receiptId)
                .addValue("syncRunId", syncRunId));
    }

    public PersonUpsert upsertPerson(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            HrisModels.WorkerRecord worker) {
        String sql = """
                INSERT INTO ppl_persons (
                    tenant_id, person_key, display_name, preferred_locale, time_zone,
                    lifecycle_state, source_system_id, external_id, created_by, updated_by)
                VALUES (
                    :tenantId, :personKey, :displayName, :preferredLocale, :timeZone,
                    :lifecycleState, :sourceSystemId, :externalId, :actorId, :actorId)
                ON CONFLICT (tenant_id, person_key) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    preferred_locale = EXCLUDED.preferred_locale,
                    time_zone = EXCLUDED.time_zone,
                    lifecycle_state = EXCLUDED.lifecycle_state,
                    source_system_id = EXCLUDED.source_system_id,
                    external_id = EXCLUDED.external_id,
                    version = ppl_persons.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                RETURNING person_id, public_id, (xmax = 0) AS inserted
                """;
        return jdbc.query(sql, params(tenantId, actorId)
                        .addValue("personKey", worker.workerNumber())
                        .addValue("displayName", worker.displayName())
                        .addValue("preferredLocale", worker.preferredLocale())
                        .addValue("timeZone", worker.timeZone())
                        .addValue("lifecycleState", "TERMINATED".equals(worker.workerStatus()) ? "INACTIVE" : "ACTIVE")
                        .addValue("sourceSystemId", sourceSystemId)
                        .addValue("externalId", worker.externalId()),
                (rs, rowNum) -> new PersonUpsert(
                        rs.getLong("person_id"),
                        rs.getObject("public_id", UUID.class),
                        rs.getBoolean("inserted")))
                .get(0);
    }

    public void upsertName(
            Long tenantId,
            Long actorId,
            long personId,
            HrisModels.WorkerRecord worker) {
        jdbc.update("""
                INSERT INTO ppl_person_names (
                    tenant_id, person_id, name_type, locale, given_name, family_name,
                    formatted_name, effective_start_date, created_by, updated_by)
                VALUES (
                    :tenantId, :personId, 'PREFERRED', :locale, :givenName, :familyName,
                    :formattedName, :effectiveStartDate, :actorId, :actorId)
                ON CONFLICT (
                    tenant_id, person_id, name_type, locale,
                    effective_start_date, effective_sequence
                ) DO UPDATE SET
                    given_name = EXCLUDED.given_name,
                    family_name = EXCLUDED.family_name,
                    formatted_name = EXCLUDED.formatted_name,
                    version = ppl_person_names.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                """, params(tenantId, actorId)
                .addValue("personId", personId)
                .addValue("locale", worker.preferredLocale() == null ? "und" : worker.preferredLocale())
                .addValue("givenName", worker.givenName())
                .addValue("familyName", worker.familyName())
                .addValue("formattedName", worker.displayName())
                .addValue("effectiveStartDate", Date.valueOf(worker.originalHireDate())));
    }

    public long upsertEmployer(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            HrisModels.Employer employer) {
        String sql = """
                INSERT INTO ppl_legal_employers (
                    tenant_id, employer_key, legal_name, country_code,
                    source_system_id, external_id, created_by, updated_by)
                VALUES (
                    :tenantId, :key, :name, :countryCode,
                    :sourceSystemId, :key, :actorId, :actorId)
                ON CONFLICT (tenant_id, employer_key) DO UPDATE SET
                    legal_name = EXCLUDED.legal_name,
                    country_code = EXCLUDED.country_code,
                    source_system_id = EXCLUDED.source_system_id,
                    version = ppl_legal_employers.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                RETURNING legal_employer_id
                """;
        return requiredLong(sql, params(tenantId, actorId)
                .addValue("key", employer.key())
                .addValue("name", employer.legalName())
                .addValue("countryCode", employer.countryCode())
                .addValue("sourceSystemId", sourceSystemId));
    }

    public long upsertWorker(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            long personId,
            HrisModels.WorkerRecord worker) {
        String sql = """
                INSERT INTO ppl_workers (
                    tenant_id, person_id, worker_number, worker_type, worker_status,
                    original_hire_date, source_system_id, external_id, created_by, updated_by)
                VALUES (
                    :tenantId, :personId, :workerNumber, :workerType, :workerStatus,
                    :hireDate, :sourceSystemId, :externalId, :actorId, :actorId)
                ON CONFLICT (tenant_id, worker_number) DO UPDATE SET
                    person_id = EXCLUDED.person_id,
                    worker_type = EXCLUDED.worker_type,
                    worker_status = EXCLUDED.worker_status,
                    original_hire_date = EXCLUDED.original_hire_date,
                    source_system_id = EXCLUDED.source_system_id,
                    external_id = EXCLUDED.external_id,
                    version = ppl_workers.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                RETURNING worker_id
                """;
        return requiredLong(sql, params(tenantId, actorId)
                .addValue("personId", personId)
                .addValue("workerNumber", worker.workerNumber())
                .addValue("workerType", worker.workerType())
                .addValue("workerStatus", worker.workerStatus())
                .addValue("hireDate", Date.valueOf(worker.originalHireDate()))
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("externalId", worker.externalId()));
    }

    public long upsertRelationship(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            long workerId,
            long employerId,
            HrisModels.WorkerRecord worker) {
        String key = worker.workerNumber() + "-PRIMARY";
        String sql = """
                INSERT INTO ppl_work_relationships (
                    tenant_id, relationship_key, worker_id, legal_employer_id,
                    relationship_type, primary_relationship, start_date,
                    source_system_id, external_id, created_by, updated_by)
                VALUES (
                    :tenantId, :key, :workerId, :employerId,
                    :type, TRUE, :startDate,
                    :sourceSystemId, :externalId, :actorId, :actorId)
                ON CONFLICT (tenant_id, relationship_key) DO UPDATE SET
                    legal_employer_id = EXCLUDED.legal_employer_id,
                    relationship_type = EXCLUDED.relationship_type,
                    start_date = EXCLUDED.start_date,
                    source_system_id = EXCLUDED.source_system_id,
                    external_id = EXCLUDED.external_id,
                    version = ppl_work_relationships.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                RETURNING work_relationship_id
                """;
        return requiredLong(sql, params(tenantId, actorId)
                .addValue("key", key)
                .addValue("workerId", workerId)
                .addValue("employerId", employerId)
                .addValue("type", worker.workerType())
                .addValue("startDate", Date.valueOf(worker.originalHireDate()))
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("externalId", worker.externalId() + ":relationship"));
    }

    public long upsertOrganization(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            HrisModels.Organization organization,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate) {
        String organizationType = canonicalOrganizationType(organization.type());
        ensureOrganizationType(
                tenantId, actorId, organizationType,
                organization.type() == null ? organizationType : organization.type().trim());
        Long parentId = null;
        if (organization.parentKey() != null) {
            ensureOrganizationType(tenantId, actorId, "CUSTOM", "Custom unit");
            parentId = upsertOrganizationRecord(
                    tenantId, actorId, sourceSystemId,
                    organization.parentKey(), organization.parentKey(), "CUSTOM", null);
        }
        long organizationId = upsertOrganizationRecord(
                tenantId, actorId, sourceSystemId,
                organization.key(), organization.name(), organizationType, parentId);
        if (parentId != null) {
            upsertOrganizationRelationship(
                    tenantId, actorId, sourceSystemId, organizationId, parentId,
                    effectiveStartDate, effectiveEndDate, organization.key());
        }
        return organizationId;
    }

    private void ensureOrganizationType(
            Long tenantId,
            Long actorId,
            String typeKey,
            String displayName) {
        jdbc.update("""
                INSERT INTO ppl_organization_type_catalog (
                    tenant_id, type_key, display_name, description,
                    hierarchy_rank, created_by, updated_by)
                VALUES (
                    :tenantId, :typeKey, :displayName,
                    'Registered by an HRIS organization import.', 500, :actorId, :actorId)
                ON CONFLICT (tenant_id, type_key) DO UPDATE SET
                    lifecycle_state = 'ACTIVE',
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by,
                    version = ppl_organization_type_catalog.version + 1
                """, params(tenantId, actorId)
                .addValue("typeKey", typeKey)
                .addValue("displayName", displayName.isBlank() ? humanizeType(typeKey) : displayName));
    }

    private void upsertOrganizationRelationship(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            long organizationId,
            long parentId,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            String organizationKey) {
        String sql = """
                INSERT INTO ppl_organization_relationships (
                    tenant_id, child_organization_id, parent_organization_id,
                    relationship_type, primary_relationship, effective_start_date,
                    effective_end_date, source_system_id, external_id, created_by, updated_by)
                VALUES (
                    :tenantId, :organizationId, :parentId,
                    'SUPERVISORY', TRUE, :startDate,
                    :endDate, :sourceSystemId, :externalId, :actorId, :actorId)
                ON CONFLICT (
                    tenant_id, child_organization_id, parent_organization_id,
                    relationship_type, effective_start_date)
                DO UPDATE SET
                    primary_relationship = TRUE,
                    effective_end_date = EXCLUDED.effective_end_date,
                    source_system_id = EXCLUDED.source_system_id,
                    external_id = EXCLUDED.external_id,
                    version = ppl_organization_relationships.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                """;
        jdbc.update(sql, params(tenantId, actorId)
                .addValue("organizationId", organizationId)
                .addValue("parentId", parentId)
                .addValue("startDate", Date.valueOf(effectiveStartDate))
                .addValue("endDate", date(effectiveEndDate))
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("externalId", organizationKey + ":supervisory"));
    }

    private long upsertOrganizationRecord(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            String key,
            String name,
            String type,
            Long parentId) {
        String sql = """
                INSERT INTO ppl_organizations (
                    tenant_id, organization_key, organization_type, name,
                    parent_organization_id, source_system_id, external_id,
                    created_by, updated_by)
                VALUES (
                    :tenantId, :key, :type, :name,
                    :parentId, :sourceSystemId, :key, :actorId, :actorId)
                ON CONFLICT (tenant_id, organization_key) DO UPDATE SET
                    organization_type = EXCLUDED.organization_type,
                    name = CASE
                        WHEN ppl_organizations.name = ppl_organizations.organization_key
                        THEN EXCLUDED.name ELSE ppl_organizations.name END,
                    parent_organization_id = COALESCE(EXCLUDED.parent_organization_id, ppl_organizations.parent_organization_id),
                    source_system_id = EXCLUDED.source_system_id,
                    version = ppl_organizations.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                RETURNING organization_id
                """;
        return requiredLong(sql, params(tenantId, actorId)
                .addValue("key", key)
                .addValue("type", type)
                .addValue("name", name)
                .addValue("parentId", parentId)
                .addValue("sourceSystemId", sourceSystemId));
    }

    public long upsertJobProfile(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            HrisModels.JobProfile job) {
        String sql = """
                INSERT INTO ppl_job_profiles (
                    tenant_id, job_key, name, job_family_key, management_level,
                    source_system_id, external_id, created_by, updated_by)
                VALUES (
                    :tenantId, :key, :name, :family, :level,
                    :sourceSystemId, :key, :actorId, :actorId)
                ON CONFLICT (tenant_id, job_key) DO UPDATE SET
                    name = EXCLUDED.name,
                    job_family_key = EXCLUDED.job_family_key,
                    management_level = EXCLUDED.management_level,
                    source_system_id = EXCLUDED.source_system_id,
                    version = ppl_job_profiles.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                RETURNING job_profile_id
                """;
        return requiredLong(sql, params(tenantId, actorId)
                .addValue("key", job.key())
                .addValue("name", job.name())
                .addValue("family", job.familyKey())
                .addValue("level", job.managementLevel())
                .addValue("sourceSystemId", sourceSystemId));
    }

    public Long upsertJobGrade(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            HrisModels.JobGrade grade) {
        if (grade == null) return null;
        String sql = """
                INSERT INTO ppl_job_grades (
                    tenant_id, grade_key, name, level_order, career_track,
                    source_system_id, external_id, created_by, updated_by)
                VALUES (
                    :tenantId, :key, :name, :levelOrder, :careerTrack,
                    :sourceSystemId, :key, :actorId, :actorId)
                ON CONFLICT (tenant_id, grade_key) DO UPDATE SET
                    name = EXCLUDED.name,
                    level_order = EXCLUDED.level_order,
                    career_track = EXCLUDED.career_track,
                    lifecycle_state = 'ACTIVE',
                    source_system_id = EXCLUDED.source_system_id,
                    version = ppl_job_grades.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                RETURNING job_grade_id
                """;
        return requiredLong(sql, params(tenantId, actorId)
                .addValue("key", grade.key())
                .addValue("name", grade.name())
                .addValue("levelOrder", grade.levelOrder())
                .addValue("careerTrack", grade.careerTrack())
                .addValue("sourceSystemId", sourceSystemId));
    }

    public long upsertLocation(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            HrisModels.Location location) {
        String sql = """
                INSERT INTO ppl_locations (
                    tenant_id, location_key, name, country_code, time_zone,
                    source_system_id, external_id, created_by, updated_by)
                VALUES (
                    :tenantId, :key, :name, :countryCode, :timeZone,
                    :sourceSystemId, :key, :actorId, :actorId)
                ON CONFLICT (tenant_id, location_key) DO UPDATE SET
                    name = EXCLUDED.name,
                    country_code = EXCLUDED.country_code,
                    time_zone = EXCLUDED.time_zone,
                    source_system_id = EXCLUDED.source_system_id,
                    version = ppl_locations.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                RETURNING location_id
                """;
        return requiredLong(sql, params(tenantId, actorId)
                .addValue("key", location.key())
                .addValue("name", location.name())
                .addValue("countryCode", location.countryCode())
                .addValue("timeZone", location.timeZone())
                .addValue("sourceSystemId", sourceSystemId));
    }

    public long upsertPosition(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            HrisModels.Position position,
            long organizationId,
            long jobProfileId,
            long locationId) {
        String sql = """
                INSERT INTO ppl_positions (
                    tenant_id, position_key, title, organization_id, job_profile_id,
                    location_id, position_status, source_system_id, external_id,
                    created_by, updated_by)
                VALUES (
                    :tenantId, :key, :title, :organizationId, :jobProfileId,
                    :locationId, 'FILLED', :sourceSystemId, :key,
                    :actorId, :actorId)
                ON CONFLICT (tenant_id, position_key) DO UPDATE SET
                    title = EXCLUDED.title,
                    organization_id = EXCLUDED.organization_id,
                    job_profile_id = EXCLUDED.job_profile_id,
                    location_id = EXCLUDED.location_id,
                    position_status = 'FILLED',
                    source_system_id = EXCLUDED.source_system_id,
                    version = ppl_positions.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                RETURNING position_id
                """;
        return requiredLong(sql, params(tenantId, actorId)
                .addValue("key", position.key())
                .addValue("title", position.title())
                .addValue("organizationId", organizationId)
                .addValue("jobProfileId", jobProfileId)
                .addValue("locationId", locationId)
                .addValue("sourceSystemId", sourceSystemId));
    }

    public void upsertAssignment(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            long relationshipId,
            long organizationId,
            long jobProfileId,
            Long jobGradeId,
            long locationId,
            long positionId,
            HrisModels.Assignment assignment) {
        String sql = """
                INSERT INTO ppl_assignments (
                    tenant_id, assignment_key, work_relationship_id,
                    effective_start_date, effective_end_date, assignment_status,
                    primary_assignment, position_id, job_profile_id, job_grade_id, organization_id,
                    location_id, manager_assignment_key, business_title, cost_center_key,
                    change_reason_code, source_system_id, external_id, source_version,
                    created_by, updated_by)
                VALUES (
                    :tenantId, :assignmentKey, :relationshipId,
                    :startDate, :endDate, :status,
                    :primary, :positionId, :jobProfileId, :jobGradeId, :organizationId,
                    :locationId, :managerKey, :title, :costCenter,
                    :changeReason, :sourceSystemId, :externalId, :sourceVersion,
                    :actorId, :actorId)
                ON CONFLICT (
                    tenant_id, assignment_key, effective_start_date, effective_sequence
                ) DO UPDATE SET
                    effective_end_date = EXCLUDED.effective_end_date,
                    assignment_status = EXCLUDED.assignment_status,
                    primary_assignment = EXCLUDED.primary_assignment,
                    position_id = EXCLUDED.position_id,
                    job_profile_id = EXCLUDED.job_profile_id,
                    job_grade_id = EXCLUDED.job_grade_id,
                    organization_id = EXCLUDED.organization_id,
                    location_id = EXCLUDED.location_id,
                    manager_assignment_key = EXCLUDED.manager_assignment_key,
                    business_title = EXCLUDED.business_title,
                    cost_center_key = EXCLUDED.cost_center_key,
                    change_reason_code = EXCLUDED.change_reason_code,
                    source_system_id = EXCLUDED.source_system_id,
                    external_id = EXCLUDED.external_id,
                    source_version = EXCLUDED.source_version,
                    version = ppl_assignments.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                """;
        jdbc.update(sql, params(tenantId, actorId)
                .addValue("assignmentKey", assignment.assignmentKey())
                .addValue("relationshipId", relationshipId)
                .addValue("startDate", Date.valueOf(assignment.effectiveStartDate()))
                .addValue("endDate", date(assignment.effectiveEndDate()))
                .addValue("status", assignment.assignmentStatus())
                .addValue("primary", assignment.primary())
                .addValue("positionId", positionId)
                .addValue("jobProfileId", jobProfileId)
                .addValue("jobGradeId", jobGradeId)
                .addValue("organizationId", organizationId)
                .addValue("locationId", locationId)
                .addValue("managerKey", assignment.managerAssignmentKey())
                .addValue("title", assignment.businessTitle())
                .addValue("costCenter", assignment.costCenterKey())
                .addValue("changeReason", assignment.changeReasonCode())
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("externalId", assignment.externalId())
                .addValue("sourceVersion", assignment.sourceVersion()));
    }

    public void replaceWorkEmail(
            Long tenantId,
            Long actorId,
            long personId,
            String workEmail,
            LocalDate validFrom) {
        jdbc.update("""
                DELETE FROM ppl_contacts
                 WHERE tenant_id = :tenantId
                   AND person_id = :personId
                   AND contact_type = 'EMAIL'
                   AND usage_type = 'WORK'
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("personId", personId));
        if (workEmail == null) return;
        jdbc.update("""
                INSERT INTO ppl_contacts (
                    tenant_id, person_id, contact_type, usage_type, display_value,
                    primary_contact, visibility, valid_from, created_by, updated_by)
                VALUES (
                    :tenantId, :personId, 'EMAIL', 'WORK', :workEmail,
                    TRUE, 'INTERNAL', :validFrom, :actorId, :actorId)
                """, params(tenantId, actorId)
                .addValue("personId", personId)
                .addValue("workEmail", workEmail)
                .addValue("validFrom", Date.valueOf(validFrom)));
    }

    public void upsertExternalMapping(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            String entityType,
            String internalKey,
            String externalId,
            String sourceVersion) {
        jdbc.update("""
                INSERT INTO int_external_mappings (
                    tenant_id, source_system_id, entity_type, internal_key,
                    external_id, external_version, last_seen_at, created_by, updated_by)
                VALUES (
                    :tenantId, :sourceSystemId, :entityType, :internalKey,
                    :externalId, :sourceVersion, CURRENT_TIMESTAMP, :actorId, :actorId)
                ON CONFLICT (tenant_id, source_system_id, entity_type, external_id)
                DO UPDATE SET
                    internal_key = EXCLUDED.internal_key,
                    external_version = EXCLUDED.external_version,
                    last_seen_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                """, params(tenantId, actorId)
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("entityType", entityType)
                .addValue("internalKey", internalKey)
                .addValue("externalId", externalId)
                .addValue("sourceVersion", sourceVersion));
    }

    public void emitProjectionChanged(
            Long tenantId,
            UUID personPublicId,
            UUID syncRunId,
            String correlationId,
            HrisModels.WorkerRecord worker,
            String jobTitle) {
        jdbc.update("""
                INSERT INTO sys_people_outbox_events (
                    tenant_id, aggregate_type, aggregate_id, event_type,
                    payload, correlation_id)
                VALUES (
                    :tenantId, 'PERSON', :personPublicId,
                    'people.worker-projection.changed',
                    jsonb_build_object(
                        'contractVersion', 1,
                        'personPublicId', :personPublicId,
                        'externalId', :externalId,
                        'displayName', :displayName,
                        'givenName', :givenName,
                        'familyName', :familyName,
                        'workEmail', :workEmail,
                        'jobTitle', :jobTitle,
                        'preferredLocale', :preferredLocale,
                        'workerStatus', :workerStatus,
                        'sourceVersion', :sourceVersion,
                        'syncRunId', :syncRunId
                    ),
                    :correlationId)
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("personPublicId", personPublicId.toString())
                .addValue("externalId", worker.externalId())
                .addValue("displayName", worker.displayName())
                .addValue("givenName", worker.givenName())
                .addValue("familyName", worker.familyName())
                .addValue("workEmail", worker.workEmail())
                .addValue("jobTitle", jobTitle)
                .addValue("preferredLocale", worker.preferredLocale())
                .addValue("workerStatus", worker.workerStatus())
                .addValue("sourceVersion", worker.sourceVersion())
                .addValue("syncRunId", syncRunId.toString())
                .addValue("correlationId", correlationId));
    }

    public void completeRun(
            Long tenantId,
            long sourceSystemId,
            long receiptId,
            UUID syncRunId,
            UUID connectorInstanceId,
            String watermark,
            long read,
            long created,
            long updated,
            long rejected) {
        jdbc.update("""
                UPDATE int_sync_runs
                   SET lifecycle_state = CASE WHEN :rejected = 0 THEN 'SUCCEEDED' ELSE 'PARTIAL' END,
                       committed_watermark = :watermark,
                       read_count = :read,
                       created_count = :created,
                       updated_count = :updated,
                       rejected_count = :rejected,
                       completed_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND sync_run_id = :syncRunId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("syncRunId", syncRunId)
                .addValue("watermark", watermark)
                .addValue("read", read)
                .addValue("created", created)
                .addValue("updated", updated)
                .addValue("rejected", rejected));
        jdbc.update("""
                UPDATE int_ingestion_receipts
                   SET lifecycle_state = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND ingestion_receipt_id = :receiptId
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("receiptId", receiptId));
        String connectorPredicate = connectorInstanceId == null
                ? "source_system_id = :sourceSystemId"
                : "connector_instance_id = :connectorInstanceId";
        MapSqlParameterSource connectorParameters = new MapSqlParameterSource("tenantId", tenantId)
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("connectorInstanceId", connectorInstanceId);
        jdbc.update("""
                UPDATE int_connector_instances
                   SET last_successful_sync_at = CURRENT_TIMESTAMP,
                       last_attempted_sync_at = CURRENT_TIMESTAMP,
                       last_health_checked_at = CURRENT_TIMESTAMP,
                       health_state = 'HEALTHY',
                       last_error_code = NULL,
                       consecutive_failure_count = 0,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND %s
                """.formatted(connectorPredicate), connectorParameters);
        if (connectorInstanceId != null) {
            jdbc.update("""
                    INSERT INTO int_connector_cursors (
                        tenant_id, connector_instance_id, cursor_type,
                        committed_cursor, committed_at, sync_run_id)
                    VALUES (
                        :tenantId, :connectorInstanceId, 'WATERMARK',
                        :watermark, CURRENT_TIMESTAMP, :syncRunId)
                    ON CONFLICT (tenant_id, connector_instance_id, cursor_type) DO UPDATE SET
                        committed_cursor = EXCLUDED.committed_cursor,
                        committed_at = EXCLUDED.committed_at,
                        sync_run_id = EXCLUDED.sync_run_id,
                        version = int_connector_cursors.version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    """, new MapSqlParameterSource("tenantId", tenantId)
                    .addValue("connectorInstanceId", connectorInstanceId)
                    .addValue("watermark", watermark)
                    .addValue("syncRunId", syncRunId));
        }
    }

    public void auditImport(
            Long tenantId,
            Long actorId,
            long sourceSystemId,
            UUID syncRunId,
            String correlationId,
            long read,
            long created,
            long updated) {
        jdbc.update("""
                INSERT INTO sys_people_audit_events (
                    tenant_id, actor_type, actor_id, action, target_type, target_id,
                    outcome, correlation_id, source_system_id, after_snapshot)
                VALUES (
                    :tenantId, 'USER', :actorId, 'people.hris-import.completed',
                    'SYNC_RUN', :syncRunId, 'SUCCESS', :correlationId,
                    :sourceSystemId,
                    jsonb_build_object('readCount', :read, 'createdCount', :created, 'updatedCount', :updated))
                """, params(tenantId, actorId)
                .addValue("sourceSystemId", sourceSystemId)
                .addValue("syncRunId", syncRunId.toString())
                .addValue("correlationId", correlationId)
                .addValue("read", read)
                .addValue("created", created)
                .addValue("updated", updated));
    }

    public Optional<HrisDtos.SyncRun> findRun(Long tenantId, UUID syncRunId) {
        String sql = baseRunSelect() + " WHERE run.tenant_id = :tenantId AND run.sync_run_id = :syncRunId";
        return jdbc.query(sql, new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("syncRunId", syncRunId), this::mapRun)
                .stream().findFirst();
    }

    public Optional<MappingRuntime> findActiveMapping(Long tenantId, long sourceSystemId) {
        return jdbc.query("""
                SELECT mapping_profile_id, source_system_id, profile_key, adapter_type,
                       source_schema_version, target_schema_version, mapping_definition, version
                  FROM int_mapping_profiles
                 WHERE tenant_id = :tenantId
                   AND source_system_id = :sourceSystemId
                   AND lifecycle_state = 'ACTIVE'
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("sourceSystemId", sourceSystemId), (rs, rowNum) -> new MappingRuntime(
                        rs.getObject("mapping_profile_id", UUID.class),
                        rs.getLong("source_system_id"), rs.getString("profile_key"),
                        rs.getString("adapter_type"), rs.getString("source_schema_version"),
                        rs.getString("target_schema_version"),
                        rs.getString("mapping_definition"), rs.getLong("version")))
                .stream().findFirst();
    }

    public HrisDtos.MappingProfile createMapping(
            Long tenantId,
            Long actorId,
            HrisDtos.CreateMappingProfileRequest request) {
        UUID id = UUID.randomUUID();
        String definition = request.mappingDefinition().toString();
        jdbc.update("""
                INSERT INTO int_mapping_profiles (
                    mapping_profile_id, tenant_id, source_system_id, profile_key,
                    adapter_type, source_schema_version, target_schema_version,
                    mapping_definition, lifecycle_state, mapping_sha256, created_by, updated_by)
                SELECT :mappingId, :tenantId, source_system_id, :profileKey,
                       :adapterType, :sourceSchemaVersion, :targetSchemaVersion,
                       CAST(:definition AS jsonb), 'DRAFT', :mappingSha256, :actorId, :actorId
                  FROM int_source_systems
                 WHERE tenant_id = :tenantId AND source_system_id = :sourceSystemId
                """, params(tenantId, actorId)
                .addValue("mappingId", id)
                .addValue("sourceSystemId", request.sourceSystemId())
                .addValue("profileKey", request.profileKey().trim())
                .addValue("adapterType", request.adapterType())
                .addValue("sourceSchemaVersion", request.sourceSchemaVersion().trim())
                .addValue("targetSchemaVersion", request.targetSchemaVersion().trim())
                .addValue("definition", definition)
                .addValue("mappingSha256", sha256(definition)));
        return findMapping(tenantId, id).orElseThrow(
                () -> new IllegalStateException("HRIS mapping source does not exist."));
    }

    public Optional<HrisDtos.MappingProfile> findMapping(Long tenantId, UUID mappingId) {
        return jdbc.query("""
                SELECT mapping_profile_id, source_system_id, profile_key, adapter_type, source_schema_version,
                       target_schema_version, lifecycle_state, mapping_sha256,
                       activated_at, version
                  FROM int_mapping_profiles
                 WHERE tenant_id = :tenantId AND mapping_profile_id = :mappingId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("mappingId", mappingId), (rs, rowNum) -> new HrisDtos.MappingProfile(
                        rs.getObject("mapping_profile_id", UUID.class),
                        rs.getLong("source_system_id"),
                        rs.getString("profile_key"), rs.getString("adapter_type"),
                        rs.getString("source_schema_version"), rs.getString("target_schema_version"),
                        rs.getString("lifecycle_state"), rs.getString("mapping_sha256"),
                        instant(rs.getTimestamp("activated_at")), rs.getLong("version")))
                .stream().findFirst();
    }

    public boolean activateMapping(
            Long tenantId,
            Long actorId,
            UUID mappingId,
            long version) {
        Long sourceSystemId = jdbc.query("""
                SELECT source_system_id
                  FROM int_mapping_profiles
                 WHERE tenant_id = :tenantId
                   AND mapping_profile_id = :mappingId
                   AND lifecycle_state = 'DRAFT'
                   AND version = :version
                 FOR UPDATE
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("mappingId", mappingId)
                .addValue("version", version), (rs, rowNum) -> rs.getLong(1))
                .stream().findFirst().orElse(null);
        if (sourceSystemId == null) return false;
        jdbc.update("""
                UPDATE int_mapping_profiles
                   SET lifecycle_state = 'RETIRED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = :actorId
                 WHERE tenant_id = :tenantId
                   AND source_system_id = :sourceSystemId
                   AND lifecycle_state = 'ACTIVE'
                """, params(tenantId, actorId).addValue("sourceSystemId", sourceSystemId));
        return jdbc.update("""
                UPDATE int_mapping_profiles
                   SET lifecycle_state = 'ACTIVE', activated_at = CURRENT_TIMESTAMP,
                       activated_by = :actorId, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = :actorId
                 WHERE tenant_id = :tenantId AND mapping_profile_id = :mappingId
                   AND lifecycle_state = 'DRAFT' AND version = :version
                """, params(tenantId, actorId)
                .addValue("mappingId", mappingId).addValue("version", version)) == 1;
    }

    public String currentCursor(Long tenantId, UUID connectorId) {
        return jdbc.query("""
                SELECT committed_cursor
                  FROM int_connector_cursors
                 WHERE tenant_id = :tenantId
                   AND connector_instance_id = :connectorId
                   AND cursor_type = 'WATERMARK'
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("connectorId", connectorId), (rs, rowNum) -> rs.getString(1))
                .stream().findFirst().orElse(null);
    }

    public void markConnectorAttempt(Long tenantId, Long actorId, UUID connectorId) {
        jdbc.update("""
                UPDATE int_connector_instances
                   SET last_attempted_sync_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP, updated_by = :actorId
                 WHERE tenant_id = :tenantId AND connector_instance_id = :connectorId
                """, params(tenantId, actorId).addValue("connectorId", connectorId));
    }

    public UUID recordFailedRun(
            Long tenantId,
            Long actorId,
            HrisDtos.ConnectorInstance connector,
            UUID mappingProfileId,
            UUID retryOfSyncRunId,
            String correlationId,
            String syncMode,
            String requestedCursor,
            String failureCode,
            String redactedMessage) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO int_sync_runs (
                    sync_run_id, tenant_id, source_system_id, connector_instance_id,
                    mapping_profile_id, retry_of_sync_run_id, correlation_id, sync_mode,
                    lifecycle_state, requested_watermark, failure_code,
                    redacted_failure_message, started_at, completed_at, created_by, updated_by)
                VALUES (
                    :runId, :tenantId, :sourceSystemId, :connectorId,
                    :mappingProfileId, :retryOfSyncRunId, :correlationId, :syncMode,
                    'FAILED', :requestedCursor, :failureCode,
                    :redactedMessage, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, :actorId, :actorId)
                """, params(tenantId, actorId)
                .addValue("runId", runId)
                .addValue("sourceSystemId", connector.sourceSystemId())
                .addValue("connectorId", connector.connectorInstanceId())
                .addValue("mappingProfileId", mappingProfileId)
                .addValue("retryOfSyncRunId", retryOfSyncRunId)
                .addValue("correlationId", correlationId)
                .addValue("syncMode", syncMode)
                .addValue("requestedCursor", requestedCursor)
                .addValue("failureCode", failureCode)
                .addValue("redactedMessage", redactedMessage));
        jdbc.update("""
                INSERT INTO int_sync_errors (
                    tenant_id, sync_run_id, entity_type, error_code,
                    redacted_message, retryable)
                VALUES (:tenantId, :runId, 'CONNECTOR', :failureCode, :redactedMessage, :retryable)
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("runId", runId)
                .addValue("failureCode", failureCode)
                .addValue("redactedMessage", redactedMessage)
                .addValue("retryable", !"CONFIGURATION_BLOCKED".equals(failureCode)));
        jdbc.update("""
                UPDATE int_connector_instances
                   SET last_attempted_sync_at = CURRENT_TIMESTAMP,
                       last_health_checked_at = CURRENT_TIMESTAMP,
                       health_state = CASE WHEN :failureCode = 'CONFIGURATION_BLOCKED'
                                           THEN 'DEGRADED' ELSE 'FAILED' END,
                       last_error_code = :failureCode,
                       consecutive_failure_count = consecutive_failure_count + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = :actorId
                 WHERE tenant_id = :tenantId AND connector_instance_id = :connectorId
                """, params(tenantId, actorId)
                .addValue("connectorId", connector.connectorInstanceId())
                .addValue("failureCode", failureCode));
        return runId;
    }

    public HrisDtos.ReconciliationRun reconcile(
            Long tenantId,
            Long actorId,
            UUID connectorId,
            UUID syncRunId) {
        UUID reconciliationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO int_reconciliation_runs (
                    reconciliation_run_id, tenant_id, connector_instance_id,
                    sync_run_id, lifecycle_state, created_by)
                VALUES (:id, :tenantId, :connectorId, :syncRunId, 'RUNNING', :actorId)
                """, params(tenantId, actorId)
                .addValue("id", reconciliationId)
                .addValue("connectorId", connectorId)
                .addValue("syncRunId", syncRunId));

        jdbc.update("""
                INSERT INTO int_reconciliation_issues (
                    tenant_id, reconciliation_run_id, connector_instance_id,
                    issue_code, severity, entity_type, internal_key, redacted_summary)
                SELECT :tenantId, :id, :connectorId,
                       'DUPLICATE_WORK_EMAIL', 'CRITICAL', 'PERSON',
                       encode(sha256(convert_to(lower(contact.display_value), 'UTF8')), 'hex'),
                       'Multiple workforce records share the same normalized work email.'
                  FROM ppl_contacts contact
                  JOIN ppl_persons person
                    ON person.tenant_id = contact.tenant_id AND person.person_id = contact.person_id
                  JOIN int_connector_instances connector
                    ON connector.tenant_id = person.tenant_id
                   AND connector.source_system_id = person.source_system_id
                 WHERE contact.tenant_id = :tenantId
                   AND connector.connector_instance_id = :connectorId
                   AND contact.contact_type = 'EMAIL' AND contact.usage_type = 'WORK'
                   AND contact.display_value IS NOT NULL
                 GROUP BY lower(contact.display_value)
                HAVING count(*) > 1
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("id", reconciliationId).addValue("connectorId", connectorId));

        jdbc.update("""
                INSERT INTO int_reconciliation_issues (
                    tenant_id, reconciliation_run_id, connector_instance_id,
                    issue_code, severity, entity_type, internal_key, redacted_summary)
                SELECT :tenantId, :id, :connectorId,
                       'MANAGER_ASSIGNMENT_NOT_FOUND', 'WARNING', 'ASSIGNMENT',
                       assignment.assignment_key,
                       'The manager assignment reference does not resolve in the workforce projection.'
                  FROM ppl_assignments assignment
                  JOIN int_connector_instances connector
                    ON connector.tenant_id = assignment.tenant_id
                   AND connector.source_system_id = assignment.source_system_id
                  LEFT JOIN ppl_assignments manager
                    ON manager.tenant_id = assignment.tenant_id
                   AND manager.assignment_key = assignment.manager_assignment_key
                 WHERE assignment.tenant_id = :tenantId
                   AND connector.connector_instance_id = :connectorId
                   AND assignment.manager_assignment_key IS NOT NULL
                   AND manager.assignment_id IS NULL
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("id", reconciliationId).addValue("connectorId", connectorId));

        jdbc.update("""
                INSERT INTO int_reconciliation_issues (
                    tenant_id, reconciliation_run_id, connector_instance_id,
                    issue_code, severity, entity_type, internal_key, external_id,
                    redacted_summary)
                SELECT :tenantId, :id, :connectorId,
                       'NOT_SEEN_IN_FULL_SYNC', 'WARNING', mapping.entity_type,
                       mapping.internal_key, mapping.external_id,
                       'The mapped record was not observed during the latest full synchronization.'
                  FROM int_external_mappings mapping
                  JOIN int_sync_runs run
                    ON run.tenant_id = mapping.tenant_id AND run.sync_run_id = :syncRunId
                  JOIN int_connector_instances connector
                    ON connector.tenant_id = mapping.tenant_id
                   AND connector.source_system_id = mapping.source_system_id
                 WHERE mapping.tenant_id = :tenantId
                   AND connector.connector_instance_id = :connectorId
                   AND run.sync_mode = 'FULL'
                   AND mapping.last_seen_at < run.started_at
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("id", reconciliationId).addValue("connectorId", connectorId)
                .addValue("syncRunId", syncRunId));

        jdbc.update("""
                UPDATE int_reconciliation_runs run
                   SET lifecycle_state = 'SUCCEEDED',
                       checked_count = (
                           SELECT count(*) FROM int_external_mappings mapping
                           JOIN int_connector_instances connector
                             ON connector.tenant_id = mapping.tenant_id
                            AND connector.source_system_id = mapping.source_system_id
                          WHERE mapping.tenant_id = run.tenant_id
                            AND connector.connector_instance_id = run.connector_instance_id),
                       issue_count = (
                           SELECT count(*) FROM int_reconciliation_issues issue
                            WHERE issue.tenant_id = run.tenant_id
                              AND issue.reconciliation_run_id = run.reconciliation_run_id),
                       critical_count = (
                           SELECT count(*) FROM int_reconciliation_issues issue
                            WHERE issue.tenant_id = run.tenant_id
                              AND issue.reconciliation_run_id = run.reconciliation_run_id
                              AND issue.severity = 'CRITICAL'),
                       completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND reconciliation_run_id = :id
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("id", reconciliationId));
        return findReconciliationRun(tenantId, reconciliationId).orElseThrow();
    }

    public Optional<HrisDtos.ReconciliationRun> findReconciliationRun(Long tenantId, UUID id) {
        return jdbc.query(reconciliationRunSelect() + """
                 WHERE tenant_id = :tenantId AND reconciliation_run_id = :id
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("id", id),
                this::mapReconciliationRun).stream().findFirst();
    }

    public List<HrisDtos.ReconciliationRun> listReconciliationRuns(Long tenantId, int limit) {
        return jdbc.query(reconciliationRunSelect() + """
                 WHERE tenant_id = :tenantId ORDER BY started_at DESC LIMIT :limit
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("limit", limit),
                this::mapReconciliationRun);
    }

    public List<HrisDtos.ReconciliationIssue> listReconciliationIssues(
            Long tenantId,
            String state,
            int limit) {
        String stateFilter = state == null || state.isBlank() ? "" : " AND lifecycle_state = :state";
        return jdbc.query("""
                SELECT reconciliation_issue_id, reconciliation_run_id, connector_instance_id,
                       issue_code, severity, entity_type, internal_key, external_id,
                       redacted_summary, lifecycle_state, first_detected_at, resolved_at
                  FROM int_reconciliation_issues
                 WHERE tenant_id = :tenantId
                """ + stateFilter + " ORDER BY first_detected_at DESC LIMIT :limit",
                new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("state", state).addValue("limit", limit),
                (rs, rowNum) -> new HrisDtos.ReconciliationIssue(
                        rs.getObject("reconciliation_issue_id", UUID.class),
                        rs.getObject("reconciliation_run_id", UUID.class),
                        rs.getObject("connector_instance_id", UUID.class),
                        rs.getString("issue_code"), rs.getString("severity"),
                        rs.getString("entity_type"), rs.getString("internal_key"),
                        rs.getString("external_id"), rs.getString("redacted_summary"),
                        rs.getString("lifecycle_state"),
                        instant(rs.getTimestamp("first_detected_at")),
                        instant(rs.getTimestamp("resolved_at"))));
    }

    public boolean resolveReconciliationIssue(
            Long tenantId,
            Long actorId,
            UUID issueId,
            HrisDtos.ResolveIssueRequest request) {
        return jdbc.update("""
                UPDATE int_reconciliation_issues
                   SET lifecycle_state = :state, resolution_note = :note,
                       resolved_at = CURRENT_TIMESTAMP, resolved_by = :actorId
                 WHERE tenant_id = :tenantId AND reconciliation_issue_id = :issueId
                   AND lifecycle_state = 'OPEN'
                """, params(tenantId, actorId)
                .addValue("issueId", issueId)
                .addValue("state", request.lifecycleState())
                .addValue("note", request.resolutionNote().trim())) == 1;
    }

    private String reconciliationRunSelect() {
        return """
                SELECT reconciliation_run_id, connector_instance_id, sync_run_id,
                       lifecycle_state, checked_count, issue_count, critical_count,
                       started_at, completed_at
                  FROM int_reconciliation_runs
                """;
    }

    private HrisDtos.ReconciliationRun mapReconciliationRun(
            java.sql.ResultSet rs,
            int rowNum) throws java.sql.SQLException {
        return new HrisDtos.ReconciliationRun(
                rs.getObject("reconciliation_run_id", UUID.class),
                rs.getObject("connector_instance_id", UUID.class),
                rs.getObject("sync_run_id", UUID.class), rs.getString("lifecycle_state"),
                rs.getLong("checked_count"), rs.getLong("issue_count"),
                rs.getLong("critical_count"), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("completed_at")));
    }

    public List<HrisDtos.SourceSystem> listSources(Long tenantId) {
        return jdbc.query("""
                SELECT source_system_id, source_key, system_type, name, lifecycle_state, version
                  FROM int_source_systems
                 WHERE tenant_id = :tenantId
                 ORDER BY source_key
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, rowNum) ->
                new HrisDtos.SourceSystem(
                        rs.getLong("source_system_id"), rs.getString("source_key"),
                        rs.getString("system_type"), rs.getString("name"),
                        rs.getString("lifecycle_state"), rs.getLong("version")));
    }

    public List<HrisDtos.ConnectorInstance> listConnectors(Long tenantId) {
        return jdbc.query(connectorSelect() + """
                 WHERE connector.tenant_id = :tenantId
                 ORDER BY connector.connector_key
                """, new MapSqlParameterSource("tenantId", tenantId), this::mapConnector);
    }

    private String connectorSelect() {
        return """
                SELECT connector.connector_instance_id, connector.source_system_id,
                       source.source_key, connector.connector_key, connector.connector_type,
                       connector.endpoint_uri, connector.auth_mode, connector.credential_reference,
                       connector.schedule_expression, connector.lifecycle_state,
                       connector.health_state, connector.last_health_checked_at,
                       connector.last_successful_sync_at, connector.last_attempted_sync_at,
                       connector.last_error_code, connector.consecutive_failure_count,
                       connector.version
                  FROM int_connector_instances connector
                  JOIN int_source_systems source
                    ON source.tenant_id = connector.tenant_id
                   AND source.source_system_id = connector.source_system_id
                """;
    }

    private HrisDtos.ConnectorInstance mapConnector(
            java.sql.ResultSet rs,
            int rowNum) throws java.sql.SQLException {
        return new HrisDtos.ConnectorInstance(
                rs.getObject("connector_instance_id", UUID.class),
                rs.getLong("source_system_id"), rs.getString("source_key"),
                rs.getString("connector_key"), rs.getString("connector_type"),
                rs.getString("endpoint_uri"), rs.getString("auth_mode"),
                rs.getString("credential_reference"), rs.getString("schedule_expression"),
                rs.getString("lifecycle_state"), rs.getString("health_state"),
                instant(rs.getTimestamp("last_health_checked_at")),
                instant(rs.getTimestamp("last_successful_sync_at")),
                instant(rs.getTimestamp("last_attempted_sync_at")),
                rs.getString("last_error_code"), rs.getInt("consecutive_failure_count"),
                rs.getLong("version"));
    }

    public List<HrisDtos.MappingProfile> listMappings(Long tenantId) {
        return jdbc.query("""
                SELECT mapping_profile_id, source_system_id, profile_key, adapter_type, source_schema_version,
                       target_schema_version, lifecycle_state, mapping_sha256,
                       activated_at, version
                  FROM int_mapping_profiles
                 WHERE tenant_id = :tenantId
                 ORDER BY profile_key
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, rowNum) ->
                new HrisDtos.MappingProfile(
                        rs.getObject("mapping_profile_id", UUID.class),
                        rs.getLong("source_system_id"),
                        rs.getString("profile_key"), rs.getString("adapter_type"),
                        rs.getString("source_schema_version"), rs.getString("target_schema_version"),
                        rs.getString("lifecycle_state"), rs.getString("mapping_sha256"),
                        instant(rs.getTimestamp("activated_at")), rs.getLong("version")));
    }

    public List<HrisDtos.SyncRun> listRuns(Long tenantId, int limit) {
        return jdbc.query(
                baseRunSelect() + " WHERE run.tenant_id = :tenantId ORDER BY run.created_at DESC LIMIT :limit",
                new MapSqlParameterSource("tenantId", tenantId).addValue("limit", limit),
                this::mapRun);
    }

    private String baseRunSelect() {
        return """
                SELECT run.sync_run_id, source.source_key, run.sync_mode, run.lifecycle_state,
                       run.requested_watermark, run.committed_watermark,
                       run.read_count, run.created_count, run.updated_count, run.rejected_count,
                       run.connector_instance_id, run.mapping_profile_id, run.retry_of_sync_run_id,
                       run.page_count, run.unchanged_count, run.failure_code,
                       run.redacted_failure_message,
                       run.started_at, run.completed_at
                  FROM int_sync_runs run
                  JOIN int_source_systems source
                    ON source.tenant_id = run.tenant_id
                   AND source.source_system_id = run.source_system_id
                """;
    }

    private HrisDtos.SyncRun mapRun(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new HrisDtos.SyncRun(
                rs.getObject("sync_run_id", UUID.class), rs.getString("source_key"),
                rs.getString("sync_mode"), rs.getString("lifecycle_state"),
                rs.getString("requested_watermark"), rs.getString("committed_watermark"),
                rs.getLong("read_count"), rs.getLong("created_count"),
                rs.getLong("updated_count"), rs.getLong("rejected_count"),
                rs.getObject("connector_instance_id", UUID.class),
                rs.getObject("mapping_profile_id", UUID.class),
                rs.getObject("retry_of_sync_run_id", UUID.class),
                rs.getInt("page_count"), rs.getLong("unchanged_count"),
                rs.getString("failure_code"), rs.getString("redacted_failure_message"),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("completed_at")));
    }

    private MapSqlParameterSource params(Long tenantId, Long actorId) {
        return new MapSqlParameterSource("tenantId", tenantId).addValue("actorId", actorId);
    }

    private long requiredLong(String sql, MapSqlParameterSource parameters) {
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        if (value == null) throw new IllegalStateException("Database did not return an identifier.");
        return value;
    }

    private Date date(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String canonicalOrganizationType(String value) {
        if (value == null || value.isBlank()) return "CUSTOM";
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9._-]+", "_");
        if (normalized.isEmpty()) return "CUSTOM";
        if (!Character.isLetter(normalized.charAt(0))) normalized = "TYPE_" + normalized;
        return normalized.length() > 100 ? normalized.substring(0, 100) : normalized;
    }

    private String humanizeType(String typeKey) {
        String normalized = typeKey.toLowerCase(Locale.ROOT).replaceAll("[._-]+", " ");
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    public record Receipt(
            long receiptId,
            UUID syncRunId,
            String state,
            String payloadSha256,
            boolean acquired) {
    }

    public record PersonUpsert(long personId, UUID publicId, boolean inserted) {
    }

    public record MappingRuntime(
            UUID mappingProfileId,
            long sourceSystemId,
            String profileKey,
            String adapterType,
            String sourceSchemaVersion,
            String targetSchemaVersion,
            String mappingDefinition,
            long version) {
    }
}
