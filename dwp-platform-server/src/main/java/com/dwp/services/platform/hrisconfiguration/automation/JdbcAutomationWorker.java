package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.JdbcAutomationRepositoryData.*;

/** Internal jdbc automation worker operations shared by the owner facade. */
final class JdbcAutomationWorker {

    private final JdbcAutomationRepositoryOperations owner;
    private final JdbcTemplate jdbc;
    private final AutomationCanonicalJson canonical;

    JdbcAutomationWorker(
            JdbcAutomationRepositoryOperations owner,
            JdbcTemplate jdbc,
            AutomationCanonicalJson canonical) {
        this.owner = owner;
        this.jdbc = jdbc;
        this.canonical = canonical;
    }


    public RuntimeRun lockRuntimeRun(long tenantId, UUID runPublicId) {
        try {
            return jdbc.queryForObject("""
                    SELECT r.*, d.public_id AS definition_public_id,
                           a.run_attempt_id, a.attempt_number,
                           a.selected_item_digest, a.selected_item_count,
                           a.worker_fence_epoch AS a_worker_fence_epoch,
                           a.worker_lease_token AS a_worker_lease_token,
                           a.worker_leased_until AS a_worker_leased_until,
                           a.worker_control_fence_epoch_snapshot AS a_worker_control,
                           a.reconciliation_fence_epoch AS a_recon_fence_epoch,
                           a.reconciliation_lease_token AS a_recon_lease_token,
                           a.reconciliation_leased_until AS a_recon_leased_until,
                           a.reconciliation_control_fence_epoch_snapshot AS a_recon_control,
                           a.reconciliation_required_at AS a_recon_required_at,
                           a.reconciliation_required_reason AS a_recon_reason,
                           v.definition_digest, v.execution_policy_digest,
                           v.compatibility_baseline_digest, v.compensation_policy_ref,
                           v.compensation_policy_digest, v.handler_owner_service,
                           v.handler_key, v.handler_contract_version,
                           v.handler_signature_digest, v.allowed_scope_types,
                           v.manifest_retry_mode, v.manifest_max_attempts,
                           v.handler_manifest_state, v.handler_signature_key_id,
                           v.input_schema_ref, v.input_schema_digest,
                           v.receipt_schema_ref, v.receipt_schema_digest,
                           v.handler_manifest_revision
                      FROM sys_automation_runs r
                      JOIN sys_automation_definitions d
                        ON d.tenant_id = r.tenant_id
                       AND d.definition_id = r.definition_id
                      JOIN sys_automation_versions v
                        ON v.tenant_id = r.tenant_id
                       AND v.definition_id = r.definition_id
                       AND v.version_id = r.version_id
                       AND v.version_number = r.version_number
                      JOIN sys_automation_run_attempts a
                        ON a.tenant_id = r.tenant_id AND a.run_id = r.run_id
                       AND a.attempt_number = r.current_attempt
                     WHERE r.tenant_id = ? AND r.public_id = ?
                     FOR UPDATE OF r, a
                    """, (rs, row) -> owner.runtimeRun(rs), tenantId, runPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("AUTOMATION_RUN_NOT_FOUND");
        }
    }

    public void assertLiveOriginalAuthority(
            RuntimeRun run, LeaseGrant authority, PreparedItem item) {
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
                       AND ia.attempt_ordinal = a.attempt_number
                     WHERE r.tenant_id = ? AND r.run_id = ? AND i.public_id = ?
                       AND r.object_revision = ? AND r.current_attempt = ?
                       AND i.item_sequence = ? AND i.subject_ref = ?
                       AND ia.run_revision_snapshot = r.object_revision
                       AND ia.accepted_at = ? AND ia.correlation_id = r.correlation_id
                       AND r.worker_lease_token = ? AND a.worker_lease_token = ?
                       AND r.worker_leased_until > statement_timestamp()
                       AND a.worker_leased_until > statement_timestamp()
                       AND r.worker_fence_epoch = ? AND a.worker_fence_epoch = ?
                       AND r.control_fence_epoch = ?
                       AND a.worker_control_fence_epoch_snapshot = ?
                       AND r.reconciliation_lease_token IS NULL
                       AND a.reconciliation_lease_token IS NULL
                       AND ia.effect_state = 'PREPARED'
                       AND ia.effect_idempotency_key = ?
                       AND ia.effect_request_digest = ?)
                """, Boolean.class, run.run().tenantId(), run.run().runId(),
                item.itemPublicId(), run.run().objectRevision(), run.attemptNumber(),
                item.itemSequence(), item.subjectRef(),
                OffsetDateTime.ofInstant(item.acceptedAt(), ZoneOffset.UTC),
                authority.token(), authority.token(), authority.authorityFenceEpoch(),
                authority.authorityFenceEpoch(), authority.controlFenceEpoch(),
                authority.controlFenceEpoch(), item.effectIdempotencyKey(),
                item.effectRequestDigest());
        if (!Boolean.TRUE.equals(live)) {
            throw conflict("AUTOMATION_STALE_EFFECT_AUTHORITY");
        }
    }

    public LeaseGrant acquireWorkerLease(
            RuntimeRun run,
            UUID leaseToken,
            OffsetDateTime leasedUntil,
            OffsetDateTime now) {
        if (!List.of(RunState.QUEUED, RunState.LEASED).contains(run.run().state())
                || run.workerLeaseToken() != null || run.reconcilerLeaseToken() != null
                || run.reconciliationRequiredAt() != null
                || run.reconciliationRequiredReason() != null) {
            throw conflict("AUTOMATION_WORKER_LEASE_UNAVAILABLE");
        }
        long nextFence = run.workerFenceEpoch() + 1;
        int attempt = jdbc.update("""
                UPDATE sys_automation_run_attempts
                   SET lifecycle_state = 'LEASED', worker_fence_epoch = ?,
                       worker_lease_token = ?, worker_leased_until = ?,
                       worker_lease_fence_epoch_snapshot = ?,
                       worker_control_fence_epoch_snapshot = ?,
                       worker_lease_revoked_at = NULL,
                       started_at = COALESCE(started_at, ?)
                 WHERE tenant_id = ? AND run_attempt_id = ?
                   AND worker_lease_token IS NULL
                   AND reconciliation_lease_token IS NULL
                   AND reconciliation_required_at IS NULL
                   AND reconciliation_required_reason IS NULL
                   AND lifecycle_state IN ('QUEUED', 'LEASED')
                """, nextFence, leaseToken, leasedUntil, nextFence,
                run.controlFenceEpoch(), now, run.run().tenantId(),
                run.runAttemptId());
        if (attempt != 1) throw conflict("AUTOMATION_WORKER_LEASE_CAS_CONFLICT");
        int aggregate = jdbc.update("""
                UPDATE sys_automation_runs
                   SET lifecycle_state = 'LEASED', worker_fence_epoch = ?,
                       worker_lease_token = ?, worker_leased_until = ?,
                       worker_lease_fence_snapshot = ?,
                       worker_control_fence_snapshot = ?, worker_revoked_at = NULL,
                       output_digest = NULL,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                   AND worker_lease_token IS NULL
                   AND reconciliation_lease_token IS NULL
                   AND reconciliation_required_reason IS NULL
                   AND lifecycle_state IN ('QUEUED', 'LEASED')
                """, nextFence, leaseToken, leasedUntil, nextFence,
                run.controlFenceEpoch(), now, run.run().tenantId(), run.run().runId(),
                run.run().objectRevision());
        if (aggregate != 1) throw conflict("AUTOMATION_WORKER_LEASE_CAS_CONFLICT");
        return new LeaseGrant(AutomationDomainPolicy.AuthorityKind.WORKER,
                run.run().tenantId(), run.run().publicId(), run.attemptNumber(),
                leaseToken, nextFence, run.controlFenceEpoch(), leasedUntil);
    }

    public LeaseGrant renewWorkerLease(
            RuntimeRun run,
            LeaseGrant current,
            OffsetDateTime leasedUntil,
            OffsetDateTime now) {
        int attempt = jdbc.update("""
                UPDATE sys_automation_run_attempts SET worker_leased_until = ?
                 WHERE tenant_id = ? AND run_attempt_id = ?
                   AND worker_lease_token = ? AND worker_fence_epoch = ?
                   AND worker_control_fence_epoch_snapshot = ?
                   AND reconciliation_lease_token IS NULL
                   AND worker_leased_until > ?
                """, leasedUntil, run.run().tenantId(), run.runAttemptId(),
                current.token(), current.authorityFenceEpoch(),
                current.controlFenceEpoch(), now);
        int aggregate = jdbc.update("""
                UPDATE sys_automation_runs SET worker_leased_until = ?, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND worker_lease_token = ?
                   AND worker_fence_epoch = ? AND control_fence_epoch = ?
                   AND reconciliation_lease_token IS NULL
                   AND worker_leased_until > ?
                """, leasedUntil, now, run.run().tenantId(), run.run().runId(),
                current.token(), current.authorityFenceEpoch(),
                current.controlFenceEpoch(), now);
        if (attempt != 1 || aggregate != 1) {
            throw conflict("AUTOMATION_WORKER_LEASE_CAS_CONFLICT");
        }
        return new LeaseGrant(AutomationDomainPolicy.AuthorityKind.WORKER,
                current.tenantId(), current.runPublicId(), current.attemptNumber(),
                current.token(), current.authorityFenceEpoch(),
                current.controlFenceEpoch(), leasedUntil);
    }

    public void materializeItems(
            RuntimeRun run,
            LeaseGrant worker,
            List<AutomationRuntimePorts.AutomationItemPlan> items,
            OffsetDateTime now) {
        requireWorkerFence(run, worker, now);
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                """, Integer.class, run.run().tenantId(), run.run().runId());
        if (existing == null || existing != 0
                || items.size() != run.expectedItemCount()) {
            throw conflict("AUTOMATION_ITEM_MATERIALIZATION_CONFLICT");
        }
        List<AutomationRuntimePorts.AutomationItemPlan> ordered = items.stream()
                .sorted(Comparator.comparingLong(
                        AutomationRuntimePorts.AutomationItemPlan::itemSequence))
                .toList();
        String selectionDigest = selectionDigest(
                ordered.stream().map(AutomationRuntimePorts.AutomationItemPlan::itemId)
                        .toList());
        int updatedAttempt = jdbc.update("""
                UPDATE sys_automation_run_attempts
                   SET selected_item_count = ?, selected_item_digest = ?
                 WHERE tenant_id = ? AND run_attempt_id = ?
                   AND selected_item_count = 0
                """, ordered.size(), selectionDigest, run.run().tenantId(),
                run.runAttemptId());
        int updatedRun = jdbc.update("""
                UPDATE sys_automation_runs
                   SET item_total = ?, lifecycle_state = 'RUNNING',
                       output_digest = NULL,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                   AND worker_lease_token = ? AND worker_fence_epoch = ?
                   AND control_fence_epoch = ?
                """, ordered.size(), now, run.run().tenantId(), run.run().runId(),
                run.run().objectRevision(), worker.token(),
                worker.authorityFenceEpoch(), worker.controlFenceEpoch());
        if (updatedAttempt != 1 || updatedRun != 1) {
            throw conflict("AUTOMATION_ITEM_MATERIALIZATION_CAS_CONFLICT");
        }
        long materializedRevision = run.run().objectRevision() + 1;
        for (AutomationRuntimePorts.AutomationItemPlan item : ordered) {
            UUID internal = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO sys_automation_run_items (
                        run_item_id, public_id, tenant_id, run_id, item_sequence,
                        subject_ref, subject_reference_digest, compensation_policy_ref,
                        compensation_policy_digest, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, internal, item.itemId(), run.run().tenantId(),
                    run.run().runId(), item.itemSequence(), item.subjectRef(),
                    AutomationDomainPolicy.sha256(
                            item.subjectRef().getBytes(StandardCharsets.UTF_8)),
                    run.compensationPolicyRef(), run.compensationPolicyDigest(), now);
            jdbc.update("""
                    INSERT INTO sys_automation_item_attempts (
                        tenant_id, run_id, run_item_id, attempt_ordinal,
                        run_revision_snapshot, receipt_state, accepted_at,
                        retryable_snapshot, correlation_id, created_at)
                    VALUES (?, ?, ?, ?, ?, 'ACCEPTED', ?, FALSE, ?, ?)
                    """, run.run().tenantId(), run.run().runId(), internal,
                    run.attemptNumber(), materializedRevision, now,
                    run.correlationId(), now);
        }
    }

    public PreparedOriginal prepareOriginal(
            RuntimeRun run,
            LeaseGrant worker,
            String requestDigest,
            OffsetDateTime now) {
        requireWorkerFence(run, worker, now);
        List<PreparedItem> items = jdbc.query("""
                SELECT i.run_item_id, i.public_id, i.item_sequence, i.subject_ref,
                       a.accepted_at
                  FROM sys_automation_run_items i
                  JOIN sys_automation_item_attempts a
                    ON a.tenant_id = i.tenant_id
                   AND a.run_item_id = i.run_item_id
                   AND a.attempt_ordinal = ?
                 WHERE i.tenant_id = ? AND i.run_id = ?
                   AND i.lifecycle_state NOT IN
                       ('SUCCEEDED', 'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
                   AND a.effect_state = 'NOT_STARTED'
                 ORDER BY i.item_sequence
                """, (rs, row) -> {
            UUID publicId = uuid(rs, "public_id");
            String key = AutomationDomainPolicy.sha256(("original\u001f"
                    + run.run().publicId() + "\u001f" + publicId + "\u001f"
                    + run.attemptNumber()).getBytes(StandardCharsets.UTF_8));
            String itemDigest = AutomationDomainPolicy.sha256((requestDigest
                    + "\u001f" + publicId).getBytes(StandardCharsets.UTF_8));
            return new PreparedItem(publicId, rs.getLong("item_sequence"),
                    rs.getString("subject_ref"),
                    rs.getObject("accepted_at", OffsetDateTime.class).toInstant(),
                    key, itemDigest);
        }, run.attemptNumber(), run.run().tenantId(), run.run().runId());
        if (items.isEmpty()) throw conflict("AUTOMATION_DISPATCH_SELECTION_EMPTY");
        for (PreparedItem item : items) {
            int attempt = jdbc.update("""
                    UPDATE sys_automation_item_attempts a
                       SET receipt_state = 'RUNNING', started_at = ?,
                           run_revision_snapshot = ?,
                           effect_state = 'PREPARED', effect_authority_kind = 'WORKER',
                           effect_idempotency_key = ?, effect_request_digest = ?,
                           effect_dispatch_authority_fence = ?,
                           effect_dispatch_control_fence = ?
                      FROM sys_automation_run_items i
                     WHERE a.tenant_id = ? AND a.run_id = ?
                       AND a.run_item_id = i.run_item_id
                       AND a.attempt_ordinal = ? AND i.public_id = ?
                       AND a.effect_state = 'NOT_STARTED'
                    """, now, run.run().objectRevision(),
                    item.effectIdempotencyKey(), item.effectRequestDigest(),
                    worker.authorityFenceEpoch(), worker.controlFenceEpoch(),
                    run.run().tenantId(), run.run().runId(), run.attemptNumber(),
                    item.itemPublicId());
            int projection = jdbc.update("""
                    UPDATE sys_automation_run_items
                       SET lifecycle_state = 'RUNNING',
                           attempt_count = attempt_count + 1,
                           effect_state = 'PREPARED', effect_idempotency_key = ?,
                           effect_request_digest = ?, updated_at = ?
                     WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                       AND effect_state = 'NOT_STARTED'
                    """, item.effectIdempotencyKey(), item.effectRequestDigest(), now,
                    run.run().tenantId(), run.run().runId(), item.itemPublicId());
            if (attempt != 1 || projection != 1) {
                throw conflict("AUTOMATION_EFFECT_PREPARE_CAS_CONFLICT");
            }
        }
        return new PreparedOriginal(invocation(run), run.manifest(), now.plusMinutes(1),
                items);
    }

    public RuntimeRun finalizeOriginal(
            RuntimeRun run,
            LeaseGrant worker,
            PreparedOriginal prepared,
            List<AutomationRunItemReceiptV1> receipts,
            OffsetDateTime now) {
        requireWorkerFence(run, worker, now);
        Map<UUID, PreparedItem> preparedById = prepared.items().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        PreparedItem::itemPublicId, item -> item));
        for (AutomationRunItemReceiptV1 rawReceipt : receipts) {
            AutomationRunItemReceiptV1 receipt =
                    Sys006TypedArtifactContract.requireInteroperable(rawReceipt);
            PreparedItem item = preparedById.get(receipt.itemId());
            if (item == null || !receipt.runId().equals(run.run().publicId())
                    || receipt.itemSequence() != item.itemSequence()
                    || !receipt.subjectRef().equals(item.subjectRef())
                    || !receipt.acceptedAt().equals(item.acceptedAt())
                    || receipt.runRevision() != run.run().objectRevision()
                    || receipt.attemptNumber() != run.attemptNumber()
                    || !receipt.correlationId().equals(run.correlationId())
                    || receipt.completedAt() == null
                    || !Set.of("SUCCEEDED", "FAILED", "CANCELED",
                    "DEAD_LETTERED").contains(receipt.state())) {
                throw conflict("AUTOMATION_EFFECT_RECEIPT_SUBSTITUTED");
            }
            if ("FAILED".equals(receipt.state())
                    && (run.run().retryMode() == RetryMode.NONE
                    || !receipt.retryable()
                    || run.attemptNumber() >= run.run().maxAttempts())) {
                throw conflict("AUTOMATION_EFFECT_RECEIPT_NOT_OWNER_NORMALIZED");
            }
            String originalReceiptState = mapReceiptState(receipt);
            String receiptState = originalReceiptState;
            if (List.of("FAILED_RETRYABLE", "FAILED_FINAL")
                    .contains(receiptState)
                    && (run.run().retryMode() == RetryMode.NONE
                    || !receipt.retryable()
                    || run.attemptNumber() >= run.run().maxAttempts())) {
                receiptState = "DEAD_LETTER";
            }
            boolean retryableSnapshot = "FAILED_RETRYABLE".equals(receiptState);
            String effectState = switch (originalReceiptState) {
                case "SUCCEEDED" -> "COMMITTED";
                case "CANCELLED" -> "NO_EFFECT_CONFIRMED";
                case "DEAD_LETTER" -> receipt.resultDigest() == null
                        ? "RESULT_UNKNOWN" : "COMMITTED";
                default -> receipt.resultDigest() == null
                        ? "RESULT_UNKNOWN" : "COMMITTED";
            };
            UUID receiptId = UUID.randomUUID();
            String receiptDigest = canonical.digest(receipt);
            int attempt = jdbc.update("""
                    UPDATE sys_automation_item_attempts a
                       SET receipt_state = ?, completed_at = ?, result_ref = ?,
                           run_revision_snapshot = ?, result_digest = ?,
                           error_code = ?, retryable_snapshot = ?,
                           next_attempt_at = ?, effect_state = ?, effect_receipt_id = ?,
                           effect_receipt_digest = ?, effect_resolution_authority_fence = ?,
                           effect_resolution_control_fence = ?,
                           effect_resolution_mode = 'DISPATCH_FINALIZE',
                           effect_committed_at = CASE WHEN ? = 'COMMITTED' THEN ? ELSE NULL END
                      FROM sys_automation_run_items i
                     WHERE a.tenant_id = ? AND a.run_id = ?
                       AND a.run_item_id = i.run_item_id
                       AND a.attempt_ordinal = ? AND i.public_id = ?
                       AND a.effect_state = 'PREPARED'
                       AND a.effect_idempotency_key = ?
                       AND a.effect_request_digest = ?
                       AND a.effect_dispatch_authority_fence = ?
                       AND a.effect_dispatch_control_fence = ?
                    """, receiptState,
                    OffsetDateTime.ofInstant(receipt.completedAt(), ZoneOffset.UTC),
                    receipt.resultRef(), run.run().objectRevision(),
                    receipt.resultDigest(), receipt.errorCode(),
                    retryableSnapshot, !retryableSnapshot
                    || receipt.nextAttemptAt() == null ? null
                    : OffsetDateTime.ofInstant(receipt.nextAttemptAt(), ZoneOffset.UTC),
                    effectState, receiptId, receiptDigest,
                    worker.authorityFenceEpoch(), worker.controlFenceEpoch(),
                    effectState, now, run.run().tenantId(), run.run().runId(),
                    run.attemptNumber(), receipt.itemId(), item.effectIdempotencyKey(),
                    item.effectRequestDigest(), worker.authorityFenceEpoch(),
                    worker.controlFenceEpoch());
            int projection = jdbc.update("""
                    UPDATE sys_automation_run_items
                       SET lifecycle_state = ?, result_digest = ?, error_code = ?,
                           effect_state = ?, effect_receipt_digest = ?, updated_at = ?
                     WHERE tenant_id = ? AND run_id = ? AND public_id = ?
                       AND effect_state = 'PREPARED'
                       AND effect_idempotency_key = ? AND effect_request_digest = ?
                    """, receiptState, receipt.resultDigest(), receipt.errorCode(),
                    effectState, receiptDigest, now, run.run().tenantId(),
                    run.run().runId(), receipt.itemId(), item.effectIdempotencyKey(),
                    item.effectRequestDigest());
            if (attempt != 1 || projection != 1) {
                throw conflict("AUTOMATION_EFFECT_FINALIZE_CAS_CONFLICT");
            }
            AutomationRunItemReceiptV1 reloaded = owner.loadOriginalReceipt(
                    run, receipt.itemId(), run.attemptNumber());
            String storedDigest = jdbc.queryForObject("""
                    SELECT a.effect_receipt_digest
                      FROM sys_automation_item_attempts a
                      JOIN sys_automation_run_items i
                        ON i.tenant_id = a.tenant_id
                       AND i.run_id = a.run_id
                       AND i.run_item_id = a.run_item_id
                     WHERE a.tenant_id = ? AND a.run_id = ?
                       AND a.attempt_ordinal = ? AND i.public_id = ?
                    """, String.class, run.run().tenantId(), run.run().runId(),
                    run.attemptNumber(), receipt.itemId());
            String reloadedDigest = canonical.digest(reloaded);
            if (!receipt.equals(reloaded) || !receiptDigest.equals(storedDigest)
                    || !receiptDigest.equals(reloadedDigest)) {
                throw conflict("AUTOMATION_EFFECT_RECEIPT_PERSISTENCE_MISMATCH");
            }
            if ("RESULT_UNKNOWN".equals(effectState)) {
                jdbc.update("""
                        UPDATE sys_automation_run_attempts
                           SET reconciliation_required_at = ?,
                               reconciliation_required_reason =
                                   'RESULT_UNKNOWN_EVIDENCE_REQUIRED'
                         WHERE tenant_id = ? AND run_attempt_id = ?
                        """, now, run.run().tenantId(), run.runAttemptId());
                jdbc.update("""
                        UPDATE sys_automation_runs
                           SET reconciliation_required_reason =
                                   'RESULT_UNKNOWN_EVIDENCE_REQUIRED',
                               updated_at = ?
                         WHERE tenant_id = ? AND run_id = ?
                        """, now, run.run().tenantId(), run.run().runId());
            }
        }
        return lockRuntimeRun(run.run().tenantId(), run.run().publicId());
    }
}
