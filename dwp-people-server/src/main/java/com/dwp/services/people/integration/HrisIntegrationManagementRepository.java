package com.dwp.services.people.integration;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

class HrisIntegrationManagementRepository extends HrisIntegrationProjectionRepository {
    HrisIntegrationManagementRepository(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    public Optional<HrisDtos.SyncRun> findRun(Long tenantId, UUID syncRunId) {
        String sql = baseRunSelect() + " WHERE run.tenant_id = :tenantId AND run.sync_run_id = :syncRunId";
        return jdbc.query(sql, new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("syncRunId", syncRunId), this::mapRun)
                .stream().findFirst();
    }

    public Optional<HrisIntegrationRepository.MappingRuntime> findActiveMapping(Long tenantId, long sourceSystemId) {
        return jdbc.query("""
                SELECT mapping_profile_id, source_system_id, profile_key, adapter_type,
                       source_schema_version, target_schema_version, mapping_definition, version
                  FROM int_mapping_profiles
                 WHERE tenant_id = :tenantId
                   AND source_system_id = :sourceSystemId
                   AND lifecycle_state = 'ACTIVE'
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("sourceSystemId", sourceSystemId), (rs, rowNum) -> new HrisIntegrationRepository.MappingRuntime(
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
                       version = version + 1,
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
                       version = version + 1,
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
                       run.redacted_failure_message, run.version,
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
                rs.getLong("version"),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("completed_at")));
    }

}
