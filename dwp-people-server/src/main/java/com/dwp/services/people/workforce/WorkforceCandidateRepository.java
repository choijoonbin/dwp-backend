package com.dwp.services.people.workforce;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Reads only the five fields approved for organization design candidates. */
@Repository
public class WorkforceCandidateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public WorkforceCandidateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<WorkforceCandidateDtos.OrganizationCandidate> list(Long tenantId) {
        return jdbc.query("""
                SELECT person.public_id,
                       person.display_name,
                       organization.name AS organization,
                       COALESCE(position.title, assignment.business_title) AS position,
                       CASE WHEN worker.worker_status = 'ACTIVE'
                                  AND assignment.assignment_status = 'ACTIVE'
                            THEN 'ELIGIBLE' ELSE 'INELIGIBLE' END AS eligibility
                  FROM ppl_persons person
                  JOIN LATERAL (
                       SELECT candidate.worker_id, candidate.worker_status
                         FROM ppl_workers candidate
                        WHERE candidate.tenant_id = person.tenant_id
                          AND candidate.person_id = person.person_id
                          AND candidate.worker_status IN ('ACTIVE', 'LEAVE', 'PENDING')
                        ORDER BY CASE candidate.worker_status
                                     WHEN 'ACTIVE' THEN 0 WHEN 'LEAVE' THEN 1 ELSE 2 END,
                                 candidate.worker_id
                        LIMIT 1
                  ) worker ON TRUE
                  JOIN LATERAL (
                       SELECT candidate.assignment_status, candidate.business_title,
                              candidate.organization_id, candidate.position_id
                         FROM ppl_work_relationships relationship
                         JOIN ppl_assignments candidate
                           ON candidate.tenant_id = relationship.tenant_id
                          AND candidate.work_relationship_id = relationship.work_relationship_id
                        WHERE relationship.tenant_id = person.tenant_id
                          AND relationship.worker_id = worker.worker_id
                          AND relationship.start_date <= CURRENT_DATE
                          AND (relationship.end_date IS NULL
                               OR relationship.end_date >= CURRENT_DATE)
                          AND candidate.assignment_status IN ('ACTIVE', 'SUSPENDED', 'PENDING')
                          AND candidate.effective_start_date <= CURRENT_DATE
                          AND (candidate.effective_end_date IS NULL
                               OR candidate.effective_end_date >= CURRENT_DATE)
                        ORDER BY candidate.primary_assignment DESC,
                                 candidate.effective_start_date DESC,
                                 candidate.effective_sequence DESC,
                                 candidate.assignment_id DESC
                        LIMIT 1
                  ) assignment ON TRUE
                  JOIN ppl_organizations organization
                    ON organization.tenant_id = person.tenant_id
                   AND organization.organization_id = assignment.organization_id
                   AND organization.lifecycle_state = 'ACTIVE'
                   AND organization.valid_from <= CURRENT_DATE
                   AND (organization.valid_to IS NULL
                        OR organization.valid_to >= CURRENT_DATE)
                  LEFT JOIN ppl_positions position
                    ON position.tenant_id = person.tenant_id
                   AND position.position_id = assignment.position_id
                   AND position.valid_from <= CURRENT_DATE
                   AND (position.valid_to IS NULL OR position.valid_to >= CURRENT_DATE)
                 WHERE person.tenant_id = :tenantId
                   AND person.lifecycle_state = 'ACTIVE'
                 ORDER BY person.display_name, person.public_id
                 LIMIT 500
                """, new MapSqlParameterSource("tenantId", tenantId),
                (result, ignored) -> new WorkforceCandidateDtos.OrganizationCandidate(
                        result.getObject("public_id", UUID.class),
                        result.getString("display_name"),
                        result.getString("organization"),
                        result.getString("position"),
                        WorkforceCandidateDtos.Eligibility.valueOf(
                                result.getString("eligibility"))));
    }
}
