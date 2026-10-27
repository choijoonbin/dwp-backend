package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.JdbcAutomationRepositoryData.*;

/** Internal jdbc automation compensation operations shared by the owner facade. */
final class JdbcAutomationCompensation {

    private final JdbcAutomationRepositoryOperations owner;
    private final JdbcTemplate jdbc;
    private final AutomationCanonicalJson canonical;

    JdbcAutomationCompensation(
            JdbcAutomationRepositoryOperations owner,
            JdbcTemplate jdbc,
            AutomationCanonicalJson canonical) {
        this.owner = owner;
        this.jdbc = jdbc;
        this.canonical = canonical;
    }


    public void assertLiveCompensationAuthority(
            RuntimeRun run, LeaseGrant authority,
            PreparedCompensation compensation) {
        Boolean live = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM sys_automation_runs r
                      JOIN sys_automation_run_attempts a
                        ON a.tenant_id = r.tenant_id AND a.run_id = r.run_id
                       AND a.attempt_number = r.current_attempt
                      JOIN sys_automation_run_items i
                        ON i.tenant_id = r.tenant_id AND i.run_id = r.run_id
                      JOIN sys_automation_item_attempts ia
                        ON ia.tenant_id = i.tenant_id
                       AND ia.run_id = i.run_id
                       AND ia.run_item_id = i.run_item_id
                       AND ia.effect_receipt_digest = i.effect_receipt_digest
                     WHERE r.tenant_id = ? AND r.run_id = ? AND i.public_id = ?
                       AND r.object_revision = ? AND r.current_attempt = ?
                       AND i.item_sequence = ? AND i.subject_ref = ?
                       AND ia.accepted_at = ?
                       AND r.reconciliation_lease_token = ?
                       AND a.reconciliation_lease_token = ?
                       AND r.reconciliation_leased_until > statement_timestamp()
                       AND a.reconciliation_leased_until > statement_timestamp()
                       AND r.reconciliation_fence_epoch = ?
                       AND a.reconciliation_fence_epoch = ?
                       AND r.control_fence_epoch = ?
                       AND a.reconciliation_control_fence_epoch_snapshot = ?
                       AND r.worker_lease_token IS NULL
                       AND a.worker_lease_token IS NULL
                       AND i.effect_idempotency_key = ?
                       AND i.effect_receipt_digest = ?
                       AND ia.effect_state = 'COMMITTED'
                       AND ia.effect_idempotency_key = i.effect_idempotency_key
                       AND ia.completed_at IS NOT NULL
                       AND ia.receipt_state IN ('SUCCEEDED', 'FAILED_RETRYABLE',
                           'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
                       AND i.compensation_effect_state = 'PREPARED'
                       AND i.compensation_idempotency_key = ?
                       AND i.compensation_request_digest = ?)
                """, Boolean.class, run.run().tenantId(), run.run().runId(),
                compensation.itemPublicId(), compensation.runRevision(),
                compensation.attemptNumber(), compensation.itemSequence(),
                compensation.subjectRef(), OffsetDateTime.ofInstant(
                        compensation.acceptedAt(), ZoneOffset.UTC),
                authority.token(), authority.token(), authority.authorityFenceEpoch(),
                authority.authorityFenceEpoch(), authority.controlFenceEpoch(),
                authority.controlFenceEpoch(),
                compensation.originalEffectIdempotencyKey(),
                compensation.originalEffectReceiptDigest(),
                compensation.compensationIdempotencyKey(),
                compensation.compensationRequestDigest());
        if (!Boolean.TRUE.equals(live)) {
            throw conflict("AUTOMATION_STALE_EFFECT_AUTHORITY");
        }
    }

    public PreparedCompensation prepareCompensation(
            RuntimeRun run,
            LeaseGrant reconciler,
            UUID itemPublicId,
            String compensationKey,
            String requestDigest,
            OffsetDateTime now) {
        requireReconcilerFence(run, reconciler, now);
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT effect_idempotency_key, effect_receipt_digest,
                           compensation_policy_ref, compensation_policy_digest,
                           compensation_state, compensation_effect_state
                      FROM sys_automation_run_items
                     WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                       AND effect_state = 'COMMITTED' FOR UPDATE
                    """, run.run().tenantId(), run.run().runId(), itemPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw conflict("AUTOMATION_COMPENSATION_NOT_ELIGIBLE");
        }
        String originalKey = (String) row.get("effect_idempotency_key");
        String originalReceipt = (String) row.get("effect_receipt_digest");
        if (originalKey == null || originalReceipt == null
                || compensationKey.equals(originalKey)
                || row.get("compensation_policy_ref") == null
                || row.get("compensation_policy_digest") == null) {
            throw conflict("AUTOMATION_COMPENSATION_EVIDENCE_INVALID");
        }
        AutomationRunItemReceiptV1 original =
                loadCommittedOriginalReceipt(run, itemPublicId);
        if (!canonical.digest(original).equals(originalReceipt)) {
            throw conflict("AUTOMATION_COMPENSATION_ORIGINAL_RECEIPT_INVALID");
        }
        int updated = jdbc.update("""
                UPDATE sys_automation_run_items
                   SET compensation_state = 'RUNNING',
                       compensation_effect_state = 'PREPARED',
                       compensation_authority_kind = 'RECONCILER',
                       compensation_dispatch_reconciliation_fence = ?,
                       compensation_dispatch_control_fence = ?,
                       compensation_idempotency_key = ?,
                       compensation_request_digest = ?, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                   AND effect_state = 'COMMITTED'
                   AND compensation_state = 'PENDING'
                   AND compensation_effect_state = 'NOT_STARTED'
                """, reconciler.authorityFenceEpoch(), reconciler.controlFenceEpoch(),
                compensationKey, requestDigest, now, run.run().tenantId(),
                run.run().runId(), itemPublicId);
        if (updated != 1) throw conflict("AUTOMATION_COMPENSATION_PREPARE_CONFLICT");
        return new PreparedCompensation(invocation(run), run.manifest(),
                now.plusMinutes(1), itemPublicId, original.itemSequence(),
                original.subjectRef(), original.acceptedAt(),
                run.run().objectRevision(), run.attemptNumber(),
                compensationKey, requestDigest, originalKey, originalReceipt);
    }

    public AutomationRunItemReceiptV1 loadCommittedOriginalReceipt(
            RuntimeRun run, UUID itemPublicId) {
        try {
            return jdbc.queryForObject("""
                    SELECT i.public_id, i.item_sequence, i.subject_ref,
                           a.run_revision_snapshot, a.receipt_state,
                           a.attempt_ordinal, a.accepted_at,
                           a.completed_at, a.result_ref, a.result_digest,
                           a.error_code, a.retryable_snapshot, a.next_attempt_at,
                           a.correlation_id
                      FROM sys_automation_run_items i
                      JOIN sys_automation_item_attempts a
                        ON a.tenant_id = i.tenant_id
                       AND a.run_id = i.run_id
                       AND a.run_item_id = i.run_item_id
                     WHERE i.tenant_id = ? AND i.run_id = ? AND i.public_id = ?
                       AND i.effect_state = 'COMMITTED'
                       AND a.effect_state = 'COMMITTED'
                       AND a.effect_receipt_digest = i.effect_receipt_digest
                       AND a.completed_at IS NOT NULL
                       AND a.receipt_state IN ('SUCCEEDED', 'FAILED_RETRYABLE',
                           'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
                     ORDER BY a.attempt_ordinal DESC
                     LIMIT 1
                    """, (rs, row) -> new AutomationRunItemReceiptV1(
                            run.run().publicId(), rs.getLong("run_revision_snapshot"),
                            uuid(rs, "public_id"), rs.getLong("item_sequence"),
                            rs.getString("subject_ref"),
                            canonicalReceiptState(rs.getString("receipt_state")),
                            rs.getInt("attempt_ordinal"),
                            rs.getObject("accepted_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("completed_at", OffsetDateTime.class).toInstant(),
                            rs.getString("result_ref"), rs.getString("result_digest"),
                            rs.getString("error_code"),
                            rs.getBoolean("retryable_snapshot"),
                            rs.getObject("next_attempt_at", OffsetDateTime.class) == null
                                    ? null : rs.getObject("next_attempt_at",
                                    OffsetDateTime.class).toInstant(),
                            uuid(rs, "correlation_id")),
                    run.run().tenantId(), run.run().runId(), itemPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw conflict("AUTOMATION_COMPENSATION_ORIGINAL_RECEIPT_MISSING");
        }
    }

    AutomationRunItemReceiptV1 loadOriginalReceipt(
            RuntimeRun run, UUID itemPublicId, int attemptNumber) {
        try {
            return jdbc.queryForObject("""
                    SELECT i.public_id, i.item_sequence, i.subject_ref,
                           a.run_revision_snapshot, a.receipt_state,
                           a.attempt_ordinal, a.accepted_at,
                           a.completed_at, a.result_ref, a.result_digest,
                           a.error_code, a.retryable_snapshot, a.next_attempt_at,
                           a.correlation_id
                      FROM sys_automation_run_items i
                      JOIN sys_automation_item_attempts a
                        ON a.tenant_id = i.tenant_id
                       AND a.run_id = i.run_id
                       AND a.run_item_id = i.run_item_id
                     WHERE i.tenant_id = ? AND i.run_id = ? AND i.public_id = ?
                       AND a.attempt_ordinal = ?
                    """, (rs, row) -> new AutomationRunItemReceiptV1(
                            run.run().publicId(),
                            rs.getLong("run_revision_snapshot"),
                            uuid(rs, "public_id"), rs.getLong("item_sequence"),
                            rs.getString("subject_ref"),
                            canonicalReceiptState(rs.getString("receipt_state")),
                            rs.getInt("attempt_ordinal"),
                            rs.getObject("accepted_at", OffsetDateTime.class)
                                    .toInstant(),
                            rs.getObject("completed_at", OffsetDateTime.class)
                                    .toInstant(),
                            rs.getString("result_ref"),
                            rs.getString("result_digest"),
                            rs.getString("error_code"),
                            rs.getBoolean("retryable_snapshot"),
                            rs.getObject("next_attempt_at") == null ? null
                                    : rs.getObject("next_attempt_at",
                                    OffsetDateTime.class).toInstant(),
                            uuid(rs, "correlation_id")),
                    run.run().tenantId(), run.run().runId(), itemPublicId,
                    attemptNumber);
        } catch (EmptyResultDataAccessException missing) {
            throw conflict("AUTOMATION_ORIGINAL_RECEIPT_MISSING");
        }
    }

    public RuntimeRun finalizeCompensation(
            RuntimeRun run,
            LeaseGrant reconciler,
            PreparedCompensation prepared,
            String outcome,
            String receiptDigest,
            AutomationRuntimePorts.ReconciliationEvidence trustedEvidence,
            OffsetDateTime now) {
        requireReconcilerFence(run, reconciler, now);
        if (!List.of("COMMITTED", "RESULT_UNKNOWN").contains(outcome)
                || receiptDigest == null
                || !receiptDigest.matches("^[a-f0-9]{64}$")) {
            throw conflict("AUTOMATION_COMPENSATION_RECEIPT_INVALID");
        }
        boolean evidenceMode = trustedEvidence != null;
        String mode = evidenceMode ? "EVIDENCE_RECONCILE" : "DISPATCH_FINALIZE";
        String state = "COMMITTED".equals(outcome) ? "SUCCEEDED" : "RESULT_UNKNOWN";
        String sql = """
                UPDATE sys_automation_run_items
                   SET compensation_state = ?, compensation_effect_state = ?,
                       compensation_receipt_id = ?, compensation_receipt_digest = ?,
                       compensation_resolution_reconciliation_fence = ?,
                       compensation_resolution_control_fence = ?,
                       compensation_resolution_mode = ?,
                       compensation_trusted_evidence_digest = ?,
                       compensation_completed_at = CASE WHEN ? = 'COMMITTED'
                           THEN ? ELSE NULL END, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                   AND compensation_idempotency_key = ?
                   AND compensation_request_digest = ?
                """ + (evidenceMode
                ? " AND compensation_effect_state IN ('PREPARED', 'RESULT_UNKNOWN')"
                + " AND compensation_dispatch_reconciliation_fence <> ?"
                : " AND compensation_effect_state = 'PREPARED'"
                + " AND compensation_dispatch_reconciliation_fence = ?"
                + " AND compensation_dispatch_control_fence = ?");
        List<Object> values = new ArrayList<>(List.of(
                state, outcome, UUID.randomUUID(), receiptDigest,
                reconciler.authorityFenceEpoch(), reconciler.controlFenceEpoch(),
                mode));
        values.add(evidenceMode ? trustedEvidence.evidenceDigest() : null);
        values.add(outcome);
        values.add(now);
        values.add(now);
        values.add(run.run().tenantId());
        values.add(run.run().runId());
        values.add(prepared.itemPublicId());
        values.add(prepared.compensationIdempotencyKey());
        values.add(prepared.compensationRequestDigest());
        values.add(reconciler.authorityFenceEpoch());
        if (!evidenceMode) values.add(reconciler.controlFenceEpoch());
        int updated = jdbc.update(sql, values.toArray());
        if (updated != 1) {
            throw conflict("AUTOMATION_COMPENSATION_FINALIZE_CAS_CONFLICT");
        }
        if ("RESULT_UNKNOWN".equals(outcome)) {
            jdbc.update("""
                    UPDATE sys_automation_run_attempts
                       SET reconciliation_required_at = ?,
                           reconciliation_required_reason =
                               'RESULT_UNKNOWN_EVIDENCE_REQUIRED'
                     WHERE tenant_id = ? AND run_attempt_id = ?
                    """, now, run.run().tenantId(), run.runAttemptId());
        }
        return owner.lockRuntimeRun(run.run().tenantId(), run.run().publicId());
    }

    public RuntimeRun appendCheckpointAndRecalculate(
            RuntimeRun run,
            LeaseGrant authority,
            String processedItemDigest,
            String trustedReceiptDigest,
            OffsetDateTime now) {
        if (authority.authorityKind() == AutomationDomainPolicy.AuthorityKind.WORKER) {
            requireWorkerFence(run, authority, now);
        } else if (authority.authorityKind()
                == AutomationDomainPolicy.AuthorityKind.RECONCILER) {
            requireReconcilerFence(run, authority, now);
            if (trustedReceiptDigest == null) {
                throw conflict("AUTOMATION_RECOVERY_CHECKPOINT_EVIDENCE_REQUIRED");
            }
        } else {
            throw conflict("AUTOMATION_CHECKPOINT_AUTHORITY_REJECTED");
        }
        Long prior = jdbc.queryForObject("""
                SELECT GREATEST(a.resume_after_checkpoint_ordinal,
                           COALESCE(MAX(c.checkpoint_ordinal), 0))
                  FROM sys_automation_run_attempts a
                  LEFT JOIN sys_automation_run_checkpoints c
                    ON c.tenant_id = a.tenant_id
                   AND c.run_attempt_id = a.run_attempt_id
                 WHERE a.tenant_id = ? AND a.run_attempt_id = ?
                 GROUP BY a.resume_after_checkpoint_ordinal
                """, Long.class, run.run().tenantId(), run.runAttemptId());
        long ordinal = (prior == null ? 0 : prior) + 1;
        Long processed = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_item_attempts
                 WHERE tenant_id = ? AND run_id = ? AND attempt_ordinal = ?
                   AND completed_at IS NOT NULL
                """, Long.class, run.run().tenantId(), run.run().runId(),
                run.attemptNumber());
        if (processedItemDigest == null
                || !processedItemDigest.matches("^[a-f0-9]{64}$")
                || (trustedReceiptDigest != null
                && !trustedReceiptDigest.matches("^[a-f0-9]{64}$"))) {
            throw conflict("AUTOMATION_CHECKPOINT_DIGEST_INVALID");
        }
        if (authority.authorityKind() == AutomationDomainPolicy.AuthorityKind.WORKER) {
            String canonicalProcessedItemDigest = owner.currentAttemptReceiptSet(
                    run.run().tenantId(), run.run().runId(), run.run().publicId(),
                    run.attemptNumber(), run.run().objectRevision()).digest();
            if (!processedItemDigest.equals(canonicalProcessedItemDigest)) {
                throw conflict("AUTOMATION_CHECKPOINT_PROCESSED_DIGEST_MISMATCH");
            }
        }
        ReceiptSet receiptSet = owner.currentReceiptSet(
                run.run().tenantId(), run.run().runId(), run.run().publicId(),
                run.run().objectRevision() + 1);
        jdbc.update("""
                INSERT INTO sys_automation_run_checkpoints (
                    tenant_id, run_id, run_attempt_id, attempt_number,
                    checkpoint_ordinal, processed_item_count,
                    processed_item_digest, output_digest, writer_authority_kind,
                    authority_fence_epoch, control_fence_epoch,
                    trusted_effect_receipt_digest, committed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.run().tenantId(), run.run().runId(), run.runAttemptId(),
                run.attemptNumber(), ordinal, processed == null ? 0 : processed,
                processedItemDigest, receiptSet.digest(),
                authority.authorityKind().name(), authority.authorityFenceEpoch(),
                authority.controlFenceEpoch(), trustedReceiptDigest, now);
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE lifecycle_state = 'SUCCEEDED') AS succeeded,
                       COUNT(*) FILTER (WHERE lifecycle_state IN
                           ('FAILED_RETRYABLE', 'FAILED_FINAL', 'DEAD_LETTER')) AS failed,
                       COUNT(*) FILTER (WHERE lifecycle_state = 'FAILED_RETRYABLE')
                           AS retryable,
                       COUNT(*) FILTER (WHERE effect_state IN
                           ('PREPARED', 'RESULT_UNKNOWN')) AS uncertain,
                       COUNT(*) FILTER (WHERE compensation_state IN
                           ('PENDING', 'RUNNING', 'RESULT_UNKNOWN')) AS compensation_pending
                  FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                """, run.run().tenantId(), run.run().runId());
        long total = ((Number) counts.get("total")).longValue();
        long succeeded = ((Number) counts.get("succeeded")).longValue();
        long failed = ((Number) counts.get("failed")).longValue();
        long retryable = ((Number) counts.get("retryable")).longValue();
        long uncertain = ((Number) counts.get("uncertain")).longValue();
        long compensationPending = ((Number) counts.get("compensation_pending")).longValue();
        Boolean cancellationRequested = jdbc.queryForObject("""
                SELECT cancellation_requested_at IS NOT NULL
                  FROM sys_automation_runs
                 WHERE tenant_id = ? AND run_id = ?
                """, Boolean.class, run.run().tenantId(), run.run().runId());
        RunState state;
        if (receiptSet.itemCount() != total) {
            throw conflict("AUTOMATION_RECEIPT_SET_CARDINALITY_MISMATCH");
        }
        if (uncertain > 0 || compensationPending > 0) {
            state = RunState.RUNNING;
        } else if (Boolean.TRUE.equals(cancellationRequested)) {
            state = RunState.CANCELLED;
        } else if (succeeded + failed < total) {
            state = RunState.QUEUED;
        } else if (total == succeeded) {
            state = RunState.SUCCEEDED;
        } else if (retryable > 0 && run.attemptNumber() < run.run().maxAttempts()) {
            state = succeeded > 0 ? RunState.PARTIAL : RunState.FAILED;
        } else {
            state = RunState.DEAD_LETTER;
        }
        boolean finalState = List.of(
                RunState.SUCCEEDED, RunState.CANCELLED, RunState.DEAD_LETTER)
                .contains(state);
        boolean reconciliationRequired = uncertain > 0 || compensationPending > 0;
        if (finalState && receiptSet.terminalCount() != total) {
            throw conflict("AUTOMATION_TERMINAL_RECEIPT_SET_INCOMPLETE");
        }
        int aggregate = jdbc.update("""
                UPDATE sys_automation_runs
                   SET lifecycle_state = ?, item_total = ?, item_succeeded = ?,
                       item_failed = ?, output_digest = ?,
                       object_revision = object_revision + 1,
                       completed_at = CASE WHEN ? THEN ? ELSE NULL END,
                       worker_lease_token = NULL, worker_leased_until = NULL,
                       worker_lease_fence_snapshot = NULL,
                       worker_control_fence_snapshot = NULL,
                       reconciliation_lease_token = NULL,
                       reconciliation_leased_until = NULL,
                       reconciliation_lease_fence_snapshot = NULL,
                       reconciliation_control_fence_snapshot = NULL,
                       reconciliation_required_reason = CASE WHEN ?
                           THEN reconciliation_required_reason ELSE NULL END,
                       updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                """, state.name(), total, succeeded, failed, receiptSet.digest(),
                finalState, now, reconciliationRequired, now,
                run.run().tenantId(), run.run().runId(),
                run.run().objectRevision());
        if (aggregate != 1) throw conflict("AUTOMATION_RUN_CHECKPOINT_CAS_CONFLICT");
        jdbc.update("""
                UPDATE sys_automation_run_attempts
                   SET lifecycle_state = ?, completed_at = CASE WHEN ? <> 'RUNNING'
                           THEN ? ELSE completed_at END,
                       worker_lease_token = NULL, worker_leased_until = NULL,
                       worker_lease_fence_epoch_snapshot = NULL,
                       worker_control_fence_epoch_snapshot = NULL,
                       reconciliation_lease_token = NULL,
                       reconciliation_leased_until = NULL,
                       reconciliation_lease_fence_epoch_snapshot = NULL,
                       reconciliation_control_fence_epoch_snapshot = NULL,
                       reconciliation_required_at = CASE WHEN ?
                           THEN reconciliation_required_at ELSE NULL END,
                       reconciliation_required_reason = CASE WHEN ?
                           THEN reconciliation_required_reason ELSE NULL END
                 WHERE tenant_id = ? AND run_attempt_id = ?
                """, state.name(), state.name(), now,
                reconciliationRequired, reconciliationRequired,
                run.run().tenantId(), run.runAttemptId());
        return owner.lockRuntimeRun(run.run().tenantId(), run.run().publicId());
    }
}
