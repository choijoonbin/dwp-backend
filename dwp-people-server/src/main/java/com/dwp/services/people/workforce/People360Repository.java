package com.dwp.services.people.workforce;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-bound, effective-dated source reads for the People 360 owner projection. */
@Repository
public class People360Repository {

    private final NamedParameterJdbcTemplate jdbc;

    public People360Repository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PersonRow> findPerson(Long tenantId, UUID personPublicId) {
        return jdbc.query("""
                SELECT person_id, public_id, display_name, preferred_locale,
                       time_zone, lifecycle_state, version
                  FROM ppl_persons
                 WHERE tenant_id = :tenantId
                   AND public_id = :personPublicId
                   AND lifecycle_state <> 'MERGED'
                """, new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("personPublicId", personPublicId),
                (result, ignored) -> new PersonRow(
                        result.getLong("person_id"),
                        result.getObject("public_id", UUID.class),
                        result.getString("display_name"),
                        result.getString("preferred_locale"),
                        result.getString("time_zone"),
                        result.getString("lifecycle_state"),
                        result.getLong("version")))
                .stream().findFirst();
    }

    public List<CurrentEmploymentRow> findCurrentEmployments(
            Long tenantId,
            long personId,
            LocalDate asOf) {
        return jdbc.query("""
                SELECT worker.worker_id,
                       worker.public_id AS worker_public_id,
                       worker.worker_number,
                       worker.worker_type,
                       worker.worker_status,
                       worker.original_hire_date,
                       worker.version AS worker_version,
                       relationship.public_id AS relationship_public_id,
                       relationship.relationship_type,
                       relationship.start_date AS relationship_start_date,
                       relationship.end_date AS relationship_end_date,
                       relationship.version AS relationship_version,
                       employer.legal_name AS legal_employer_name,
                       assignment.public_id AS assignment_public_id,
                       assignment.assignment_key,
                       assignment.assignment_status,
                       assignment.business_title,
                       assignment.effective_start_date,
                       assignment.effective_end_date,
                       assignment.version AS assignment_version,
                       organization.public_id AS organization_public_id,
                       organization.name AS organization_name,
                       job.name AS job_profile_name,
                       grade.name AS job_grade_name,
                       location.name AS location_name,
                       manager.person_public_id AS manager_person_public_id,
                       manager.display_name AS manager_display_name
                  FROM ppl_workers worker
                  JOIN ppl_work_relationships relationship
                    ON relationship.tenant_id = worker.tenant_id
                   AND relationship.worker_id = worker.worker_id
                   AND relationship.start_date <= :asOf
                   AND (relationship.end_date IS NULL OR relationship.end_date >= :asOf)
                  JOIN ppl_legal_employers employer
                    ON employer.tenant_id = relationship.tenant_id
                   AND employer.legal_employer_id = relationship.legal_employer_id
                  LEFT JOIN ppl_assignments assignment
                    ON assignment.tenant_id = relationship.tenant_id
                   AND assignment.work_relationship_id = relationship.work_relationship_id
                   AND assignment.primary_assignment = TRUE
                   AND assignment.assignment_status IN ('ACTIVE', 'SUSPENDED', 'PENDING')
                   AND assignment.effective_start_date <= :asOf
                   AND (assignment.effective_end_date IS NULL
                        OR assignment.effective_end_date >= :asOf)
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
                  LEFT JOIN LATERAL (
                      SELECT manager_person.public_id AS person_public_id,
                             manager_person.display_name
                        FROM ppl_assignments manager_assignment
                        JOIN ppl_work_relationships manager_relationship
                          ON manager_relationship.tenant_id = manager_assignment.tenant_id
                         AND manager_relationship.work_relationship_id =
                             manager_assignment.work_relationship_id
                        JOIN ppl_workers manager_worker
                          ON manager_worker.tenant_id = manager_relationship.tenant_id
                         AND manager_worker.worker_id = manager_relationship.worker_id
                        JOIN ppl_persons manager_person
                          ON manager_person.tenant_id = manager_worker.tenant_id
                         AND manager_person.person_id = manager_worker.person_id
                       WHERE manager_assignment.tenant_id = assignment.tenant_id
                         AND manager_assignment.assignment_key =
                             assignment.manager_assignment_key
                         AND manager_assignment.assignment_status IN
                             ('ACTIVE', 'SUSPENDED', 'PENDING')
                         AND manager_assignment.effective_start_date <= :asOf
                         AND (manager_assignment.effective_end_date IS NULL
                              OR manager_assignment.effective_end_date >= :asOf)
                       ORDER BY manager_assignment.effective_start_date DESC,
                                manager_assignment.effective_sequence DESC,
                                manager_assignment.assignment_id DESC
                       LIMIT 1
                  ) manager ON TRUE
                 WHERE worker.tenant_id = :tenantId
                   AND worker.person_id = :personId
                   AND worker.worker_status IN ('ACTIVE', 'LEAVE', 'PENDING')
                 ORDER BY relationship.primary_relationship DESC,
                          relationship.start_date DESC,
                          worker.worker_id,
                          assignment.effective_start_date DESC NULLS LAST,
                          assignment.effective_sequence DESC NULLS LAST,
                          assignment.assignment_id DESC NULLS LAST
                """, parameters(tenantId, personId, asOf), this::employment);
    }

    private MapSqlParameterSource parameters(Long tenantId, long personId, LocalDate asOf) {
        return new MapSqlParameterSource("tenantId", tenantId)
                .addValue("personId", personId)
                .addValue("asOf", Date.valueOf(asOf));
    }

    private CurrentEmploymentRow employment(ResultSet result, int ignored) throws SQLException {
        return new CurrentEmploymentRow(
                result.getLong("worker_id"),
                result.getObject("worker_public_id", UUID.class),
                result.getString("worker_number"),
                result.getString("worker_type"),
                result.getString("worker_status"),
                date(result, "original_hire_date"),
                result.getLong("worker_version"),
                result.getObject("relationship_public_id", UUID.class),
                result.getString("relationship_type"),
                date(result, "relationship_start_date"),
                date(result, "relationship_end_date"),
                result.getLong("relationship_version"),
                result.getString("legal_employer_name"),
                result.getObject("assignment_public_id", UUID.class),
                result.getString("assignment_key"),
                result.getString("assignment_status"),
                result.getString("business_title"),
                date(result, "effective_start_date"),
                date(result, "effective_end_date"),
                nullableLong(result, "assignment_version"),
                result.getObject("organization_public_id", UUID.class),
                result.getString("organization_name"),
                result.getString("job_profile_name"),
                result.getString("job_grade_name"),
                result.getString("location_name"),
                result.getObject("manager_person_public_id", UUID.class),
                result.getString("manager_display_name"));
    }

    private LocalDate date(ResultSet result, String column) throws SQLException {
        Date value = result.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    public record PersonRow(
            long internalPersonId,
            UUID personId,
            String displayName,
            String preferredLocale,
            String timeZone,
            String lifecycleState,
            long version) {
    }

    public record CurrentEmploymentRow(
            long internalWorkerId,
            UUID workerId,
            String workerNumber,
            String workerType,
            String workerStatus,
            LocalDate originalHireDate,
            long workerVersion,
            UUID workRelationshipId,
            String relationshipType,
            LocalDate relationshipStartDate,
            LocalDate relationshipEndDate,
            long relationshipVersion,
            String legalEmployerName,
            UUID assignmentId,
            String assignmentKey,
            String assignmentStatus,
            String businessTitle,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            Long assignmentVersion,
            UUID organizationId,
            String organizationName,
            String jobProfileName,
            String jobGradeName,
            String locationName,
            UUID managerPersonId,
            String managerDisplayName) {
    }
}
