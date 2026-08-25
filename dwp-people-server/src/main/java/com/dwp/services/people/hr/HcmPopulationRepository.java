package com.dwp.services.people.hr;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Tenant-bound relationship and target-population queries owned by People/HCM. */
@Repository
public class HcmPopulationRepository {

    private static final UUID EMPTY_ORGANIZATION =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    private static final String CURRENT_ASSIGNMENT = """
            JOIN ppl_workers worker
              ON worker.tenant_id = person.tenant_id AND worker.person_id = person.person_id
             AND worker.worker_status IN ('ACTIVE', 'LEAVE')
            JOIN ppl_work_relationships relationship
              ON relationship.tenant_id = worker.tenant_id
             AND relationship.worker_id = worker.worker_id
             AND relationship.start_date <= CURRENT_DATE
             AND (relationship.end_date IS NULL OR relationship.end_date >= CURRENT_DATE)
            JOIN ppl_assignments assignment
              ON assignment.tenant_id = relationship.tenant_id
             AND assignment.work_relationship_id = relationship.work_relationship_id
             AND assignment.assignment_status IN ('ACTIVE', 'SUSPENDED', 'PENDING')
             AND assignment.effective_start_date <= CURRENT_DATE
             AND (assignment.effective_end_date IS NULL
                  OR assignment.effective_end_date >= CURRENT_DATE)
            LEFT JOIN ppl_organizations organization
              ON organization.tenant_id = assignment.tenant_id
             AND organization.organization_id = assignment.organization_id
            """;

    private static final String VISIBLE_WORKERS = """
            WITH visible_workers AS (
                SELECT DISTINCT worker.worker_id
                  FROM ppl_persons person
            """ + CURRENT_ASSIGNMENT + """
                 WHERE person.tenant_id = :tenantId
                   AND worker.worker_id <> :actorWorkerId
                   AND (:tenantWide OR assignment.manager_assignment_key = :managerAssignmentKey
                        OR organization.public_id IN (:organizationIds))
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public HcmPopulationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ActorWorkforce> actor(Long tenantId, UUID personPublicId) {
        return actor(tenantId, personPublicId, false);
    }

    /**
     * Locks the actor's current worker, relationship, and assignment proof. This
     * prevents a manager assignment from being ended or replaced between scope
     * recomputation and a target-population mutation.
     */
    public Optional<ActorWorkforce> actorForMutation(Long tenantId, UUID personPublicId) {
        return actor(tenantId, personPublicId, true);
    }

    private Optional<ActorWorkforce> actor(
            Long tenantId, UUID personPublicId, boolean lockForMutation) {
        if (tenantId == null || personPublicId == null) return Optional.empty();
        String lock = lockForMutation
                ? " FOR SHARE OF worker, relationship, assignment"
                : "";
        return jdbc.query("""
                SELECT worker.worker_id, person.public_id AS person_public_id,
                       person.display_name, assignment.assignment_key,
                       assignment.business_title, organization.name AS organization_name,
                       worker.version AS worker_version,
                       assignment.version AS assignment_version,
                       organization.version AS organization_version
                  FROM ppl_persons person
                """ + CURRENT_ASSIGNMENT + """
                 WHERE person.tenant_id = :tenantId AND person.public_id = :personPublicId
                 ORDER BY assignment.primary_assignment DESC,
                          assignment.effective_start_date DESC,
                          assignment.effective_sequence DESC
                 LIMIT 1
                """ + lock, parameters(tenantId).addValue("personPublicId", personPublicId),
                (result, ignored) -> new ActorWorkforce(
                        result.getLong("worker_id"),
                        result.getObject("person_public_id", UUID.class),
                        result.getString("display_name"),
                        result.getString("assignment_key"),
                        result.getString("business_title"),
                        result.getString("organization_name"),
                        result.getLong("worker_version"),
                        result.getLong("assignment_version"),
                        result.getObject("organization_version", Long.class)))
                .stream().findFirst();
    }

    public Optional<String> tenantRevision(Long tenantId) {
        return jdbc.query("""
                SELECT COUNT(*) AS organization_count,
                       COALESCE(MAX(version), 0) AS maximum_version
                  FROM ppl_organizations
                 WHERE tenant_id = :tenantId AND lifecycle_state = 'ACTIVE'
                """, parameters(tenantId), (result, ignored) ->
                result.getLong("organization_count") + ":"
                        + result.getLong("maximum_version"))
                .stream().filter(value -> !value.startsWith("0:"))
                .findFirst();
    }

    public Optional<PopulationEvidence> populationEvidence(
            Long tenantId, PopulationScope scope) {
        return jdbc.query("""
                SELECT COUNT(DISTINCT worker.worker_id) AS population_count,
                       MD5(STRING_AGG(DISTINCT
                           worker.public_id::TEXT || ':' || worker.version::TEXT || ':' ||
                           assignment.public_id::TEXT || ':' || assignment.version::TEXT,
                           ',' ORDER BY
                           worker.public_id::TEXT || ':' || worker.version::TEXT || ':' ||
                           assignment.public_id::TEXT || ':' || assignment.version::TEXT))
                           AS population_revision
                  FROM ppl_persons person
                """ + CURRENT_ASSIGNMENT + """
                 WHERE person.tenant_id = :tenantId
                   AND worker.worker_id <> :actorWorkerId
                   AND (:tenantWide OR assignment.manager_assignment_key = :managerAssignmentKey
                        OR organization.public_id IN (:organizationIds))
                """, populationParameters(tenantId, scope),
                (result, ignored) -> new PopulationEvidence(
                        result.getLong("population_count"),
                        result.getString("population_revision")))
                .stream().filter(value -> value.count() > 0 && value.revision() != null)
                .findFirst();
    }

    public boolean containsWorker(Long tenantId, PopulationScope scope, long targetWorkerId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT worker.worker_id)
                  FROM ppl_persons person
                """ + CURRENT_ASSIGNMENT + """
                 WHERE person.tenant_id = :tenantId
                   AND worker.worker_id = :targetWorkerId
                   AND worker.worker_id <> :actorWorkerId
                   AND (:tenantWide OR assignment.manager_assignment_key = :managerAssignmentKey
                        OR organization.public_id IN (:organizationIds))
                """, populationParameters(tenantId, scope)
                        .addValue("targetWorkerId", targetWorkerId), Integer.class);
        return count != null && count == 1;
    }

    /**
     * Locks every relationship/assignment row that proves current membership.
     * A concurrent reassignment must wait until the approval transaction ends.
     */
    public boolean lockWorkerInPopulation(
            Long tenantId, PopulationScope scope, long targetWorkerId) {
        List<Long> rows = jdbc.query("""
                SELECT worker.worker_id
                  FROM ppl_persons person
                """ + CURRENT_ASSIGNMENT + """
                 WHERE person.tenant_id = :tenantId
                   AND worker.worker_id = :targetWorkerId
                   AND worker.worker_id <> :actorWorkerId
                   AND (:tenantWide OR assignment.manager_assignment_key = :managerAssignmentKey
                        OR organization.public_id IN (:organizationIds))
                 FOR SHARE OF worker, relationship, assignment
                """, populationParameters(tenantId, scope)
                        .addValue("targetWorkerId", targetWorkerId),
                (result, ignored) -> result.getLong("worker_id"));
        return !rows.isEmpty();
    }

    public List<HrDtos.TeamMember> teamMembers(Long tenantId, PopulationScope scope) {
        return jdbc.query("""
                SELECT DISTINCT ON (worker.worker_id)
                       person.public_id, person.display_name, assignment.business_title,
                       organization.name AS organization_name,
                       (SELECT COUNT(*)
                          FROM ppl_assignments report
                         WHERE report.tenant_id = assignment.tenant_id
                           AND report.manager_assignment_key = assignment.assignment_key
                           AND report.assignment_status IN ('ACTIVE', 'SUSPENDED', 'PENDING')
                           AND report.effective_start_date <= CURRENT_DATE
                           AND (report.effective_end_date IS NULL
                                OR report.effective_end_date >= CURRENT_DATE)) AS direct_reports
                  FROM ppl_persons person
                """ + CURRENT_ASSIGNMENT + """
                 WHERE person.tenant_id = :tenantId
                   AND worker.worker_id <> :actorWorkerId
                   AND (:tenantWide OR assignment.manager_assignment_key = :managerAssignmentKey
                        OR organization.public_id IN (:organizationIds))
                 ORDER BY worker.worker_id, assignment.primary_assignment DESC,
                          assignment.effective_start_date DESC
                 LIMIT 500
                """, populationParameters(tenantId, scope),
                (result, ignored) -> new HrDtos.TeamMember(
                        result.getObject("public_id", UUID.class),
                        result.getString("display_name"),
                        result.getString("business_title"),
                        result.getString("organization_name"),
                        result.getInt("direct_reports")));
    }

    public List<HrDtos.ApprovalItem> teamQueue(
            Long tenantId, PopulationScope scope, String domain) {
        if ("TIME".equals(domain)) return timeQueue(tenantId, scope);
        if (!"ABSENCE".equals(domain)) throw new IllegalArgumentException(
                "Only TIME and ABSENCE team queues are supported.");
        return absenceQueue(tenantId, scope);
    }

    public List<HrDtos.TeamAbsence> teamAbsences(Long tenantId, PopulationScope scope) {
        return jdbc.query("""
                SELECT request.public_id, person.public_id AS person_public_id,
                       person.display_name, assignment.business_title,
                       plan.name AS plan_name, request.start_at, request.end_at, request.status
                  FROM abs_leave_requests request
                  JOIN abs_leave_plans plan
                    ON plan.tenant_id = request.tenant_id
                   AND plan.leave_plan_id = request.leave_plan_id
                  JOIN ppl_workers worker
                    ON worker.tenant_id = request.tenant_id
                   AND worker.worker_id = request.worker_id
                  JOIN ppl_persons person
                    ON person.tenant_id = worker.tenant_id
                   AND person.person_id = worker.person_id
                  JOIN LATERAL (
                      SELECT candidate.assignment_key, candidate.business_title,
                             candidate.manager_assignment_key, candidate.organization_id
                        FROM ppl_work_relationships relationship
                        JOIN ppl_assignments candidate
                          ON candidate.tenant_id = relationship.tenant_id
                         AND candidate.work_relationship_id = relationship.work_relationship_id
                       WHERE relationship.tenant_id = worker.tenant_id
                         AND relationship.worker_id = worker.worker_id
                         AND candidate.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
                         AND candidate.effective_start_date <= CURRENT_DATE
                         AND (candidate.effective_end_date IS NULL
                              OR candidate.effective_end_date >= CURRENT_DATE)
                       ORDER BY candidate.primary_assignment DESC,
                                candidate.effective_start_date DESC
                       LIMIT 1
                  ) assignment ON TRUE
                  LEFT JOIN ppl_organizations organization
                    ON organization.tenant_id = request.tenant_id
                   AND organization.organization_id = assignment.organization_id
                 WHERE request.tenant_id = :tenantId
                   AND worker.worker_id <> :actorWorkerId
                   AND (:tenantWide OR assignment.manager_assignment_key = :managerAssignmentKey
                        OR organization.public_id IN (:organizationIds))
                   AND request.status IN ('SUBMITTED', 'APPROVED')
                   AND request.end_at >= CURRENT_TIMESTAMP
                   AND request.start_at < CURRENT_TIMESTAMP + INTERVAL '60 days'
                 ORDER BY request.start_at, person.display_name
                 LIMIT 200
                """, populationParameters(tenantId, scope),
                (result, ignored) -> new HrDtos.TeamAbsence(
                        result.getObject("public_id", UUID.class),
                        result.getObject("person_public_id", UUID.class),
                        result.getString("display_name"),
                        result.getString("business_title"),
                        result.getString("plan_name"),
                        instant(result.getTimestamp("start_at")),
                        instant(result.getTimestamp("end_at")),
                        result.getString("status")));
    }

    public long visibleWorkerCount(Long tenantId, PopulationScope scope) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT worker.worker_id)
                 FROM ppl_persons person
                """ + CURRENT_ASSIGNMENT + """
                 WHERE person.tenant_id = :tenantId
                   AND worker.worker_id <> :actorWorkerId
                   AND (:tenantWide OR assignment.manager_assignment_key = :managerAssignmentKey
                        OR organization.public_id IN (:organizationIds))
                """, populationParameters(tenantId, scope), Long.class);
        return count == null ? 0L : count;
    }

    /** Metrics are calculated only from rows belonging to the live target population. */
    public List<HrDtos.DomainMetric> metrics(
            Long tenantId, PopulationScope scope, String domain) {
        return switch (domain) {
            case "WORKFORCE" -> List.of(metric(
                    "activeWorkers", visibleWorkerCount(tenantId, scope), "INFO"));
            case "TIME" -> List.of(
                    metric("submitted", count(tenantId, scope, """
                            SELECT COUNT(*) FROM tme_time_cards item
                              JOIN visible_workers visible ON visible.worker_id = item.worker_id
                             WHERE item.tenant_id = :tenantId AND item.status = 'SUBMITTED'
                            """), "ATTENTION"),
                    metric("openExceptions", count(tenantId, scope, """
                            SELECT COUNT(*) FROM tme_time_exceptions exception
                              JOIN tme_time_cards card
                                ON card.tenant_id = exception.tenant_id
                               AND card.time_card_id = exception.time_card_id
                              JOIN visible_workers visible ON visible.worker_id = card.worker_id
                             WHERE exception.tenant_id = :tenantId
                               AND exception.lifecycle_state = 'OPEN'
                            """), "CRITICAL"),
                    metric("openCards", count(tenantId, scope, """
                            SELECT COUNT(*) FROM tme_time_cards item
                              JOIN visible_workers visible ON visible.worker_id = item.worker_id
                             WHERE item.tenant_id = :tenantId AND item.status = 'OPEN'
                            """), "INFO"));
            case "ABSENCE" -> List.of(
                    metric("submitted", count(tenantId, scope, """
                            SELECT COUNT(*) FROM abs_leave_requests item
                              JOIN visible_workers visible ON visible.worker_id = item.worker_id
                             WHERE item.tenant_id = :tenantId AND item.status = 'SUBMITTED'
                            """), "ATTENTION"),
                    metric("activePlans", count(tenantId, scope, """
                            SELECT COUNT(DISTINCT enrollment.leave_plan_id)
                              FROM abs_worker_plan_enrollments enrollment
                              JOIN visible_workers visible
                                ON visible.worker_id = enrollment.worker_id
                              JOIN abs_leave_plans plan
                                ON plan.tenant_id = enrollment.tenant_id
                               AND plan.leave_plan_id = enrollment.leave_plan_id
                             WHERE enrollment.tenant_id = :tenantId
                               AND enrollment.lifecycle_state = 'ACTIVE'
                               AND plan.lifecycle_state = 'ACTIVE'
                            """), "INFO"),
                    metric("activeEnrollments", count(tenantId, scope, """
                            SELECT COUNT(*) FROM abs_worker_plan_enrollments enrollment
                              JOIN visible_workers visible
                                ON visible.worker_id = enrollment.worker_id
                             WHERE enrollment.tenant_id = :tenantId
                               AND enrollment.lifecycle_state = 'ACTIVE'
                            """), "INFO"));
            case "BENEFITS" -> List.of(
                    metric("activePlans", count(tenantId, scope, """
                            SELECT COUNT(DISTINCT enrollment.benefit_plan_id)
                              FROM bnf_enrollments enrollment
                              JOIN visible_workers visible
                                ON visible.worker_id = enrollment.worker_id
                              JOIN bnf_benefit_plans plan
                                ON plan.tenant_id = enrollment.tenant_id
                               AND plan.benefit_plan_id = enrollment.benefit_plan_id
                             WHERE enrollment.tenant_id = :tenantId
                               AND enrollment.status IN ('ELECTED','ACTIVE')
                               AND plan.lifecycle_state = 'ACTIVE'
                            """), "INFO"),
                    metric("openWindows", count(tenantId, scope, """
                            SELECT COUNT(DISTINCT window.enrollment_window_id)
                              FROM bnf_enrollment_windows window
                              JOIN bnf_benefit_plans plan
                                ON plan.tenant_id = window.tenant_id
                               AND plan.benefit_program_id = window.benefit_program_id
                              JOIN bnf_enrollments enrollment
                                ON enrollment.tenant_id = plan.tenant_id
                               AND enrollment.benefit_plan_id = plan.benefit_plan_id
                              JOIN visible_workers visible
                                ON visible.worker_id = enrollment.worker_id
                             WHERE window.tenant_id = :tenantId
                               AND window.lifecycle_state = 'OPEN'
                            """), "ATTENTION"),
                    metric("activeEnrollments", count(tenantId, scope, """
                            SELECT COUNT(*) FROM bnf_enrollments enrollment
                              JOIN visible_workers visible
                                ON visible.worker_id = enrollment.worker_id
                             WHERE enrollment.tenant_id = :tenantId
                               AND enrollment.status = 'ACTIVE'
                            """), "INFO"));
            case "PAY" -> List.of(
                    metric("openCycles", count(tenantId, scope, """
                            SELECT COUNT(DISTINCT statement.pay_cycle_id)
                              FROM pay_statement_references statement
                              JOIN visible_workers visible
                                ON visible.worker_id = statement.worker_id
                              JOIN pay_pay_cycles cycle
                                ON cycle.tenant_id = statement.tenant_id
                               AND cycle.pay_cycle_id = statement.pay_cycle_id
                             WHERE statement.tenant_id = :tenantId
                               AND cycle.status NOT IN ('PAID','CANCELLED')
                            """), "ATTENTION"),
                    metric("pendingStatements", count(tenantId, scope, """
                            SELECT COUNT(*) FROM pay_statement_references statement
                              JOIN visible_workers visible
                                ON visible.worker_id = statement.worker_id
                             WHERE statement.tenant_id = :tenantId
                               AND statement.availability_state = 'PENDING'
                            """), "ATTENTION"),
                    metric("availableStatements", count(tenantId, scope, """
                            SELECT COUNT(*) FROM pay_statement_references statement
                              JOIN visible_workers visible
                                ON visible.worker_id = statement.worker_id
                             WHERE statement.tenant_id = :tenantId
                               AND statement.availability_state = 'AVAILABLE'
                            """), "INFO"));
            case "TALENT" -> List.of(
                    metric("activeJourneys", count(tenantId, scope, """
                            SELECT COUNT(*) FROM tal_journey_instances item
                              JOIN visible_workers visible ON visible.worker_id = item.worker_id
                             WHERE item.tenant_id = :tenantId AND item.status = 'ACTIVE'
                            """), "INFO"),
                    metric("atRiskGoals", count(tenantId, scope, """
                            SELECT COUNT(*) FROM tal_goals item
                              JOIN visible_workers visible ON visible.worker_id = item.worker_id
                             WHERE item.tenant_id = :tenantId AND item.status = 'AT_RISK'
                            """), "ATTENTION"),
                    metric("requiredLearning", count(tenantId, scope, """
                            SELECT COUNT(*) FROM tal_learning_assignments item
                              JOIN visible_workers visible ON visible.worker_id = item.worker_id
                             WHERE item.tenant_id = :tenantId AND item.required
                               AND item.status IN ('ASSIGNED','IN_PROGRESS')
                            """), "ATTENTION"));
            default -> throw new IllegalArgumentException("Unsupported HCM metrics domain.");
        };
    }

    private long count(Long tenantId, PopulationScope scope, String query) {
        Long count = jdbc.queryForObject(
                VISIBLE_WORKERS + query, populationParameters(tenantId, scope), Long.class);
        return count == null ? 0L : count;
    }

    private HrDtos.DomainMetric metric(String key, long value, String severity) {
        return new HrDtos.DomainMetric(key, value, severity);
    }

    private List<HrDtos.ApprovalItem> timeQueue(Long tenantId, PopulationScope scope) {
        return jdbc.query("""
                SELECT card.public_id, person.public_id AS person_public_id,
                       person.display_name, assignment.business_title,
                       card.recorded_minutes || ' minutes recorded' AS summary,
                       card.status, card.submitted_at, card.version
                  FROM tme_time_cards card
                  JOIN ppl_workers worker
                    ON worker.tenant_id = card.tenant_id AND worker.worker_id = card.worker_id
                  JOIN ppl_persons person
                    ON person.tenant_id = worker.tenant_id AND person.person_id = worker.person_id
                  JOIN LATERAL (
                      SELECT candidate.assignment_key, candidate.business_title,
                             candidate.manager_assignment_key, candidate.organization_id
                        FROM ppl_work_relationships relationship
                        JOIN ppl_assignments candidate
                          ON candidate.tenant_id = relationship.tenant_id
                         AND candidate.work_relationship_id = relationship.work_relationship_id
                       WHERE relationship.tenant_id = worker.tenant_id
                         AND relationship.worker_id = worker.worker_id
                         AND candidate.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
                         AND candidate.effective_start_date <= CURRENT_DATE
                         AND (candidate.effective_end_date IS NULL
                              OR candidate.effective_end_date >= CURRENT_DATE)
                       ORDER BY candidate.primary_assignment DESC,
                                candidate.effective_start_date DESC
                       LIMIT 1
                  ) assignment ON TRUE
                  LEFT JOIN ppl_organizations organization
                    ON organization.tenant_id = card.tenant_id
                   AND organization.organization_id = assignment.organization_id
                 WHERE card.tenant_id = :tenantId AND card.status = 'SUBMITTED'
                   AND worker.worker_id <> :actorWorkerId
                   AND (:tenantWide OR assignment.manager_assignment_key = :managerAssignmentKey
                        OR organization.public_id IN (:organizationIds))
                 ORDER BY card.submitted_at
                 LIMIT 200
                """, populationParameters(tenantId, scope),
                (result, ignored) -> approval(result, "TIME"));
    }

    private List<HrDtos.ApprovalItem> absenceQueue(Long tenantId, PopulationScope scope) {
        return jdbc.query("""
                SELECT request.public_id, person.public_id AS person_public_id,
                       person.display_name, assignment.business_title,
                       plan.name || ' · ' || request.requested_minutes || ' minutes' AS summary,
                       request.status, request.submitted_at, request.version
                  FROM abs_leave_requests request
                  JOIN abs_leave_plans plan
                    ON plan.tenant_id = request.tenant_id
                   AND plan.leave_plan_id = request.leave_plan_id
                  JOIN ppl_workers worker
                    ON worker.tenant_id = request.tenant_id AND worker.worker_id = request.worker_id
                  JOIN ppl_persons person
                    ON person.tenant_id = worker.tenant_id AND person.person_id = worker.person_id
                  JOIN LATERAL (
                      SELECT candidate.assignment_key, candidate.business_title,
                             candidate.manager_assignment_key, candidate.organization_id
                        FROM ppl_work_relationships relationship
                        JOIN ppl_assignments candidate
                          ON candidate.tenant_id = relationship.tenant_id
                         AND candidate.work_relationship_id = relationship.work_relationship_id
                       WHERE relationship.tenant_id = worker.tenant_id
                         AND relationship.worker_id = worker.worker_id
                         AND candidate.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
                         AND candidate.effective_start_date <= CURRENT_DATE
                         AND (candidate.effective_end_date IS NULL
                              OR candidate.effective_end_date >= CURRENT_DATE)
                       ORDER BY candidate.primary_assignment DESC,
                                candidate.effective_start_date DESC
                       LIMIT 1
                  ) assignment ON TRUE
                  LEFT JOIN ppl_organizations organization
                    ON organization.tenant_id = request.tenant_id
                   AND organization.organization_id = assignment.organization_id
                 WHERE request.tenant_id = :tenantId AND request.status = 'SUBMITTED'
                   AND worker.worker_id <> :actorWorkerId
                   AND (:tenantWide OR assignment.manager_assignment_key = :managerAssignmentKey
                        OR organization.public_id IN (:organizationIds))
                 ORDER BY request.submitted_at
                 LIMIT 200
                """, populationParameters(tenantId, scope),
                (result, ignored) -> approval(result, "ABSENCE"));
    }

    private HrDtos.ApprovalItem approval(java.sql.ResultSet result, String domain)
            throws java.sql.SQLException {
        return new HrDtos.ApprovalItem(
                result.getObject("public_id", UUID.class), domain,
                result.getObject("person_public_id", UUID.class),
                result.getString("display_name"), result.getString("business_title"),
                result.getString("summary"), result.getString("status"),
                instant(result.getTimestamp("submitted_at")), result.getLong("version"));
    }

    private MapSqlParameterSource populationParameters(Long tenantId, PopulationScope scope) {
        Set<UUID> organizations = scope.organizationIds().isEmpty()
                ? Set.of(EMPTY_ORGANIZATION) : scope.organizationIds();
        return parameters(tenantId)
                .addValue("actorWorkerId", scope.actorWorkerId())
                .addValue("managerAssignmentKey", scope.managerAssignmentKey())
                .addValue("tenantWide", scope.tenantWide())
                .addValue("organizationIds", organizations);
    }

    private MapSqlParameterSource parameters(Long tenantId) {
        return new MapSqlParameterSource("tenantId", tenantId);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record ActorWorkforce(
            long workerId,
            UUID personId,
            String displayName,
            String assignmentKey,
            String businessTitle,
            String organizationName,
            long workerVersion,
            long assignmentVersion,
            Long organizationVersion) {

        public String revision() {
            return workerVersion + ":" + assignmentVersion + ":"
                    + (organizationVersion == null ? "" : organizationVersion);
        }
    }

    public record PopulationScope(
            long actorWorkerId,
            String managerAssignmentKey,
            boolean tenantWide,
            Set<UUID> organizationIds,
            Set<String> fieldGroups,
            String policyFingerprint) {

        public PopulationScope {
            organizationIds = organizationIds == null
                    ? Set.of() : Set.copyOf(new LinkedHashSet<>(organizationIds));
            fieldGroups = fieldGroups == null ? Set.of() : Set.copyOf(fieldGroups);
        }

        public HrDtos.DataBoundary dataBoundary() {
            if (tenantWide) return HrDtos.DataBoundary.TENANT;
            if (!organizationIds.isEmpty() && managerAssignmentKey != null) {
                return HrDtos.DataBoundary.TEAM_AND_ORGANIZATION_SET;
            }
            return managerAssignmentKey != null
                    ? HrDtos.DataBoundary.TEAM : HrDtos.DataBoundary.ORGANIZATION_SET;
        }
    }

    public record PopulationEvidence(long count, String revision) {
    }
}
