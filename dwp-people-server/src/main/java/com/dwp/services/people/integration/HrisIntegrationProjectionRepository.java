package com.dwp.services.people.integration;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

class HrisIntegrationProjectionRepository extends HrisIntegrationIngestionRepository {
    HrisIntegrationProjectionRepository(NamedParameterJdbcTemplate jdbc) {
        super(jdbc);
    }

    public HrisIntegrationRepository.PersonUpsert upsertPerson(
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
                (rs, rowNum) -> new HrisIntegrationRepository.PersonUpsert(
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

}
