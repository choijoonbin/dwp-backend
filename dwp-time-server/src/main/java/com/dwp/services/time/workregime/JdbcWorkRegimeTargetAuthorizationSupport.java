package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeRepository.CommandEvidence;
import com.dwp.services.time.workregime.WorkRegimeRepository.DraftWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetAuthorizationGuard;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetBindingEvidence;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Performs owner-side target-population checks inside the same transaction as every read or
 * mutation. The gateway scope key is only an actor entitlement: persisted plans are bound to the
 * stable population and its concrete People assignment membership.
 */
final class JdbcWorkRegimeTargetAuthorizationSupport {

    private final JdbcTemplate jdbc;

    JdbcWorkRegimeTargetAuthorizationSupport(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    void lockActor(TargetAuthorizationGuard guard) {
        Objects.requireNonNull(guard, "guard must not be null");
        Integer count = exactlyOne(jdbc.query("""
                SELECT 1
                  FROM tim_target_population_projections p
                  JOIN tim_target_population_actor_grants g
                    ON g.tenant_id = p.tenant_id
                   AND g.population_public_id = p.population_public_id
                   AND g.population_revision = p.projection_revision
                 WHERE p.tenant_id = ?
                   AND p.population_public_id = ?
                   AND p.scope_public_ref = ?
                   AND p.projection_revision = ?
                   AND p.source_digest = ?
                   AND p.lifecycle_state = 'ACTIVE'
                   AND p.effective_from <= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date
                   AND (p.effective_to IS NULL
                        OR p.effective_to > (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date)
                   AND g.actor_id = ?
                   AND g.gateway_scope_key = ?
                   AND g.grant_revision = ?
                   AND g.source_digest = ?
                   AND g.lifecycle_state = 'ACTIVE'
                   AND g.valid_from <= CURRENT_TIMESTAMP
                   AND (g.valid_to IS NULL OR g.valid_to > CURRENT_TIMESTAMP)
                 FOR SHARE OF p, g
                """, (row, number) -> row.getInt(1),
                guard.tenantId(), guard.populationPublicId(), guard.scopePublicRef(),
                guard.populationRevision(), guard.populationDigest(), guard.actorId(),
                guard.gatewayScopeKey(), guard.grantRevision(), guard.grantDigest()),
                "current target-population actor entitlement");
        if (count != 1) {
            throw new IllegalStateException("Target-population actor entitlement is unavailable");
        }
    }

    Optional<LockedTarget> tryLockPlanAccess(
            TargetAuthorizationGuard guard, UUID workPlanPublicId) {
        Objects.requireNonNull(guard, "guard must not be null");
        Objects.requireNonNull(workPlanPublicId, "workPlanPublicId must not be null");
        List<LockedTarget> rows = jdbc.query(existingTargetSql(false),
                JdbcWorkRegimeTargetAuthorizationSupport::mapLockedTarget,
                guard.tenantId(), workPlanPublicId,
                guard.populationPublicId(), guard.scopePublicRef(),
                guard.populationRevision(), guard.populationDigest(),
                guard.actorId(), guard.gatewayScopeKey(), guard.grantRevision(),
                guard.grantDigest());
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Work plan resolves to multiple target assignments");
        }
        return rows.stream().findFirst();
    }

    LockedTarget authorizeCreate(DraftWrite draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        CommandEvidence command = draft.commandEvidence();
        TargetAuthorizationGuard guard = command.targetAuthorization();
        TargetBindingEvidence target = draft.targetBindingEvidence();
        if (command.operation() != LifecycleAction.CREATE_DRAFT
                || !command.aggregateId().equals(draft.publicId())
                || command.expectedVersion() != null
                || draft.scopeType() != WorkRegimeModels.ScopeType.POPULATION
                || !draft.scopeRef().equals(guard.scopePublicRef())) {
            throw new IllegalArgumentException("create command target evidence is inconsistent");
        }
        List<LockedTarget> rows = jdbc.query("""
                SELECT p.population_public_id,
                       p.projection_revision AS population_revision,
                       p.source_digest AS population_digest,
                       m.worker_public_id,
                       m.people_assignment_public_id,
                       m.people_assignment_revision,
                       m.membership_revision,
                       m.source_digest AS membership_digest,
                       g.actor_id AS author_actor_id,
                       g.gateway_scope_key AS author_gateway_scope_key,
                       g.grant_revision AS author_grant_revision,
                       g.source_digest AS grant_digest,
                       CURRENT_TIMESTAMP AS verified_at,
                       m.effective_from AS member_effective_from,
                       m.effective_to AS member_effective_to
                  FROM tim_target_population_projections p
                  JOIN tim_target_population_actor_grants g
                    ON g.tenant_id = p.tenant_id
                   AND g.population_public_id = p.population_public_id
                   AND g.population_revision = p.projection_revision
                  JOIN tim_target_population_members m
                    ON m.tenant_id = p.tenant_id
                   AND m.population_public_id = p.population_public_id
                   AND m.population_revision = p.projection_revision
                  JOIN tim_command_receipts r
                    ON r.tenant_id = p.tenant_id
                   AND r.public_id = ?
                 WHERE p.tenant_id = ?
                   AND p.population_public_id = ?
                   AND p.scope_public_ref = ?
                   AND p.projection_revision = ?
                   AND p.source_digest = ?
                   AND p.lifecycle_state = 'ACTIVE'
                   AND p.effective_from <= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date
                   AND (p.effective_to IS NULL
                        OR p.effective_to > (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date)
                   AND p.effective_from <= ?
                   AND (p.effective_to IS NULL OR (? IS NOT NULL AND p.effective_to >= ?))
                   AND g.actor_id = ?
                   AND g.gateway_scope_key = ?
                   AND g.grant_revision = ?
                   AND g.source_digest = ?
                   AND g.lifecycle_state = 'ACTIVE'
                   AND g.valid_from <= CURRENT_TIMESTAMP
                   AND (g.valid_to IS NULL OR g.valid_to > CURRENT_TIMESTAMP)
                   AND m.worker_public_id = ?
                   AND m.people_assignment_public_id = ?
                   AND m.people_assignment_revision = ?
                   AND m.membership_revision = ?
                   AND m.source_digest = ?
                   AND m.lifecycle_state = 'ACTIVE'
                   AND m.effective_from <= ?
                   AND (m.effective_to IS NULL OR (? IS NOT NULL AND m.effective_to >= ?))
                   AND r.idempotency_key = ?
                   AND r.operation = 'CREATE_DRAFT'
                   AND r.aggregate_public_id = ?
                   AND r.scope_public_ref = p.scope_public_ref
                   AND r.expected_version IS NULL
                   AND r.request_digest = ?
                   AND r.actor_id = g.actor_id
                   AND r.purpose_code = ?
                   AND r.authorization_decision_id = ?
                   AND r.correlation_id = ?
                   AND r.lifecycle_state = 'RUNNING'
                 FOR SHARE OF p, g, m, r
                """, JdbcWorkRegimeTargetAuthorizationSupport::mapLockedTarget,
                command.receiptId(), draft.tenantId(), guard.populationPublicId(),
                guard.scopePublicRef(), guard.populationRevision(), guard.populationDigest(),
                draft.period().from(), draft.period().to(), draft.period().to(),
                guard.actorId(), guard.gatewayScopeKey(), guard.grantRevision(),
                guard.grantDigest(), draft.workerPublicId(), draft.peopleAssignmentPublicId(),
                draft.peopleAssignmentRevision(), target.membershipRevision(),
                target.membershipDigest(), draft.period().from(), draft.period().to(),
                draft.period().to(), command.idempotencyKey(), draft.publicId(),
                command.requestDigest(), command.purpose(), command.decisionId(),
                command.correlationId());
        LockedTarget locked = exactlyOne(rows, "create target-population binding");
        requireSameBinding(target, locked.binding());
        return locked;
    }

    LockedTarget authorizeExisting(
            CommandEvidence command,
            UUID workPlanPublicId,
            long expectedVersion,
            PolicyState expectedState) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(expectedState, "expectedState must not be null");
        TargetAuthorizationGuard guard = command.targetAuthorization();
        if (!command.aggregateId().equals(workPlanPublicId)
                || !Objects.equals(command.expectedVersion(), expectedVersion)) {
            throw new IllegalArgumentException("command aggregate or version is inconsistent");
        }
        List<LockedTarget> rows = jdbc.query(existingTargetSql(true),
                JdbcWorkRegimeTargetAuthorizationSupport::mapLockedTarget,
                guard.tenantId(), workPlanPublicId,
                guard.populationPublicId(), guard.scopePublicRef(),
                guard.populationRevision(), guard.populationDigest(),
                guard.actorId(), guard.gatewayScopeKey(), guard.grantRevision(),
                guard.grantDigest(), command.receiptId(), command.idempotencyKey(),
                command.operation().name(), expectedVersion, command.requestDigest(),
                command.purpose(), command.decisionId(), command.correlationId(),
                expectedState.name(), expectedVersion);
        return exactlyOne(rows, "authorized work-plan mutation target");
    }

    void authorizeSimulationResultAssignments(
            TargetAuthorizationGuard guard,
            LockedTarget target,
            EffectivePeriod period,
            WorkRegimeModels.SimulationResult result) {
        Set<UUID> assignmentIds = new LinkedHashSet<>();
        result.segments().forEach(segment -> assignmentIds.add(segment.assignmentId()));
        result.differences().forEach(difference -> assignmentIds.add(difference.assignmentId()));
        if (assignmentIds.isEmpty()) return;
        for (UUID assignmentId : assignmentIds) {
            List<UUID> authorized = jdbc.query("""
                SELECT a.public_id
                  FROM tim_work_plan_assignments a
                  JOIN tim_work_regime_versions wr
                    ON wr.tenant_id = a.tenant_id
                   AND wr.work_regime_version_id = a.work_regime_version_id
                   AND wr.policy_revision = a.policy_revision
                  JOIN tim_work_plan_target_evidence te
                    ON te.tenant_id = a.tenant_id
                   AND te.work_plan_assignment_id = a.work_plan_assignment_id
                  JOIN tim_target_population_projections p
                    ON p.tenant_id = te.tenant_id
                   AND p.population_public_id = te.population_public_id
                   AND p.projection_revision = te.population_revision
                   AND p.source_digest = te.population_digest
                  JOIN tim_target_population_members m
                    ON m.tenant_id = a.tenant_id
                   AND m.population_public_id = p.population_public_id
                   AND m.population_revision = p.projection_revision
                   AND m.worker_public_id = a.worker_public_id
                   AND m.people_assignment_public_id = a.people_assignment_public_id
                   AND m.people_assignment_revision = a.people_assignment_revision
                   AND m.membership_revision = te.membership_revision
                   AND m.source_digest = te.membership_digest
                 WHERE a.tenant_id = ?
                   AND p.population_public_id = ?
                   AND p.projection_revision = ?
                   AND p.source_digest = ?
                   AND p.lifecycle_state = 'ACTIVE'
                   AND m.lifecycle_state = 'ACTIVE'
                   AND a.public_id = ?
                   AND a.lifecycle_state IN ('DRAFT', 'PUBLISHED')
                   AND wr.lifecycle_state IN ('VALIDATED', 'PUBLISHED')
                   AND a.effective_from < ?
                   AND (a.effective_to IS NULL OR a.effective_to > ?)
                   AND wr.effective_from < ?
                   AND (wr.effective_to IS NULL OR wr.effective_to > ?)
                   AND m.effective_from <= ?
                   AND (m.effective_to IS NULL OR m.effective_to >= ?)
                 FOR SHARE OF a, wr, p, m
                    """, (row, number) -> row.getObject(1, UUID.class),
                    guard.tenantId(), target.binding().populationPublicId(),
                    target.binding().populationRevision(), target.binding().populationDigest(),
                    assignmentId,
                    period.to(), period.from(), period.to(), period.from(),
                    period.from(), period.to());
            if (authorized.size() != 1 || !authorized.getFirst().equals(assignmentId)) {
                throw new IllegalStateException(
                        "Simulation result references an assignment outside the target population");
            }
        }
    }

    void appendCommandEvidence(
            CommandEvidence command, UUID workPlanPublicId, LockedTarget target) {
        TargetAuthorizationGuard guard = command.targetAuthorization();
        TargetBindingEvidence binding = target.binding();
        int inserted = jdbc.update("""
                INSERT INTO tim_work_regime_command_authority_evidence (
                    tenant_id, receipt_public_id, work_regime_public_id,
                    actor_id, gateway_scope_key,
                    population_public_id, population_revision, grant_revision,
                    population_digest, grant_digest,
                    worker_public_id, people_assignment_public_id,
                    people_assignment_revision, membership_revision,
                    membership_digest, member_effective_from, member_effective_to,
                    verified_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, receipt_public_id) DO NOTHING
                """, guard.tenantId(), command.receiptId(), workPlanPublicId,
                guard.actorId(), guard.gatewayScopeKey(), guard.populationPublicId(),
                guard.populationRevision(), guard.grantRevision(), guard.populationDigest(),
                guard.grantDigest(), binding.workerPublicId(),
                binding.peopleAssignmentPublicId(), binding.peopleAssignmentRevision(),
                binding.membershipRevision(), binding.membershipDigest(),
                target.memberPeriod().from(), target.memberPeriod().to());
        if (inserted == 0) {
            Integer exact = jdbc.queryForObject("""
                    SELECT count(*)
                      FROM tim_work_regime_command_authority_evidence
                     WHERE tenant_id = ? AND receipt_public_id = ?
                       AND work_regime_public_id = ? AND actor_id = ?
                       AND gateway_scope_key = ? AND population_public_id = ?
                       AND population_revision = ? AND grant_revision = ?
                       AND population_digest = ? AND grant_digest = ?
                       AND worker_public_id = ? AND people_assignment_public_id = ?
                       AND people_assignment_revision = ? AND membership_revision = ?
                       AND membership_digest = ?
                    """, Integer.class,
                    guard.tenantId(), command.receiptId(), workPlanPublicId,
                    guard.actorId(), guard.gatewayScopeKey(), guard.populationPublicId(),
                    guard.populationRevision(), guard.grantRevision(), guard.populationDigest(),
                    guard.grantDigest(), binding.workerPublicId(),
                    binding.peopleAssignmentPublicId(), binding.peopleAssignmentRevision(),
                    binding.membershipRevision(), binding.membershipDigest());
            if (exact == null || exact != 1) {
                throw new IllegalStateException(
                        "Command target-population authority evidence conflicts");
            }
        }
    }

    private static String existingTargetSql(boolean withReceipt) {
        return """
                SELECT te.population_public_id,
                       te.population_revision,
                       te.population_digest,
                       a.worker_public_id,
                       a.people_assignment_public_id,
                       a.people_assignment_revision,
                       te.membership_revision,
                       te.membership_digest,
                       te.author_actor_id,
                       te.author_gateway_scope_key,
                       te.author_grant_revision,
                       te.grant_digest,
                       te.verified_at,
                       m.effective_from AS member_effective_from,
                       m.effective_to AS member_effective_to
                  FROM tim_work_regime_versions wr
                  JOIN tim_work_plan_assignments a
                    ON a.tenant_id = wr.tenant_id
                   AND a.work_regime_version_id = wr.work_regime_version_id
                  JOIN tim_work_plan_target_evidence te
                    ON te.tenant_id = a.tenant_id
                   AND te.work_plan_assignment_id = a.work_plan_assignment_id
                  JOIN tim_target_population_projections p
                    ON p.tenant_id = te.tenant_id
                   AND p.population_public_id = te.population_public_id
                   AND p.projection_revision = te.population_revision
                   AND p.source_digest = te.population_digest
                  JOIN tim_target_population_members m
                    ON m.tenant_id = a.tenant_id
                   AND m.population_public_id = p.population_public_id
                   AND m.population_revision = p.projection_revision
                   AND m.worker_public_id = a.worker_public_id
                   AND m.people_assignment_public_id = a.people_assignment_public_id
                   AND m.people_assignment_revision = a.people_assignment_revision
                   AND m.membership_revision = te.membership_revision
                   AND m.source_digest = te.membership_digest
                  JOIN tim_target_population_actor_grants g
                    ON g.tenant_id = p.tenant_id
                   AND g.population_public_id = p.population_public_id
                   AND g.population_revision = p.projection_revision
                 %s
                 WHERE wr.tenant_id = ?
                   AND wr.public_id = ?
                   AND wr.scope_type = 'POPULATION'
                   AND wr.scope_public_ref = p.scope_public_ref
                   AND wr.author_actor_id = te.author_actor_id
                   AND p.population_public_id = ?
                   AND p.scope_public_ref = ?
                   AND p.projection_revision = ?
                   AND p.source_digest = ?
                   AND p.lifecycle_state = 'ACTIVE'
                   AND p.effective_from <= (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date
                   AND (p.effective_to IS NULL
                        OR p.effective_to > (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date)
                   AND p.effective_from <= a.effective_from
                   AND (p.effective_to IS NULL
                        OR (a.effective_to IS NOT NULL AND p.effective_to >= a.effective_to))
                   AND g.actor_id = ?
                   AND g.gateway_scope_key = ?
                   AND g.grant_revision = ?
                   AND g.source_digest = ?
                   AND g.lifecycle_state = 'ACTIVE'
                   AND g.valid_from <= CURRENT_TIMESTAMP
                   AND (g.valid_to IS NULL OR g.valid_to > CURRENT_TIMESTAMP)
                   AND m.lifecycle_state = 'ACTIVE'
                   AND m.effective_from <= a.effective_from
                   AND (m.effective_to IS NULL
                        OR (a.effective_to IS NOT NULL AND m.effective_to >= a.effective_to))
                 %s
                 FOR SHARE OF wr, a, p, g, m %s
                """.formatted(
                withReceipt
                        ? "JOIN tim_command_receipts r ON r.tenant_id = wr.tenant_id"
                        : "",
                withReceipt
                        ? """
                          AND r.public_id = ?
                          AND r.idempotency_key = ?
                          AND r.operation = ?
                          AND r.aggregate_public_id = wr.public_id
                          AND r.scope_public_ref = p.scope_public_ref
                          AND r.expected_version = ?
                          AND r.request_digest = ?
                          AND r.actor_id = g.actor_id
                          AND r.purpose_code = ?
                          AND r.authorization_decision_id = ?
                          AND r.correlation_id = ?
                          AND r.lifecycle_state = 'RUNNING'
                          AND wr.lifecycle_state = ?
                          AND wr.version = ?
                          """
                        : "",
                withReceipt ? ", r" : "");
    }

    private static LockedTarget mapLockedTarget(ResultSet row, int number) throws SQLException {
        TargetBindingEvidence binding = new TargetBindingEvidence(
                row.getObject("population_public_id", UUID.class),
                row.getLong("population_revision"),
                row.getObject("worker_public_id", UUID.class),
                row.getObject("people_assignment_public_id", UUID.class),
                row.getLong("people_assignment_revision"),
                row.getLong("membership_revision"),
                row.getLong("author_actor_id"),
                row.getString("author_gateway_scope_key"),
                row.getLong("author_grant_revision"),
                row.getString("population_digest").trim(),
                row.getString("membership_digest").trim(),
                row.getString("grant_digest").trim(),
                row.getTimestamp("verified_at").toInstant());
        return new LockedTarget(
                binding,
                new EffectivePeriod(
                        row.getObject("member_effective_from", LocalDate.class),
                        row.getObject("member_effective_to", LocalDate.class)));
    }

    private static void requireSameBinding(
            TargetBindingEvidence expected, TargetBindingEvidence actual) {
        boolean exact = expected.populationPublicId().equals(actual.populationPublicId())
                && expected.populationRevision() == actual.populationRevision()
                && expected.workerPublicId().equals(actual.workerPublicId())
                && expected.peopleAssignmentPublicId().equals(actual.peopleAssignmentPublicId())
                && expected.peopleAssignmentRevision() == actual.peopleAssignmentRevision()
                && expected.membershipRevision() == actual.membershipRevision()
                && expected.authorActorId() == actual.authorActorId()
                && expected.authorGatewayScopeKey().equals(actual.authorGatewayScopeKey())
                && expected.authorGrantRevision() == actual.authorGrantRevision()
                && expected.populationDigest().equals(actual.populationDigest())
                && expected.membershipDigest().equals(actual.membershipDigest())
                && expected.grantDigest().equals(actual.grantDigest());
        if (!exact) {
            throw new IllegalStateException(
                    "Resolved create target binding differs from supplied evidence");
        }
    }

    private static <T> T exactlyOne(List<T> rows, String label) {
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one " + label + "; found " + rows.size());
        }
        return rows.getFirst();
    }

    record LockedTarget(TargetBindingEvidence binding, EffectivePeriod memberPeriod) {
        LockedTarget {
            Objects.requireNonNull(binding, "binding must not be null");
            Objects.requireNonNull(memberPeriod, "memberPeriod must not be null");
        }
    }
}
