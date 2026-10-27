package com.dwp.services.platform.hrisconfiguration.automation;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.JdbcAutomationRepositoryData.*;

/** Internal jdbc automation reconciliation operations shared by the owner facade. */
final class JdbcAutomationReconciliation {

    private final JdbcAutomationRepositoryOperations owner;
    private final JdbcTemplate jdbc;

    JdbcAutomationReconciliation(
            JdbcAutomationRepositoryOperations owner,
            JdbcTemplate jdbc) {
        this.owner = owner;
        this.jdbc = jdbc;
    }


    public LeaseGrant acquireReconcilerLease(
            RuntimeRun run,
            UUID leaseToken,
            OffsetDateTime leasedUntil,
            OffsetDateTime now) {
        if (run.workerLeaseToken() != null || run.reconcilerLeaseToken() != null
                || run.reconciliationRequiredAt() == null) {
            throw conflict("AUTOMATION_RECONCILER_LEASE_UNAVAILABLE");
        }
        long nextFence = run.reconcilerFenceEpoch() + 1;
        int attempt = jdbc.update("""
                UPDATE sys_automation_run_attempts
                   SET reconciliation_fence_epoch = ?,
                       reconciliation_lease_token = ?,
                       reconciliation_leased_until = ?,
                       reconciliation_lease_fence_epoch_snapshot = ?,
                       reconciliation_control_fence_epoch_snapshot = ?,
                       reconciliation_lease_revoked_at = NULL
                 WHERE tenant_id = ? AND run_attempt_id = ?
                   AND worker_lease_token IS NULL
                   AND reconciliation_lease_token IS NULL
                   AND reconciliation_required_at IS NOT NULL
                """, nextFence, leaseToken, leasedUntil, nextFence,
                run.controlFenceEpoch(), run.run().tenantId(), run.runAttemptId());
        int aggregate = jdbc.update("""
                UPDATE sys_automation_runs
                   SET reconciliation_fence_epoch = ?,
                       reconciliation_lease_token = ?,
                       reconciliation_leased_until = ?,
                       reconciliation_lease_fence_snapshot = ?,
                       reconciliation_control_fence_snapshot = ?,
                       reconciliation_revoked_at = NULL,
                       output_digest = NULL,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                   AND worker_lease_token IS NULL
                   AND reconciliation_lease_token IS NULL
                   AND reconciliation_required_reason IS NOT NULL
                """, nextFence, leaseToken, leasedUntil, nextFence,
                run.controlFenceEpoch(), now, run.run().tenantId(), run.run().runId(),
                run.run().objectRevision());
        if (attempt != 1 || aggregate != 1) {
            throw conflict("AUTOMATION_RECONCILER_LEASE_CAS_CONFLICT");
        }
        return new LeaseGrant(AutomationDomainPolicy.AuthorityKind.RECONCILER,
                run.run().tenantId(), run.run().publicId(), run.attemptNumber(),
                leaseToken, nextFence, run.controlFenceEpoch(), leasedUntil);
    }

    public RecoveryResult recoverAuthority(
            RuntimeRun run,
            AutomationDomainPolicy.RecoveryDecision decision,
            UUID suppliedToken,
            boolean explicitlyRevoked,
            String reason,
            OffsetDateTime now) {
        Long unresolvedEffects = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*)
                          FROM sys_automation_item_attempts a
                         WHERE a.tenant_id = ? AND a.run_id = ?
                           AND a.attempt_ordinal = ?
                           AND a.effect_state IN ('PREPARED', 'RESULT_UNKNOWN'))
                     + (SELECT COUNT(*)
                          FROM sys_automation_run_items i
                         WHERE i.tenant_id = ? AND i.run_id = ?
                           AND i.compensation_state IN
                               ('PENDING', 'RUNNING', 'RESULT_UNKNOWN'))
                """, Long.class, run.run().tenantId(), run.run().runId(),
                run.attemptNumber(), run.run().tenantId(), run.run().runId());
        boolean reconciliationRequired = (unresolvedEffects != null
                && unresolvedEffects > 0)
                || run.reconciliationRequiredAt() != null
                || run.reconciliationRequiredReason() != null
                || decision.branch()
                == AutomationDomainPolicy.RecoveryBranch.RECONCILER_LEASE;
        String attemptAssignments;
        String runAssignments;
        List<Object> attemptValues = new ArrayList<>();
        List<Object> runValues = new ArrayList<>();
        switch (decision.branch()) {
            case NO_ACTIVE_LEASE -> {
                attemptAssignments = reconciliationRequired ? """
                        reconciliation_required_at = COALESCE(
                            reconciliation_required_at, ?),
                        reconciliation_required_reason = COALESCE(
                            reconciliation_required_reason, ?)
                        """ : """
                        lifecycle_state = 'QUEUED',
                        reconciliation_required_at = NULL,
                        reconciliation_required_reason = NULL
                        """;
                runAssignments = reconciliationRequired ? """
                        control_fence_epoch = ?,
                        reconciliation_required_reason = COALESCE(
                            reconciliation_required_reason, ?), updated_at = ?
                        """ : """
                        lifecycle_state = 'QUEUED', control_fence_epoch = ?,
                        reconciliation_required_reason = NULL, updated_at = ?
                        """;
                if (reconciliationRequired) {
                    attemptValues.add(now);
                    attemptValues.add(reason);
                }
                runValues.add(decision.nextControlFence());
                if (reconciliationRequired) runValues.add(reason);
                runValues.add(now);
            }
            case WORKER_LEASE -> {
                attemptAssignments = """
                        worker_fence_epoch = ?, worker_lease_revoked_at = ?,
                        worker_lease_token = NULL, worker_leased_until = NULL,
                        worker_lease_fence_epoch_snapshot = NULL,
                        worker_control_fence_epoch_snapshot = NULL,
                        lifecycle_state = CASE WHEN ? THEN lifecycle_state
                            ELSE 'QUEUED' END,
                        reconciliation_required_at = COALESCE(
                            reconciliation_required_at,
                            CASE WHEN ? THEN ? ELSE NULL END),
                        reconciliation_required_reason = CASE WHEN ?
                            THEN COALESCE(reconciliation_required_reason, ?)
                            ELSE NULL END
                        """;
                runAssignments = """
                        control_fence_epoch = ?, worker_fence_epoch = ?,
                        worker_revoked_at = ?, worker_lease_token = NULL,
                        worker_leased_until = NULL, worker_lease_fence_snapshot = NULL,
                        worker_control_fence_snapshot = NULL,
                        lifecycle_state = CASE WHEN ? THEN lifecycle_state
                            ELSE 'QUEUED' END,
                        reconciliation_required_reason = CASE WHEN ?
                            THEN COALESCE(reconciliation_required_reason, ?)
                            ELSE NULL END, updated_at = ?
                        """;
                attemptValues.add(decision.nextWorkerFence());
                attemptValues.add(now);
                attemptValues.add(reconciliationRequired);
                attemptValues.add(reconciliationRequired);
                attemptValues.add(now);
                attemptValues.add(reconciliationRequired);
                attemptValues.add(reason);
                runValues.add(decision.nextControlFence());
                runValues.add(decision.nextWorkerFence());
                runValues.add(now);
                runValues.add(reconciliationRequired);
                runValues.add(reconciliationRequired);
                runValues.add(reason);
                runValues.add(now);
            }
            case RECONCILER_LEASE -> {
                attemptAssignments = """
                        reconciliation_fence_epoch = ?,
                        reconciliation_lease_revoked_at = ?,
                        reconciliation_lease_token = NULL,
                        reconciliation_leased_until = NULL,
                        reconciliation_lease_fence_epoch_snapshot = NULL,
                        reconciliation_control_fence_epoch_snapshot = NULL
                        """;
                runAssignments = """
                        control_fence_epoch = ?, reconciliation_fence_epoch = ?,
                        reconciliation_revoked_at = ?,
                        reconciliation_lease_token = NULL,
                        reconciliation_leased_until = NULL,
                        reconciliation_lease_fence_snapshot = NULL,
                        reconciliation_control_fence_snapshot = NULL, updated_at = ?
                        """;
                attemptValues.add(decision.nextReconcilerFence());
                attemptValues.add(now);
                runValues.add(decision.nextControlFence());
                runValues.add(decision.nextReconcilerFence());
                runValues.add(now);
                runValues.add(now);
            }
            default -> throw new IllegalStateException();
        }
        attemptValues.add(run.run().tenantId());
        attemptValues.add(run.runAttemptId());
        runValues.add(run.run().tenantId());
        runValues.add(run.run().runId());
        runValues.add(run.run().objectRevision());
        String branchPredicate = switch (decision.branch()) {
            case NO_ACTIVE_LEASE -> " AND worker_lease_token IS NULL"
                    + " AND reconciliation_lease_token IS NULL";
            case WORKER_LEASE -> " AND worker_lease_token = ?"
                    + " AND (worker_leased_until <= statement_timestamp() OR ?)";
            case RECONCILER_LEASE -> " AND reconciliation_lease_token = ?"
                    + " AND (reconciliation_leased_until <= statement_timestamp() OR ?)";
        };
        if (decision.branch() != AutomationDomainPolicy.RecoveryBranch.NO_ACTIVE_LEASE) {
            attemptValues.add(suppliedToken);
            attemptValues.add(explicitlyRevoked);
            runValues.add(suppliedToken);
            runValues.add(explicitlyRevoked);
        }
        int attempt = jdbc.update("UPDATE sys_automation_run_attempts SET "
                + attemptAssignments + " WHERE tenant_id = ? AND run_attempt_id = ?"
                + branchPredicate, attemptValues.toArray());
        int aggregate = jdbc.update("UPDATE sys_automation_runs SET "
                + runAssignments
                + ", output_digest = NULL"
                + ", object_revision = object_revision + 1"
                + " WHERE tenant_id = ? AND run_id = ? AND object_revision = ?"
                + branchPredicate, runValues.toArray());
        if (attempt != 1 || aggregate != 1) {
            throw conflict("AUTOMATION_OWNER_RECOVERY_CAS_CONFLICT");
        }
        return new RecoveryResult(
                decision.branch(), decision.nextControlFence(),
                decision.nextWorkerFence(), decision.nextReconcilerFence(),
                reconciliationRequired, false);
    }

    public RuntimeRun reconcileOriginal(
            RuntimeRun run,
            LeaseGrant reconciler,
            UUID itemPublicId,
            String effectIdempotencyKey,
            String effectRequestDigest,
            AutomationRuntimePorts.ReconciliationEvidence evidence,
            OffsetDateTime now) {
        requireReconcilerFence(run, reconciler, now);
        String effectState = evidence.outcome();
        int attempt = jdbc.update("""
                UPDATE sys_automation_item_attempts a
                   SET effect_state = ?, effect_authority_kind = 'RECONCILER',
                       run_revision_snapshot = CASE WHEN a.completed_at IS NULL
                           THEN r.object_revision ELSE a.run_revision_snapshot END,
                       effect_receipt_digest = CASE WHEN a.completed_at IS NULL
                           THEN COALESCE(?, a.effect_receipt_digest)
                           ELSE a.effect_receipt_digest END,
                       effect_resolution_authority_fence = ?,
                       effect_resolution_control_fence = ?,
                       effect_resolution_mode = 'EVIDENCE_RECONCILE',
                       effect_trusted_evidence_digest = ?,
                       effect_committed_at = CASE WHEN ? = 'COMMITTED'
                           THEN COALESCE(effect_committed_at, ?) ELSE effect_committed_at END,
                       receipt_state = CASE
                           WHEN ? = 'NO_EFFECT_CONFIRMED' AND a.completed_at IS NULL
                               THEN CASE
                                   WHEN r.cancellation_requested_at IS NOT NULL
                                       THEN 'CANCELLED'
                                   WHEN r.retry_mode <> 'NONE'
                                       AND r.current_attempt < r.max_attempts
                                       THEN 'FAILED_RETRYABLE'
                                   ELSE 'DEAD_LETTER' END
                           ELSE a.receipt_state END,
                       retryable_snapshot = CASE
                           WHEN ? = 'NO_EFFECT_CONFIRMED' AND a.completed_at IS NULL
                               THEN r.cancellation_requested_at IS NULL
                                   AND r.retry_mode <> 'NONE'
                                   AND r.current_attempt < r.max_attempts
                           ELSE a.retryable_snapshot END,
                       error_code = CASE
                           WHEN ? = 'NO_EFFECT_CONFIRMED' AND a.completed_at IS NULL
                               THEN 'EXTERNAL_NO_EFFECT_CONFIRMED'
                           ELSE a.error_code END,
                       completed_at = CASE
                           WHEN ? = 'NO_EFFECT_CONFIRMED' AND a.completed_at IS NULL
                               THEN ? ELSE a.completed_at END
                  FROM sys_automation_run_items i, sys_automation_runs r
                 WHERE a.tenant_id = ? AND a.run_id = ?
                   AND a.run_item_id = i.run_item_id
                   AND r.tenant_id = a.tenant_id AND r.run_id = a.run_id
                   AND a.attempt_ordinal = ? AND i.public_id = ?
                   AND a.effect_idempotency_key = ?
                   AND a.effect_request_digest = ?
                   AND a.effect_state IN ('PREPARED', 'RESULT_UNKNOWN')
                   AND (a.effect_resolution_mode IS NULL
                        OR a.effect_resolution_mode IN
                            ('DISPATCH_FINALIZE', 'EVIDENCE_RECONCILE'))
                """, effectState, evidence.receiptDigest(),
                reconciler.authorityFenceEpoch(), reconciler.controlFenceEpoch(),
                evidence.evidenceDigest(), effectState, now, effectState,
                effectState, effectState, effectState, now,
                run.run().tenantId(), run.run().runId(), run.attemptNumber(),
                itemPublicId, effectIdempotencyKey, effectRequestDigest);
        int projection = jdbc.update("""
                UPDATE sys_automation_run_items i
                   SET effect_state = ?,
                       effect_receipt_digest = CASE WHEN i.lifecycle_state IN
                           ('SUCCEEDED', 'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
                           THEN i.effect_receipt_digest
                           ELSE COALESCE(?, i.effect_receipt_digest) END,
                       lifecycle_state = CASE
                           WHEN ? = 'COMMITTED' AND EXISTS (
                               SELECT 1 FROM sys_automation_item_attempts terminal
                                WHERE terminal.tenant_id = i.tenant_id
                                  AND terminal.run_id = i.run_id
                                  AND terminal.run_item_id = i.run_item_id
                                  AND terminal.completed_at IS NOT NULL
                                  AND terminal.receipt_state IN
                                     ('SUCCEEDED', 'FAILED_RETRYABLE',
                                      'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER'))
                               AND i.lifecycle_state NOT IN
                                  ('SUCCEEDED', 'FAILED_FINAL', 'CANCELLED',
                                   'DEAD_LETTER') THEN 'SUCCEEDED'
                           WHEN ? <> 'NO_EFFECT_CONFIRMED'
                               OR i.lifecycle_state IN
                                  ('FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
                               THEN i.lifecycle_state
                           WHEN r.cancellation_requested_at IS NOT NULL
                               THEN 'CANCELLED'
                           WHEN r.retry_mode <> 'NONE'
                               AND r.current_attempt < r.max_attempts
                               THEN 'FAILED_RETRYABLE'
                           ELSE 'DEAD_LETTER' END,
                       error_code = CASE WHEN ? = 'NO_EFFECT_CONFIRMED'
                           THEN COALESCE(i.error_code,
                               'EXTERNAL_NO_EFFECT_CONFIRMED')
                           ELSE i.error_code END,
                       updated_at = ?
                  FROM sys_automation_runs r
                 WHERE i.tenant_id = ? AND i.run_id = ? AND i.public_id = ?
                   AND i.effect_idempotency_key = ?
                   AND i.effect_request_digest = ?
                   AND r.tenant_id = i.tenant_id AND r.run_id = i.run_id
                   AND i.effect_state IN ('PREPARED', 'RESULT_UNKNOWN')
                """, effectState, evidence.receiptDigest(), effectState, effectState,
                effectState, now, run.run().tenantId(), run.run().runId(), itemPublicId,
                effectIdempotencyKey, effectRequestDigest);
        if (attempt != 1 || projection != 1) {
            throw conflict("AUTOMATION_ORIGINAL_RECONCILIATION_CAS_CONFLICT");
        }
        if ("COMMITTED".equals(effectState)) {
            jdbc.update("""
                    UPDATE sys_automation_run_items i
                       SET compensation_state = 'PENDING', updated_at = ?
                      FROM sys_automation_runs r
                     WHERE i.tenant_id = ? AND i.run_id = ?
                       AND i.public_id = ?
                       AND r.tenant_id = i.tenant_id AND r.run_id = i.run_id
                       AND r.cancellation_requested_at IS NOT NULL
                       AND i.compensation_policy_ref IS NOT NULL
                       AND i.compensation_policy_digest IS NOT NULL
                       AND i.compensation_state = 'NOT_REQUIRED'
                       AND EXISTS (
                           SELECT 1
                             FROM sys_automation_item_attempts terminal
                            WHERE terminal.tenant_id = i.tenant_id
                              AND terminal.run_id = i.run_id
                              AND terminal.run_item_id = i.run_item_id
                              AND terminal.effect_state = 'COMMITTED'
                              AND terminal.effect_receipt_digest =
                                  i.effect_receipt_digest
                              AND terminal.completed_at IS NOT NULL
                              AND terminal.receipt_state IN
                                 ('SUCCEEDED', 'FAILED_RETRYABLE',
                                  'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER'))
                    """, now, run.run().tenantId(), run.run().runId(), itemPublicId);
        }
        return owner.lockRuntimeRun(run.run().tenantId(), run.run().publicId());
    }
}
