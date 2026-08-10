package com.dwp.services.people.organization;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OrganizationChartRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public OrganizationChartRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OrganizationRow> organizations(Long tenantId, LocalDate asOf) {
        return jdbc.query("""
                SELECT organization.organization_id,
                       organization.public_id,
                       organization.organization_key,
                       organization.name,
                       organization.short_name,
                       organization.organization_type,
                       COALESCE(type_catalog.display_name, organization.organization_type)
                           AS organization_type_name,
                       type_catalog.hierarchy_rank AS organization_type_rank,
                       COALESCE(type_catalog.root_candidate, FALSE) AS root_candidate,
                       organization.description,
                       organization.cost_center_key,
                       organization.color_token,
                       parent.public_id AS parent_public_id,
                       leader_person.public_id AS leader_public_id
                  FROM ppl_organizations organization
                  LEFT JOIN LATERAL (
                        SELECT relationship.parent_organization_id
                          FROM ppl_organization_relationships relationship
                         WHERE relationship.tenant_id = organization.tenant_id
                           AND relationship.child_organization_id = organization.organization_id
                           AND relationship.relationship_type = 'SUPERVISORY'
                           AND relationship.primary_relationship = TRUE
                           AND relationship.effective_start_date <= :asOf
                           AND (relationship.effective_end_date IS NULL
                                OR relationship.effective_end_date >= :asOf)
                         ORDER BY relationship.effective_start_date DESC,
                                  relationship.organization_relationship_id DESC
                         LIMIT 1
                  ) current_parent ON TRUE
                  LEFT JOIN ppl_organizations parent
                    ON parent.tenant_id = organization.tenant_id
                   AND parent.organization_id = COALESCE(
                        current_parent.parent_organization_id,
                        organization.parent_organization_id)
                  LEFT JOIN ppl_organization_type_catalog type_catalog
                    ON type_catalog.tenant_id = organization.tenant_id
                   AND type_catalog.type_key = organization.organization_type
                  LEFT JOIN LATERAL (
                        SELECT role_assignment.person_id
                          FROM ppl_organization_role_assignments role_assignment
                         WHERE role_assignment.tenant_id = organization.tenant_id
                           AND role_assignment.organization_id = organization.organization_id
                           AND role_assignment.role_code = 'LEADER'
                           AND role_assignment.effective_start_date <= :asOf
                           AND (role_assignment.effective_end_date IS NULL
                                OR role_assignment.effective_end_date >= :asOf)
                         ORDER BY role_assignment.primary_assignment DESC,
                                  role_assignment.effective_start_date DESC,
                                  role_assignment.organization_role_assignment_id DESC
                         LIMIT 1
                  ) current_leader ON TRUE
                  LEFT JOIN ppl_persons leader_person
                    ON leader_person.tenant_id = organization.tenant_id
                   AND leader_person.person_id = current_leader.person_id
                 WHERE organization.tenant_id = :tenantId
                   AND organization.lifecycle_state = 'ACTIVE'
                   AND organization.valid_from <= :asOf
                   AND (organization.valid_to IS NULL OR organization.valid_to >= :asOf)
                 ORDER BY organization.organization_id
                """, parameters(tenantId, asOf), (resultSet, rowNumber) -> new OrganizationRow(
                resultSet.getLong("organization_id"),
                resultSet.getObject("public_id", UUID.class),
                resultSet.getString("organization_key"),
                resultSet.getString("name"),
                resultSet.getString("short_name"),
                resultSet.getString("organization_type"),
                resultSet.getString("organization_type_name"),
                resultSet.getObject("organization_type_rank", Integer.class),
                resultSet.getBoolean("root_candidate"),
                resultSet.getString("description"),
                resultSet.getString("cost_center_key"),
                resultSet.getString("color_token"),
                resultSet.getObject("parent_public_id", UUID.class),
                resultSet.getObject("leader_public_id", UUID.class)));
    }

    public List<PersonRow> people(Long tenantId, LocalDate asOf) {
        return jdbc.query("""
                SELECT person.public_id,
                       person.display_name,
                       worker.worker_number,
                       worker.worker_type,
                       worker.worker_status,
                       assignment.assignment_key,
                       assignment.manager_assignment_key,
                       assignment.business_title,
                       assignment.full_time_equivalent,
                       organization.public_id AS organization_public_id,
                       position.public_id AS position_public_id,
                       position.position_key,
                       job.name AS job_profile_name,
                       job.management_level,
                       grade.grade_key,
                       grade.name AS grade_name,
                       COALESCE(grade.level_order, 0) AS grade_order,
                       location.location_key,
                       location.name AS location_name,
                       contact.display_value AS work_email
                  FROM ppl_persons person
                  JOIN LATERAL (
                        SELECT candidate.*
                          FROM ppl_workers candidate
                         WHERE candidate.tenant_id = person.tenant_id
                           AND candidate.person_id = person.person_id
                         ORDER BY CASE candidate.worker_status
                                      WHEN 'ACTIVE' THEN 0 WHEN 'LEAVE' THEN 1
                                      WHEN 'PENDING' THEN 2 ELSE 3 END,
                                  candidate.worker_id
                         LIMIT 1
                  ) worker ON TRUE
                  JOIN LATERAL (
                        SELECT candidate.*
                          FROM ppl_assignments candidate
                          JOIN ppl_work_relationships relationship
                            ON relationship.tenant_id = candidate.tenant_id
                           AND relationship.work_relationship_id = candidate.work_relationship_id
                         WHERE candidate.tenant_id = person.tenant_id
                           AND relationship.worker_id = worker.worker_id
                           AND candidate.effective_start_date <= :asOf
                           AND (candidate.effective_end_date IS NULL
                                OR candidate.effective_end_date >= :asOf)
                           AND candidate.assignment_status IN ('ACTIVE', 'SUSPENDED', 'PENDING')
                         ORDER BY candidate.primary_assignment DESC,
                                  candidate.effective_start_date DESC,
                                  candidate.effective_sequence DESC,
                                  candidate.assignment_id DESC
                         LIMIT 1
                  ) assignment ON TRUE
                  JOIN ppl_organizations organization
                    ON organization.tenant_id = assignment.tenant_id
                   AND organization.organization_id = assignment.organization_id
                  LEFT JOIN ppl_positions position
                    ON position.tenant_id = assignment.tenant_id
                   AND position.position_id = assignment.position_id
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
                        SELECT candidate.display_value
                          FROM ppl_contacts candidate
                         WHERE candidate.tenant_id = person.tenant_id
                           AND candidate.person_id = person.person_id
                           AND candidate.contact_type = 'EMAIL'
                           AND candidate.usage_type = 'WORK'
                           AND candidate.visibility IN ('PUBLIC', 'INTERNAL')
                           AND (candidate.valid_from IS NULL OR candidate.valid_from <= :asOf)
                           AND (candidate.valid_to IS NULL OR candidate.valid_to >= :asOf)
                         ORDER BY candidate.primary_contact DESC, candidate.contact_id DESC
                         LIMIT 1
                  ) contact ON TRUE
                 WHERE person.tenant_id = :tenantId
                   AND person.lifecycle_state = 'ACTIVE'
                   AND worker.worker_status IN ('ACTIVE', 'LEAVE', 'PENDING')
                 ORDER BY person.display_name, person.person_id
                """, parameters(tenantId, asOf), this::mapPerson);
    }

    public List<RelationshipRow> relationships(Long tenantId, LocalDate asOf) {
        return jdbc.query("""
                SELECT child.public_id AS child_public_id,
                       parent.public_id AS parent_public_id,
                       relationship.relationship_type,
                       relationship.primary_relationship
                  FROM ppl_organization_relationships relationship
                  JOIN ppl_organizations child
                    ON child.tenant_id = relationship.tenant_id
                   AND child.organization_id = relationship.child_organization_id
                  JOIN ppl_organizations parent
                    ON parent.tenant_id = relationship.tenant_id
                   AND parent.organization_id = relationship.parent_organization_id
                 WHERE relationship.tenant_id = :tenantId
                   AND relationship.effective_start_date <= :asOf
                   AND (relationship.effective_end_date IS NULL
                        OR relationship.effective_end_date >= :asOf)
                   AND child.lifecycle_state = 'ACTIVE'
                   AND parent.lifecycle_state = 'ACTIVE'
                 ORDER BY relationship.relationship_type,
                          relationship.organization_relationship_id
                """, parameters(tenantId, asOf), (resultSet, rowNumber) -> new RelationshipRow(
                resultSet.getObject("child_public_id", UUID.class),
                resultSet.getObject("parent_public_id", UUID.class),
                resultSet.getString("relationship_type"),
                resultSet.getBoolean("primary_relationship")));
    }

    public List<PositionRow> positions(Long tenantId, LocalDate asOf) {
        return jdbc.query("""
                SELECT position.public_id,
                       position.position_key,
                       position.title,
                       organization.public_id AS organization_public_id,
                       effective_parent.public_id AS reports_to_position_public_id,
                       position.position_status,
                       position.position_type,
                       position.criticality,
                       position.budgeted_fte,
                       position.annual_cost_amount,
                       position.cost_currency,
                       job.name AS job_profile_name,
                       location.name AS location_name,
                       position.availability_date
                  FROM ppl_positions position
                  JOIN ppl_organizations organization
                    ON organization.tenant_id = position.tenant_id
                   AND organization.organization_id = position.organization_id
                  LEFT JOIN LATERAL (
                       SELECT parent.public_id
                         FROM ppl_position_relationships relationship
                         JOIN ppl_positions parent
                           ON parent.tenant_id = relationship.tenant_id
                          AND parent.position_id = relationship.parent_position_id
                          AND parent.valid_from <= :asOf
                          AND (parent.valid_to IS NULL OR parent.valid_to >= :asOf)
                          AND parent.position_status <> 'CLOSED'
                        WHERE relationship.tenant_id = position.tenant_id
                          AND relationship.child_position_id = position.position_id
                          AND relationship.relationship_type = 'SUPERVISORY'
                          AND relationship.primary_relationship = TRUE
                          AND relationship.effective_start_date <= :asOf
                          AND (relationship.effective_end_date IS NULL
                               OR relationship.effective_end_date >= :asOf)
                        ORDER BY CASE relationship.relationship_source
                                     WHEN 'SCENARIO' THEN 0 WHEN 'HRIS' THEN 1
                                     WHEN 'POSITION' THEN 2 ELSE 3 END,
                                 relationship.effective_start_date DESC,
                                 relationship.position_relationship_id DESC
                        LIMIT 1
                  ) effective_parent ON TRUE
                  LEFT JOIN ppl_job_profiles job
                    ON job.tenant_id = position.tenant_id
                   AND job.job_profile_id = position.job_profile_id
                  LEFT JOIN ppl_locations location
                    ON location.tenant_id = position.tenant_id
                   AND location.location_id = position.location_id
                 WHERE position.tenant_id = :tenantId
                   AND position.valid_from <= :asOf
                   AND (position.valid_to IS NULL OR position.valid_to >= :asOf)
                   AND position.position_status <> 'CLOSED'
                 ORDER BY position.position_status, position.position_key
                """, parameters(tenantId, asOf), (resultSet, rowNumber) -> new PositionRow(
                resultSet.getObject("public_id", UUID.class),
                resultSet.getString("position_key"),
                resultSet.getString("title"),
                resultSet.getObject("organization_public_id", UUID.class),
                resultSet.getObject("reports_to_position_public_id", UUID.class),
                resultSet.getString("position_status"),
                resultSet.getString("position_type"),
                resultSet.getString("criticality"),
                resultSet.getBigDecimal("budgeted_fte"),
                resultSet.getBigDecimal("annual_cost_amount"),
                resultSet.getString("cost_currency"),
                resultSet.getString("job_profile_name"),
                resultSet.getString("location_name"),
                date(resultSet, "availability_date")));
    }

    public Optional<DesignPolicyRow> designPolicy(Long tenantId) {
        List<DesignPolicyRow> rows = jdbc.query("""
                SELECT minimum_manager_span,
                       maximum_manager_span,
                       maximum_layers,
                       maximum_contingent_percent,
                       maximum_vacancy_percent
                  FROM ppl_org_design_policies
                 WHERE tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", tenantId),
                (resultSet, rowNumber) -> new DesignPolicyRow(
                        resultSet.getInt("minimum_manager_span"),
                        resultSet.getInt("maximum_manager_span"),
                        resultSet.getInt("maximum_layers"),
                        resultSet.getBigDecimal("maximum_contingent_percent"),
                        resultSet.getBigDecimal("maximum_vacancy_percent")));
        return rows.stream().findFirst();
    }

    private MapSqlParameterSource parameters(Long tenantId, LocalDate asOf) {
        return new MapSqlParameterSource("tenantId", tenantId)
                .addValue("asOf", Date.valueOf(asOf));
    }

    private PersonRow mapPerson(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PersonRow(
                resultSet.getObject("public_id", UUID.class),
                resultSet.getString("display_name"),
                resultSet.getString("worker_number"),
                resultSet.getString("worker_type"),
                resultSet.getString("worker_status"),
                resultSet.getString("assignment_key"),
                resultSet.getString("manager_assignment_key"),
                resultSet.getString("business_title"),
                resultSet.getBigDecimal("full_time_equivalent"),
                resultSet.getObject("organization_public_id", UUID.class),
                resultSet.getObject("position_public_id", UUID.class),
                resultSet.getString("position_key"),
                resultSet.getString("job_profile_name"),
                resultSet.getString("management_level"),
                resultSet.getString("grade_key"),
                resultSet.getString("grade_name"),
                resultSet.getInt("grade_order"),
                resultSet.getString("location_key"),
                resultSet.getString("location_name"),
                resultSet.getString("work_email"));
    }

    private LocalDate date(ResultSet resultSet, String column) throws SQLException {
        Date value = resultSet.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    public record OrganizationRow(
            long internalId,
            UUID publicId,
            String key,
            String name,
            String shortName,
            String type,
            String typeName,
            Integer typeRank,
            boolean rootCandidate,
            String description,
            String costCenterKey,
            String colorToken,
            UUID parentPublicId,
            UUID leaderPublicId) {
    }

    public record PersonRow(
            UUID publicId,
            String displayName,
            String workerNumber,
            String workerType,
            String workerStatus,
            String assignmentKey,
            String managerAssignmentKey,
            String businessTitle,
            BigDecimal fullTimeEquivalent,
            UUID organizationPublicId,
            UUID positionPublicId,
            String positionKey,
            String jobProfileName,
            String managementLevel,
            String gradeKey,
            String gradeName,
            int gradeOrder,
            String locationKey,
            String locationName,
            String workEmail) {
    }

    public record RelationshipRow(
            UUID childPublicId,
            UUID parentPublicId,
            String type,
            boolean primary) {
    }

    public record PositionRow(
            UUID publicId,
            String key,
            String title,
            UUID organizationPublicId,
            UUID reportsToPositionPublicId,
            String status,
            String type,
            String criticality,
            BigDecimal budgetedFte,
            BigDecimal annualCostAmount,
            String costCurrency,
            String jobProfileName,
            String locationName,
            LocalDate availabilityDate) {
    }

    public record DesignPolicyRow(
            int minimumManagerSpan,
            int maximumManagerSpan,
            int maximumLayers,
            BigDecimal maximumContingentPercent,
            BigDecimal maximumVacancyPercent) {
    }
}
