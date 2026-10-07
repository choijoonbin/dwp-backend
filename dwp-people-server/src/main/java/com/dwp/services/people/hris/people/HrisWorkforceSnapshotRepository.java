package com.dwp.services.people.hris.people;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotV1;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** HRM-owned, tenant and effective-date bound source for the PER snapshot port. */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "dwp.hris.performance.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
class HrisWorkforceSnapshotRepository {

    private static final UUID EMPTY_ORGANIZATION = new UUID(0L, 0L);
    private final NamedParameterJdbcTemplate jdbc;

    HrisWorkforceSnapshotRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<WorkforceSnapshotV1> page(
            long tenantId,
            HcmPopulationRepository.PopulationScope scope,
            Instant asOf,
            String afterPosition,
            int limitPlusOne) {
        String afterAssignment = null;
        String afterWorker = null;
        if (afterPosition != null) {
            String[] parts = afterPosition.split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException(
                    "Snapshot cursor position is not canonical.");
            afterAssignment = parts[0];
            afterWorker = parts[1];
        }
        Set<UUID> organizations = scope.organizationIds().isEmpty()
                ? Set.of(EMPTY_ORGANIZATION) : scope.organizationIds();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("asOf", Date.valueOf(asOf.atZone(ZoneOffset.UTC).toLocalDate()))
                .addValue("actorWorkerId", scope.actorWorkerId())
                .addValue("managerAssignmentKey", scope.managerAssignmentKey())
                .addValue("tenantWide", scope.tenantWide())
                .addValue("organizationIds", organizations)
                .addValue("afterAssignment", afterAssignment)
                .addValue("afterWorker", afterWorker)
                .addValue("limit", limitPlusOne);
        return jdbc.query("""
                SELECT worker.public_id AS worker_public_id,
                       assignment.public_id AS assignment_public_id,
                       CASE worker.worker_status
                           WHEN 'PENDING' THEN 'PENDING'
                           WHEN 'TERMINATED' THEN 'TERMINATED'
                           WHEN 'ACTIVE' THEN 'ACTIVE'
                           WHEN 'LEAVE' THEN 'ACTIVE'
                           ELSE 'INACTIVE'
                       END AS workforce_status,
                       organization.public_id AS organization_public_id,
                       manager.public_id AS manager_assignment_public_id
                  FROM ppl_workers worker
                  JOIN LATERAL (
                      SELECT relationship.work_relationship_id
                        FROM ppl_work_relationships relationship
                       WHERE relationship.tenant_id = worker.tenant_id
                         AND relationship.worker_id = worker.worker_id
                         AND relationship.start_date <= :asOf
                         AND (relationship.end_date IS NULL
                              OR relationship.end_date >= :asOf)
                       ORDER BY relationship.primary_relationship DESC,
                                relationship.start_date DESC,
                                relationship.work_relationship_id DESC
                       LIMIT 1
                  ) relationship ON TRUE
                  JOIN LATERAL (
                      SELECT candidate.public_id, candidate.assignment_key,
                             candidate.manager_assignment_key,
                             candidate.organization_id
                        FROM ppl_assignments candidate
                       WHERE candidate.tenant_id = worker.tenant_id
                         AND candidate.work_relationship_id =
                             relationship.work_relationship_id
                         AND candidate.primary_assignment = TRUE
                         AND candidate.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
                         AND candidate.effective_start_date <= :asOf
                         AND (candidate.effective_end_date IS NULL
                              OR candidate.effective_end_date >= :asOf)
                       ORDER BY candidate.effective_start_date DESC,
                                candidate.effective_sequence DESC,
                                candidate.assignment_id DESC
                       LIMIT 1
                  ) assignment ON TRUE
                  JOIN ppl_organizations organization
                    ON organization.tenant_id = worker.tenant_id
                   AND organization.organization_id = assignment.organization_id
                  LEFT JOIN LATERAL (
                      SELECT candidate.public_id
                        FROM ppl_assignments candidate
                       WHERE candidate.tenant_id = worker.tenant_id
                         AND candidate.assignment_key = assignment.manager_assignment_key
                         AND candidate.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
                         AND candidate.effective_start_date <= :asOf
                         AND (candidate.effective_end_date IS NULL
                              OR candidate.effective_end_date >= :asOf)
                       ORDER BY candidate.effective_start_date DESC,
                                candidate.effective_sequence DESC,
                                candidate.assignment_id DESC
                       LIMIT 1
                  ) manager ON TRUE
                 WHERE worker.tenant_id = :tenantId
                   AND worker.worker_id <> :actorWorkerId
                   AND (:tenantWide
                        OR assignment.manager_assignment_key = :managerAssignmentKey
                        OR organization.public_id IN (:organizationIds))
                   AND (:afterAssignment IS NULL
                        OR assignment.public_id::TEXT > :afterAssignment
                        OR (assignment.public_id::TEXT = :afterAssignment
                            AND worker.public_id::TEXT > :afterWorker))
                 ORDER BY assignment.public_id::TEXT, worker.public_id::TEXT
                 LIMIT :limit
                """, parameters, (result, ignored) -> new WorkforceSnapshotV1(
                result.getObject("worker_public_id", UUID.class),
                result.getObject("assignment_public_id", UUID.class),
                result.getString("workforce_status"),
                result.getObject("organization_public_id", UUID.class),
                result.getObject("manager_assignment_public_id", UUID.class),
                null,
                null));
    }

    List<WorkforceSnapshotV1> immutablePage(
            long tenantId,
            HcmPopulationRepository.PopulationScope scope,
            Instant asOf,
            String afterPosition,
            int limitPlusOne) {
        return List.copyOf(new ArrayList<>(page(
                tenantId, scope, asOf, afterPosition, limitPlusOne)));
    }
}
