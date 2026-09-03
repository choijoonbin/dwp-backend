package com.dwp.services.people.directory;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class PeopleDirectoryRepository {

    private static final String DIRECTORY_SELECT = """
            SELECT p.person_id,
                   p.public_id,
                   p.display_name,
                   p.preferred_locale,
                   p.time_zone,
                   p.lifecycle_state,
                   w.worker_number,
                   w.worker_type,
                   w.worker_status,
                   w.original_hire_date,
                   a.assignment_key,
                   a.business_title,
                   a.manager_assignment_key,
                   a.effective_start_date AS assignment_effective_from,
                   org.public_id AS organization_public_id,
                   org.organization_key,
                   org.name AS organization_name,
                   job.name AS job_profile_name,
                   job.management_level,
                   grade.grade_key,
                   grade.name AS grade_name,
                   loc.location_key,
                   loc.name AS location_name,
                   employer.legal_name AS legal_employer_name,
                   manager_person.public_id AS manager_person_public_id,
                   manager_person.display_name AS manager_display_name,
                   COALESCE(report_count.direct_report_count, 0) AS direct_report_count,
                   contact.display_value AS work_email,
                   media.object_key AS profile_image_key
              FROM ppl_persons p
              LEFT JOIN LATERAL (
                    SELECT candidate.*
                      FROM ppl_workers candidate
                     WHERE candidate.tenant_id = p.tenant_id
                       AND candidate.person_id = p.person_id
                     ORDER BY CASE candidate.worker_status
                                  WHEN 'ACTIVE' THEN 0 WHEN 'LEAVE' THEN 1
                                  WHEN 'PENDING' THEN 2 ELSE 3 END,
                              candidate.worker_id
                     LIMIT 1
              ) w ON TRUE
              LEFT JOIN LATERAL (
                    SELECT candidate.*, relationship.legal_employer_id
                      FROM ppl_assignments candidate
                      JOIN ppl_work_relationships relationship
                        ON relationship.tenant_id = candidate.tenant_id
                       AND relationship.work_relationship_id = candidate.work_relationship_id
                     WHERE candidate.tenant_id = p.tenant_id
                       AND relationship.worker_id = w.worker_id
                       AND candidate.effective_start_date <= :asOf
                       AND (candidate.effective_end_date IS NULL
                            OR candidate.effective_end_date >= :asOf)
                     ORDER BY candidate.primary_assignment DESC,
                              candidate.effective_start_date DESC,
                              candidate.effective_sequence DESC,
                              candidate.assignment_id DESC
                     LIMIT 1
              ) a ON TRUE
              LEFT JOIN ppl_organizations org
                ON org.tenant_id = p.tenant_id AND org.organization_id = a.organization_id
              LEFT JOIN ppl_job_profiles job
                ON job.tenant_id = p.tenant_id AND job.job_profile_id = a.job_profile_id
              LEFT JOIN ppl_job_grades grade
                ON grade.tenant_id = p.tenant_id AND grade.job_grade_id = a.job_grade_id
              LEFT JOIN ppl_locations loc
                ON loc.tenant_id = p.tenant_id AND loc.location_id = a.location_id
              LEFT JOIN ppl_legal_employers employer
                ON employer.tenant_id = p.tenant_id
               AND employer.legal_employer_id = a.legal_employer_id
              LEFT JOIN LATERAL (
                    SELECT manager.*
                      FROM ppl_assignments manager
                     WHERE manager.tenant_id = p.tenant_id
                       AND manager.assignment_key = a.manager_assignment_key
                       AND manager.effective_start_date <= :asOf
                       AND (manager.effective_end_date IS NULL
                            OR manager.effective_end_date >= :asOf)
                     ORDER BY manager.effective_start_date DESC,
                              manager.effective_sequence DESC,
                              manager.assignment_id DESC
                     LIMIT 1
              ) manager_assignment ON TRUE
              LEFT JOIN ppl_work_relationships manager_relationship
                ON manager_relationship.tenant_id = manager_assignment.tenant_id
               AND manager_relationship.work_relationship_id = manager_assignment.work_relationship_id
              LEFT JOIN ppl_workers manager_worker
                ON manager_worker.tenant_id = manager_relationship.tenant_id
               AND manager_worker.worker_id = manager_relationship.worker_id
              LEFT JOIN ppl_persons manager_person
                ON manager_person.tenant_id = manager_worker.tenant_id
               AND manager_person.person_id = manager_worker.person_id
              LEFT JOIN LATERAL (
                    SELECT COUNT(*)::INTEGER AS direct_report_count
                      FROM ppl_assignments report
                     WHERE report.tenant_id = p.tenant_id
                       AND report.manager_assignment_key = a.assignment_key
                       AND report.effective_start_date <= :asOf
                       AND (report.effective_end_date IS NULL
                            OR report.effective_end_date >= :asOf)
                       AND report.assignment_status IN ('ACTIVE', 'SUSPENDED', 'PENDING')
              ) report_count ON TRUE
              LEFT JOIN LATERAL (
                    SELECT candidate.display_value
                      FROM ppl_contacts candidate
                     WHERE candidate.tenant_id = p.tenant_id
                       AND candidate.person_id = p.person_id
                       AND candidate.contact_type = 'EMAIL'
                       AND candidate.usage_type = 'WORK'
                       AND candidate.visibility IN ('PUBLIC', 'INTERNAL')
                       AND (candidate.valid_from IS NULL OR candidate.valid_from <= :asOf)
                       AND (candidate.valid_to IS NULL OR candidate.valid_to >= :asOf)
                     ORDER BY candidate.primary_contact DESC, candidate.contact_id DESC
                     LIMIT 1
              ) contact ON TRUE
              LEFT JOIN LATERAL (
                    SELECT candidate.object_key
                      FROM ppl_profile_media candidate
                     WHERE candidate.tenant_id = p.tenant_id
                       AND candidate.person_id = p.person_id
                       AND candidate.lifecycle_state = 'ACTIVE'
                       AND candidate.visibility IN ('PUBLIC', 'INTERNAL')
                     ORDER BY candidate.profile_media_id DESC
                     LIMIT 1
              ) media ON TRUE
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PeopleDirectoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DirectoryRow> search(
            Long tenantId,
            long afterPersonId,
            String query,
            String workerStatus,
            LocalDate asOf,
            int limit) {
        return search(tenantId, afterPersonId, query, workerStatus, asOf, limit, true, Set.of());
    }

    public List<DirectoryRow> search(
            Long tenantId,
            long afterPersonId,
            String query,
            String workerStatus,
            LocalDate asOf,
            int limit,
            boolean tenantWide,
            Set<UUID> organizationIds) {
        StringBuilder sql = new StringBuilder(DIRECTORY_SELECT).append("""
             WHERE p.tenant_id = :tenantId
               AND p.person_id > :afterPersonId
            """);
        MapSqlParameterSource parameters = commonParameters(tenantId, asOf)
                .addValue("afterPersonId", afterPersonId)
                .addValue("limit", limit);
        if (query != null && !query.isBlank()) {
            sql.append("""
               AND (
                    LOWER(p.display_name) LIKE :query
                    OR LOWER(COALESCE(contact.display_value, '')) LIKE :query
                    OR LOWER(COALESCE(w.worker_number, '')) LIKE :query
                    OR LOWER(COALESCE(a.assignment_key, '')) LIKE :query
                    OR LOWER(COALESCE(a.business_title, '')) LIKE :query
                    OR LOWER(COALESCE(org.name, '')) LIKE :query
                    OR LOWER(COALESCE(job.name, '')) LIKE :query
                    OR LOWER(COALESCE(grade.name, '')) LIKE :query
                    OR LOWER(COALESCE(loc.name, '')) LIKE :query
                    OR LOWER(COALESCE(manager_person.display_name, '')) LIKE :query
               )
            """);
            parameters.addValue("query", "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (workerStatus != null && !workerStatus.isBlank()) {
            sql.append(" AND w.worker_status = :workerStatus\n");
            parameters.addValue("workerStatus", workerStatus);
        }
        if (!tenantWide) {
            sql.append(" AND org.public_id IN (:organizationIds)\n");
            parameters.addValue("organizationIds", organizationIds);
        }
        sql.append(" ORDER BY p.person_id ASC LIMIT :limit");
        return jdbc.query(sql.toString(), parameters, this::mapDirectoryRow);
    }

    public Optional<DirectoryRow> findByPublicId(Long tenantId, UUID publicId, LocalDate asOf) {
        return findByPublicId(tenantId, publicId, asOf, true, Set.of());
    }

    public Optional<DirectoryRow> findByPublicId(
            Long tenantId,
            UUID publicId,
            LocalDate asOf,
            boolean tenantWide,
            Set<UUID> organizationIds) {
        String sql = DIRECTORY_SELECT + " WHERE p.tenant_id = :tenantId AND p.public_id = :publicId"
                + (tenantWide ? "" : " AND org.public_id IN (:organizationIds)");
        MapSqlParameterSource parameters = commonParameters(tenantId, asOf)
                .addValue("publicId", publicId);
        if (!tenantWide) parameters.addValue("organizationIds", organizationIds);
        List<DirectoryRow> rows = jdbc.query(
                sql,
                parameters,
                this::mapDirectoryRow);
        return rows.stream().findFirst();
    }

    public List<AssignmentRow> findAssignments(Long tenantId, long personId) {
        String sql = """
                SELECT a.assignment_key,
                       a.assignment_status,
                       a.primary_assignment,
                       a.effective_start_date,
                       a.effective_end_date,
                       a.business_title,
                       org.name AS organization_name,
                       job.name AS job_profile_name,
                       grade.name AS grade_name,
                       loc.name AS location_name,
                       a.manager_assignment_key,
                       a.change_reason_code
                  FROM ppl_assignments a
                  JOIN ppl_work_relationships relationship
                    ON relationship.tenant_id = a.tenant_id
                   AND relationship.work_relationship_id = a.work_relationship_id
                  JOIN ppl_workers worker
                    ON worker.tenant_id = relationship.tenant_id
                   AND worker.worker_id = relationship.worker_id
                  LEFT JOIN ppl_organizations org
                    ON org.tenant_id = a.tenant_id AND org.organization_id = a.organization_id
                  LEFT JOIN ppl_job_profiles job
                    ON job.tenant_id = a.tenant_id AND job.job_profile_id = a.job_profile_id
                  LEFT JOIN ppl_job_grades grade
                    ON grade.tenant_id = a.tenant_id AND grade.job_grade_id = a.job_grade_id
                  LEFT JOIN ppl_locations loc
                    ON loc.tenant_id = a.tenant_id AND loc.location_id = a.location_id
                 WHERE a.tenant_id = :tenantId
                   AND worker.person_id = :personId
                 ORDER BY a.effective_start_date DESC,
                          a.effective_sequence DESC,
                          a.assignment_id DESC
                """;
        return jdbc.query(
                sql,
                new MapSqlParameterSource("tenantId", tenantId).addValue("personId", personId),
                (resultSet, rowNumber) -> new AssignmentRow(
                        resultSet.getString("assignment_key"),
                        resultSet.getString("assignment_status"),
                        resultSet.getBoolean("primary_assignment"),
                        date(resultSet, "effective_start_date"),
                        date(resultSet, "effective_end_date"),
                        resultSet.getString("business_title"),
                        resultSet.getString("organization_name"),
                        resultSet.getString("job_profile_name"),
                        resultSet.getString("grade_name"),
                        resultSet.getString("location_name"),
                        resultSet.getString("manager_assignment_key"),
                        resultSet.getString("change_reason_code")));
    }

    public List<WorkforceEntityRow> findWorkforceEntities(Long tenantId, long personId) {
        String sql = """
                SELECT worker.public_id AS worker_public_id,
                       worker.worker_number,
                       worker.worker_type,
                       worker.worker_status,
                       worker.original_hire_date,
                       relationship.public_id AS relationship_public_id,
                       relationship.relationship_key,
                       relationship.relationship_type,
                       relationship.primary_relationship,
                       relationship.start_date AS relationship_start_date,
                       relationship.end_date AS relationship_end_date,
                       relationship.projected_end_date,
                       employer.employer_key AS legal_employer_key,
                       employer.legal_name AS legal_employer_name,
                       employer.country_code AS legal_employer_country_code,
                       assignment.public_id AS assignment_public_id,
                       assignment.assignment_key,
                       assignment.assignment_status,
                       assignment.primary_assignment,
                       assignment.effective_start_date,
                       assignment.effective_end_date,
                       assignment.effective_sequence,
                       assignment.business_title,
                       organization.public_id AS organization_public_id,
                       organization.organization_key,
                       organization.name AS organization_name,
                       job.name AS job_profile_name,
                       grade.name AS job_grade_name,
                       location.location_key,
                       location.name AS location_name,
                       assignment.manager_assignment_key,
                       assignment.change_reason_code
                  FROM ppl_workers worker
                  JOIN ppl_work_relationships relationship
                    ON relationship.tenant_id = worker.tenant_id
                   AND relationship.worker_id = worker.worker_id
                  JOIN ppl_legal_employers employer
                    ON employer.tenant_id = relationship.tenant_id
                   AND employer.legal_employer_id = relationship.legal_employer_id
                  LEFT JOIN ppl_assignments assignment
                    ON assignment.tenant_id = relationship.tenant_id
                   AND assignment.work_relationship_id = relationship.work_relationship_id
                  LEFT JOIN ppl_organizations organization
                    ON organization.tenant_id = assignment.tenant_id
                   AND organization.organization_id = assignment.organization_id
                  LEFT JOIN ppl_job_profiles job
                    ON job.tenant_id = assignment.tenant_id
                   AND job.job_profile_id = assignment.job_profile_id
                  LEFT JOIN ppl_job_grades grade
                    ON grade.tenant_id = assignment.tenant_id
                   AND grade.job_grade_id = assignment.job_grade_id
                  LEFT JOIN ppl_locations location
                    ON location.tenant_id = assignment.tenant_id
                   AND location.location_id = assignment.location_id
                 WHERE worker.tenant_id = :tenantId
                   AND worker.person_id = :personId
                 ORDER BY worker.worker_id,
                          relationship.primary_relationship DESC,
                          relationship.start_date DESC,
                          assignment.effective_start_date DESC NULLS LAST,
                          assignment.effective_sequence DESC NULLS LAST,
                          assignment.assignment_id DESC NULLS LAST
                """;
        return jdbc.query(
                sql,
                new MapSqlParameterSource("tenantId", tenantId).addValue("personId", personId),
                (resultSet, rowNumber) -> new WorkforceEntityRow(
                        resultSet.getObject("worker_public_id", UUID.class),
                        resultSet.getString("worker_number"),
                        resultSet.getString("worker_type"),
                        resultSet.getString("worker_status"),
                        date(resultSet, "original_hire_date"),
                        resultSet.getObject("relationship_public_id", UUID.class),
                        resultSet.getString("relationship_key"),
                        resultSet.getString("relationship_type"),
                        resultSet.getBoolean("primary_relationship"),
                        date(resultSet, "relationship_start_date"),
                        date(resultSet, "relationship_end_date"),
                        date(resultSet, "projected_end_date"),
                        resultSet.getString("legal_employer_key"),
                        resultSet.getString("legal_employer_name"),
                        resultSet.getString("legal_employer_country_code"),
                        resultSet.getObject("assignment_public_id", UUID.class),
                        resultSet.getString("assignment_key"),
                        resultSet.getString("assignment_status"),
                        resultSet.getBoolean("primary_assignment"),
                        date(resultSet, "effective_start_date"),
                        date(resultSet, "effective_end_date"),
                        resultSet.getInt("effective_sequence"),
                        resultSet.getString("business_title"),
                        resultSet.getObject("organization_public_id", UUID.class),
                        resultSet.getString("organization_key"),
                        resultSet.getString("organization_name"),
                        resultSet.getString("job_profile_name"),
                        resultSet.getString("job_grade_name"),
                        resultSet.getString("location_key"),
                        resultSet.getString("location_name"),
                        resultSet.getString("manager_assignment_key"),
                        resultSet.getString("change_reason_code")));
    }

    private MapSqlParameterSource commonParameters(Long tenantId, LocalDate asOf) {
        return new MapSqlParameterSource("tenantId", tenantId).addValue("asOf", Date.valueOf(asOf));
    }

    private DirectoryRow mapDirectoryRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DirectoryRow(
                resultSet.getLong("person_id"),
                resultSet.getObject("public_id", UUID.class),
                resultSet.getString("display_name"),
                resultSet.getString("preferred_locale"),
                resultSet.getString("time_zone"),
                resultSet.getString("lifecycle_state"),
                resultSet.getString("worker_number"),
                resultSet.getString("worker_type"),
                resultSet.getString("worker_status"),
                date(resultSet, "original_hire_date"),
                resultSet.getString("assignment_key"),
                resultSet.getString("business_title"),
                resultSet.getString("manager_assignment_key"),
                date(resultSet, "assignment_effective_from"),
                resultSet.getObject("organization_public_id", UUID.class),
                resultSet.getString("organization_key"),
                resultSet.getString("organization_name"),
                resultSet.getString("job_profile_name"),
                resultSet.getString("management_level"),
                resultSet.getString("grade_key"),
                resultSet.getString("grade_name"),
                resultSet.getString("location_key"),
                resultSet.getString("location_name"),
                resultSet.getString("legal_employer_name"),
                resultSet.getObject("manager_person_public_id", UUID.class),
                resultSet.getString("manager_display_name"),
                resultSet.getInt("direct_report_count"),
                resultSet.getString("work_email"),
                resultSet.getString("profile_image_key"));
    }

    private LocalDate date(ResultSet resultSet, String column) throws SQLException {
        Date value = resultSet.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    public record DirectoryRow(
            long internalPersonId,
            UUID publicId,
            String displayName,
            String preferredLocale,
            String timeZone,
            String lifecycleState,
            String workerNumber,
            String workerType,
            String workerStatus,
            LocalDate originalHireDate,
            String assignmentKey,
            String businessTitle,
            String managerAssignmentKey,
            LocalDate assignmentEffectiveFrom,
            UUID organizationPublicId,
            String organizationKey,
            String organizationName,
            String jobProfileName,
            String managementLevel,
            String gradeKey,
            String gradeName,
            String locationKey,
            String locationName,
            String legalEmployerName,
            UUID managerPersonPublicId,
            String managerDisplayName,
            int directReportCount,
            String workEmail,
            String profileImageKey) {
    }

    public record AssignmentRow(
            String assignmentKey,
            String assignmentStatus,
            boolean primaryAssignment,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            String businessTitle,
            String organizationName,
            String jobProfileName,
            String jobGradeName,
            String locationName,
            String managerAssignmentKey,
            String changeReasonCode) {
    }

    public record WorkforceEntityRow(
            UUID workerId,
            String workerNumber,
            String workerType,
            String workerStatus,
            LocalDate originalHireDate,
            UUID workRelationshipId,
            String relationshipKey,
            String relationshipType,
            boolean primaryRelationship,
            LocalDate relationshipStartDate,
            LocalDate relationshipEndDate,
            LocalDate projectedEndDate,
            String legalEmployerKey,
            String legalEmployerName,
            String legalEmployerCountryCode,
            UUID assignmentId,
            String assignmentKey,
            String assignmentStatus,
            boolean primaryAssignment,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            int effectiveSequence,
            String businessTitle,
            UUID organizationId,
            String organizationKey,
            String organizationName,
            String jobProfileName,
            String jobGradeName,
            String locationKey,
            String locationName,
            String managerAssignmentKey,
            String changeReasonCode) {
    }
}
