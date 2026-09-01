package com.dwp.services.people.hr;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

final class HrWorkerRepository {

    private final JdbcTemplate jdbc;

    HrWorkerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<HrRepository.WorkerIdentity> worker(Long tenantId, UUID personPublicId) {
        if (personPublicId == null) return Optional.empty();
        return jdbc.query("""
                SELECT worker.worker_id, person.public_id, person.display_name,
                       assignment.assignment_key, assignment.business_title,
                       organization.name AS organization_name,
                       manager_person.public_id AS manager_person_id,
                       manager_person.display_name AS manager_display_name,
                       (SELECT COUNT(*)
                          FROM ppl_assignments report_assignment
                          JOIN ppl_work_relationships report_relationship
                            ON report_relationship.tenant_id = report_assignment.tenant_id
                           AND report_relationship.work_relationship_id = report_assignment.work_relationship_id
                          JOIN ppl_workers report_worker
                            ON report_worker.tenant_id = report_relationship.tenant_id
                           AND report_worker.worker_id = report_relationship.worker_id
                         WHERE report_assignment.tenant_id = assignment.tenant_id
                           AND report_assignment.manager_assignment_key = assignment.assignment_key
                           AND report_assignment.assignment_status = 'ACTIVE'
                           AND report_relationship.start_date <= CURRENT_DATE
                           AND (report_relationship.end_date IS NULL
                                OR report_relationship.end_date >= CURRENT_DATE)
                           AND report_assignment.effective_start_date <= CURRENT_DATE
                           AND (report_assignment.effective_end_date IS NULL
                                OR report_assignment.effective_end_date >= CURRENT_DATE)
                           AND report_worker.worker_status IN ('ACTIVE', 'LEAVE')) AS direct_reports
                  FROM ppl_persons person
                  JOIN ppl_workers worker
                    ON worker.tenant_id = person.tenant_id
                   AND worker.person_id = person.person_id
                  LEFT JOIN LATERAL (
                      SELECT candidate.*
                        FROM ppl_work_relationships relationship
                        JOIN ppl_assignments candidate
                          ON candidate.tenant_id = relationship.tenant_id
                         AND candidate.work_relationship_id = relationship.work_relationship_id
                       WHERE relationship.tenant_id = worker.tenant_id
                         AND relationship.worker_id = worker.worker_id
                         AND relationship.start_date <= CURRENT_DATE
                         AND (relationship.end_date IS NULL
                              OR relationship.end_date >= CURRENT_DATE)
                         AND candidate.assignment_status = 'ACTIVE'
                         AND candidate.effective_start_date <= CURRENT_DATE
                         AND (candidate.effective_end_date IS NULL
                              OR candidate.effective_end_date >= CURRENT_DATE)
                       ORDER BY candidate.primary_assignment DESC,
                                candidate.effective_start_date DESC,
                                candidate.effective_sequence DESC
                       LIMIT 1
                  ) assignment ON TRUE
                  LEFT JOIN ppl_organizations organization
                    ON organization.tenant_id = assignment.tenant_id
                   AND organization.organization_id = assignment.organization_id
                  LEFT JOIN ppl_assignments manager_assignment
                    ON manager_assignment.tenant_id = assignment.tenant_id
                   AND manager_assignment.assignment_key = assignment.manager_assignment_key
                   AND manager_assignment.assignment_status = 'ACTIVE'
                   AND manager_assignment.effective_start_date <= CURRENT_DATE
                   AND (manager_assignment.effective_end_date IS NULL
                        OR manager_assignment.effective_end_date >= CURRENT_DATE)
                  LEFT JOIN ppl_work_relationships manager_relationship
                    ON manager_relationship.tenant_id = manager_assignment.tenant_id
                   AND manager_relationship.work_relationship_id = manager_assignment.work_relationship_id
                   AND manager_relationship.start_date <= CURRENT_DATE
                   AND (manager_relationship.end_date IS NULL
                        OR manager_relationship.end_date >= CURRENT_DATE)
                  LEFT JOIN ppl_workers manager_worker
                    ON manager_worker.tenant_id = manager_relationship.tenant_id
                   AND manager_worker.worker_id = manager_relationship.worker_id
                  LEFT JOIN ppl_persons manager_person
                    ON manager_person.tenant_id = manager_worker.tenant_id
                   AND manager_person.person_id = manager_worker.person_id
                 WHERE person.tenant_id = ?
                   AND person.public_id = ?
                   AND person.lifecycle_state = 'ACTIVE'
                   AND worker.worker_status IN ('ACTIVE', 'LEAVE')
                 LIMIT 1
                """, (result, ignored) -> new HrRepository.WorkerIdentity(
                result.getLong("worker_id"),
                result.getObject("public_id", UUID.class),
                result.getString("display_name"),
                result.getString("assignment_key"),
                result.getString("business_title"),
                result.getString("organization_name"),
                result.getObject("manager_person_id", UUID.class),
                result.getString("manager_display_name"),
                result.getInt("direct_reports")), tenantId, personPublicId).stream().findFirst();
    }

    boolean manages(Long tenantId, String managerAssignmentKey, long workerId) {
        if (managerAssignmentKey == null) return false;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM ppl_assignments assignment
                  JOIN ppl_work_relationships relationship
                    ON relationship.tenant_id = assignment.tenant_id
                   AND relationship.work_relationship_id = assignment.work_relationship_id
                 WHERE assignment.tenant_id = ?
                   AND relationship.worker_id = ?
                   AND assignment.manager_assignment_key = ?
                   AND assignment.assignment_status = 'ACTIVE'
                   AND assignment.effective_start_date <= CURRENT_DATE
                   AND (assignment.effective_end_date IS NULL
                        OR assignment.effective_end_date >= CURRENT_DATE)
                """, Integer.class, tenantId, workerId, managerAssignmentKey);
        return count != null && count > 0;
    }
}
