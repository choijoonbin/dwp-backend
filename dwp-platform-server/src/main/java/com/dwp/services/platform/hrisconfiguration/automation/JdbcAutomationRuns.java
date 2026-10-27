package com.dwp.services.platform.hrisconfiguration.automation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.JdbcAutomationRepositoryData.*;

/** Internal jdbc automation runs operations shared by the owner facade. */
final class JdbcAutomationRuns {

    private final JdbcAutomationRepositoryOperations owner;
    private final JdbcTemplate jdbc;

    JdbcAutomationRuns(
            JdbcAutomationRepositoryOperations owner,
            JdbcTemplate jdbc) {
        this.owner = owner;
        this.jdbc = jdbc;
    }


    public RunRecord lockRun(long tenantId, UUID runPublicId) {
        try {
            return jdbc.queryForObject("""
                    SELECT r.*, d.public_id AS definition_public_id
                      FROM sys_automation_runs r
                      JOIN sys_automation_definitions d
                        ON d.tenant_id = r.tenant_id
                       AND d.definition_id = r.definition_id
                     WHERE r.tenant_id = ? AND r.public_id = ? FOR UPDATE OF r
                    """, RUN_MAPPER, tenantId, runPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("AUTOMATION_RUN_NOT_FOUND");
        }
    }

    RunRecord readRun(long tenantId, UUID runPublicId) {
        try {
            return jdbc.queryForObject("""
                    SELECT r.*, d.public_id AS definition_public_id
                      FROM sys_automation_runs r
                      JOIN sys_automation_definitions d
                        ON d.tenant_id = r.tenant_id
                       AND d.definition_id = r.definition_id
                     WHERE r.tenant_id = ? AND r.public_id = ?
                    """, RUN_MAPPER, tenantId, runPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("AUTOMATION_RUN_NOT_FOUND");
        }
    }

    public RunRecord insertRun(
            DefinitionRecord definition,
            VersionRecord version,
            UUID actor,
            UUID commandReceiptId,
            QueueRunCommand command,
            String concurrencyKeyDigest,
            OffsetDateTime now) {
        UUID runId = UUID.randomUUID();
        UUID publicId = UUID.randomUUID();
        try {
            RunRecord run = jdbc.queryForObject("""
                    INSERT INTO sys_automation_runs (
                        run_id, public_id, tenant_id, definition_id, version_id,
                        version_number, scope_type, scope_id, requested_at,
                        requested_by_actor_ref, idempotency_key, correlation_id,
                        input_ref, input_digest, expected_item_count,
                        concurrency_key_digest, retry_mode, max_attempts,
                        item_total, command_receipt_id, created_by_actor_ref,
                        created_at, updated_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, r.idempotency_key,
                           r.correlation_id, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?
                      FROM sys_hris_command_receipts r
                     WHERE r.tenant_id = ? AND r.public_id = ?
                    RETURNING *, ?::uuid AS definition_public_id
                    """, RUN_MAPPER, runId, publicId, definition.tenantId(),
                    definition.definitionId(), version.versionId(),
                    version.versionNumber(), command.scopeType(), command.scopeId(),
                    now, "principal:" + actor, command.inputReference(),
                    command.inputDigest(), command.expectedItemCount(),
                    concurrencyKeyDigest, version.retryMode().name(),
                    version.maxAttempts(), commandReceiptId, actor, now, now,
                    definition.tenantId(), commandReceiptId, definition.publicId());
            jdbc.update("""
                    INSERT INTO sys_automation_run_attempts (
                        tenant_id, run_id, attempt_number, selection_mode,
                        selected_item_count, selected_item_digest, created_at)
                    VALUES (?, ?, 1, 'ALL_ITEMS', 0, ?, ?)
                    """, definition.tenantId(), runId,
                    emptySelectionDigest(publicId), now);
            return run;
        } catch (DuplicateKeyException duplicate) {
            throw conflict("AUTOMATION_CONCURRENCY_CONFLICT");
        }
    }

    public RunRecord retryRun(
            RunRecord run,
            UUID actor,
            UUID commandReceiptId,
            SelectionMode selectionMode,
            long selectedItemCount,
            String selectedItemDigest,
            OffsetDateTime now) {
        if (!List.of(RunState.PARTIAL, RunState.FAILED).contains(run.state())
                || run.retryMode() == RetryMode.NONE
                || run.currentAttempt() >= run.maxAttempts()
                || selectedItemCount < 1) {
            throw conflict("AUTOMATION_RUN_NOT_RETRYABLE");
        }
        int next = run.currentAttempt() + 1;
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE sys_automation_runs
                       SET lifecycle_state = 'QUEUED', selection_mode = ?,
                           current_attempt = ?, object_revision = object_revision + 1,
                           output_digest = NULL,
                           reconciliation_required_reason = NULL,
                           updated_at = ?, completed_at = NULL
                     WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                       AND lifecycle_state IN ('PARTIAL', 'FAILED')
                    """, selectionMode.name(), next, now, run.tenantId(),
                    run.runId(), run.objectRevision());
        } catch (DuplicateKeyException concurrency) {
            throw conflict("AUTOMATION_CONCURRENCY_CONFLICT");
        }
        if (updated != 1) throw conflict("AUTOMATION_RUN_CAS_CONFLICT");
        Long resumeAfter = jdbc.queryForObject("""
                SELECT COALESCE(MAX(checkpoint_ordinal), 0)
                  FROM sys_automation_run_checkpoints
                 WHERE tenant_id = ? AND run_id = ?
                """, Long.class, run.tenantId(), run.runId());
        jdbc.update("""
                INSERT INTO sys_automation_run_attempts (
                    tenant_id, run_id, attempt_number, selection_mode,
                    selected_item_count, selected_item_digest,
                    resume_after_checkpoint_ordinal, worker_fence_epoch,
                    reconciliation_fence_epoch, created_at)
                SELECT tenant_id, run_id, ?, ?, ?, ?, ?, worker_fence_epoch,
                       reconciliation_fence_epoch, ?
                  FROM sys_automation_runs
                 WHERE tenant_id = ? AND run_id = ?
                """, next, selectionMode.name(), selectedItemCount,
                selectedItemDigest, resumeAfter == null ? 0 : resumeAfter, now,
                run.tenantId(), run.runId());
        List<RetryItem> frozen = jdbc.query("""
                SELECT i.run_item_id, i.public_id, i.lifecycle_state,
                       i.result_digest, i.error_code,
                       latest.item_attempt_id AS prior_item_attempt_id,
                       latest.effect_state, latest.effect_receipt_digest,
                       r.correlation_id
                  FROM sys_automation_run_items i
                  JOIN sys_automation_runs r
                    ON r.tenant_id = i.tenant_id AND r.run_id = i.run_id
                  LEFT JOIN LATERAL (
                      SELECT a.item_attempt_id, a.effect_state,
                             a.effect_receipt_digest
                        FROM sys_automation_item_attempts a
                       WHERE a.tenant_id = i.tenant_id
                         AND a.run_item_id = i.run_item_id
                       ORDER BY a.attempt_ordinal DESC LIMIT 1
                  ) latest ON TRUE
                 WHERE i.tenant_id = ? AND i.run_id = ?
                   AND (? = 'ALL_ITEMS'
                        OR i.lifecycle_state = 'FAILED_RETRYABLE')
                 ORDER BY i.public_id
                """, (rs, row) -> new RetryItem(
                uuid(rs, "run_item_id"), uuid(rs, "public_id"),
                rs.getString("lifecycle_state"), rs.getString("result_digest"),
                rs.getString("error_code"), uuidNullable(rs, "prior_item_attempt_id"),
                rs.getString("effect_state"), rs.getString("effect_receipt_digest"),
                uuid(rs, "correlation_id")), run.tenantId(), run.runId(),
                selectionMode.name());
        if (frozen.size() != selectedItemCount
                || !selectionDigest(frozen.stream().map(RetryItem::publicId).toList())
                .equals(selectedItemDigest)) {
            throw conflict("AUTOMATION_RETRY_SELECTION_CHANGED");
        }
        for (RetryItem item : frozen) {
            boolean dedupedSuccess = "SUCCEEDED".equals(item.state());
            if (dedupedSuccess && (!"COMMITTED".equals(item.effectState())
                    || item.priorAttemptId() == null
                    || item.effectReceiptDigest() == null)) {
                throw conflict("AUTOMATION_RETRY_DEDUPE_EVIDENCE_MISSING");
            }
            jdbc.update("""
                    INSERT INTO sys_automation_item_attempts (
                        tenant_id, run_id, run_item_id, attempt_ordinal,
                        run_revision_snapshot, deduped_from_item_attempt_id,
                        receipt_state, accepted_at, result_digest, error_code,
                        retryable_snapshot, correlation_id, effect_state,
                        started_at, completed_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?,
                            'NOT_STARTED', ?, ?, ?)
                    """, run.tenantId(), run.runId(), item.runItemId(), next,
                    run.objectRevision() + 1,
                    dedupedSuccess ? item.priorAttemptId() : null,
                    dedupedSuccess ? "SUCCEEDED" : "ACCEPTED", now,
                    dedupedSuccess ? item.resultDigest() : null,
                    dedupedSuccess ? item.errorCode() : null,
                    item.correlationId(), dedupedSuccess ? now : null,
                    dedupedSuccess ? now : null, now);
            if (!dedupedSuccess) {
                int reset = jdbc.update("""
                        UPDATE sys_automation_run_items
                           SET lifecycle_state = 'PENDING', result_digest = NULL,
                               error_code = NULL, effect_state = 'NOT_STARTED',
                               effect_idempotency_key = NULL,
                               effect_request_digest = NULL,
                               effect_receipt_digest = NULL, updated_at = ?
                         WHERE tenant_id = ? AND run_id = ? AND run_item_id = ?
                           AND lifecycle_state = 'FAILED_RETRYABLE'
                           AND effect_state = 'NO_EFFECT_CONFIRMED'
                        """, now, run.tenantId(), run.runId(), item.runItemId());
                if (reset != 1) {
                    throw conflict("AUTOMATION_RETRY_EFFECT_NOT_CONFIRMED_SAFE");
                }
            }
        }
        return lockRun(run.tenantId(), run.publicId());
    }

    public RetrySelection retrySelection(RunRecord run) {
        if (!List.of(RunState.PARTIAL, RunState.FAILED).contains(run.state())
                || run.retryMode() == RetryMode.NONE
                || run.currentAttempt() >= run.maxAttempts()
                || run.maxAttempts() > 20) {
            throw conflict("AUTOMATION_RUN_NOT_RETRYABLE");
        }
        Integer unsafe = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_automation_run_items i
                  LEFT JOIN LATERAL (
                      SELECT a.receipt_state, a.retryable_snapshot,
                             a.effect_state, a.effect_receipt_digest
                        FROM sys_automation_item_attempts a
                       WHERE a.tenant_id = i.tenant_id
                         AND a.run_id = i.run_id
                         AND a.run_item_id = i.run_item_id
                       ORDER BY a.attempt_ordinal DESC LIMIT 1
                  ) latest ON TRUE
                 WHERE i.tenant_id = ? AND i.run_id = ?
                   AND (
                       (i.lifecycle_state = 'FAILED_RETRYABLE' AND NOT (
                           i.effect_state = 'NO_EFFECT_CONFIRMED'
                           AND latest.receipt_state = 'FAILED_RETRYABLE'
                           AND latest.retryable_snapshot
                           AND latest.effect_state = 'NO_EFFECT_CONFIRMED'))
                       OR (? = 'WHOLE_INVOCATION' AND (
                           (i.lifecycle_state = 'SUCCEEDED' AND NOT (
                               i.effect_state = 'COMMITTED'
                               AND i.effect_receipt_digest IS NOT NULL
                               AND latest.receipt_state = 'SUCCEEDED'
                               AND latest.effect_state = 'COMMITTED'
                               AND latest.effect_receipt_digest
                                   = i.effect_receipt_digest))
                           OR i.lifecycle_state NOT IN
                               ('SUCCEEDED', 'FAILED_RETRYABLE'))))
                """, Integer.class, run.tenantId(), run.runId(),
                run.retryMode().name());
        if (unsafe == null || unsafe > 0) {
            throw conflict("AUTOMATION_RETRY_EVIDENCE_INCOMPLETE");
        }
        String statePredicate = run.retryMode() == RetryMode.FAILED_ITEMS_ONLY
                ? " AND lifecycle_state = 'FAILED_RETRYABLE'" : "";
        List<UUID> selected = jdbc.query(
                "SELECT public_id FROM sys_automation_run_items"
                        + " WHERE tenant_id = ? AND run_id = ?" + statePredicate
                        + " ORDER BY public_id",
                (rs, row) -> uuid(rs, "public_id"), run.tenantId(), run.runId());
        if (selected.isEmpty()) {
            throw conflict("AUTOMATION_RETRY_SELECTION_EMPTY");
        }
        Integer retryable = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                   AND lifecycle_state = 'FAILED_RETRYABLE'
                """, Integer.class, run.tenantId(), run.runId());
        if (retryable == null || retryable < 1) {
            throw conflict("AUTOMATION_RETRY_SELECTION_EMPTY");
        }
        String digest = selectionDigest(selected);
        if (run.retryMode() == RetryMode.WHOLE_INVOCATION) {
            Integer exactOriginal = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_automation_run_attempts
                     WHERE tenant_id = ? AND run_id = ? AND attempt_number = 1
                       AND selection_mode = 'ALL_ITEMS'
                       AND selected_item_count = ? AND selected_item_digest = ?
                    """, Integer.class, run.tenantId(), run.runId(),
                    selected.size(), digest);
            if (exactOriginal == null || exactOriginal != 1) {
                throw conflict("AUTOMATION_RETRY_ORIGINAL_SELECTION_MISMATCH");
            }
        }
        return new RetrySelection(
                run.retryMode() == RetryMode.FAILED_ITEMS_ONLY
                        ? SelectionMode.FAILED_ITEMS_ONLY : SelectionMode.ALL_ITEMS,
                selected.size(), digest);
    }

    public RunRecord cancelRun(
            RunRecord run, String reason, OffsetDateTime now) {
        Integer nonCompensatable = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                   AND effect_state = 'COMMITTED'
                   AND compensation_policy_ref IS NULL
                """, Integer.class, run.tenantId(), run.runId());
        if (nonCompensatable != null && nonCompensatable > 0) {
            throw conflict("AUTOMATION_CANCEL_NON_COMPENSATABLE_EFFECT");
        }
        Map<String, Object> effects = jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE effect_state IN
                           ('PREPARED', 'RESULT_UNKNOWN')) AS uncertain,
                       COUNT(*) FILTER (WHERE effect_state = 'COMMITTED'
                           AND compensation_state <> 'SUCCEEDED') AS committed,
                       COUNT(*) FILTER (WHERE compensation_state IN
                           ('PENDING', 'RUNNING', 'RESULT_UNKNOWN')) AS pending
                  FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                """, run.tenantId(), run.runId());
        long uncertain = ((Number) effects.get("uncertain")).longValue();
        long committed = ((Number) effects.get("committed")).longValue();
        long pending = ((Number) effects.get("pending")).longValue();
        boolean immediate = uncertain == 0 && committed == 0 && pending == 0;
        Map<String, Object> lease = jdbc.queryForMap("""
                SELECT worker_lease_token, reconciliation_lease_token
                  FROM sys_automation_runs
                 WHERE tenant_id = ? AND run_id = ? FOR UPDATE
                """, run.tenantId(), run.runId());
        UUID workerToken = (UUID) lease.get("worker_lease_token");
        UUID reconcilerToken = (UUID) lease.get("reconciliation_lease_token");
        if (workerToken != null && reconcilerToken != null) {
            throw conflict("AUTOMATION_AUTHORITY_LEASES_NOT_EXCLUSIVE");
        }
        long nextRevision = run.objectRevision() + 1;
        int updated = jdbc.update("""
                UPDATE sys_automation_runs
                   SET lifecycle_state = 'RUNNING', cancellation_requested_at = ?,
                       cancellation_reason = ?, control_fence_epoch = control_fence_epoch + 1,
                       worker_fence_epoch = worker_fence_epoch +
                           CASE WHEN worker_lease_token IS NULL THEN 0 ELSE 1 END,
                       reconciliation_fence_epoch = reconciliation_fence_epoch +
                           CASE WHEN reconciliation_lease_token IS NULL THEN 0 ELSE 1 END,
                       worker_revoked_at = CASE WHEN worker_lease_token IS NULL
                           THEN worker_revoked_at ELSE ? END,
                       reconciliation_revoked_at = CASE
                           WHEN reconciliation_lease_token IS NULL
                           THEN reconciliation_revoked_at ELSE ? END,
                       worker_lease_token = NULL, worker_leased_until = NULL,
                       worker_lease_fence_snapshot = NULL,
                       worker_control_fence_snapshot = NULL,
                       reconciliation_lease_token = NULL,
                       reconciliation_leased_until = NULL,
                       reconciliation_lease_fence_snapshot = NULL,
                       reconciliation_control_fence_snapshot = NULL,
                       reconciliation_required_reason = CASE
                           WHEN ? > 0 THEN 'CANCEL_EFFECT_EVIDENCE_REQUIRED'
                           WHEN ? > 0 OR ? > 0 THEN 'CANCEL_COMPENSATION_REQUIRED'
                           ELSE reconciliation_required_reason END,
                       output_digest = NULL, completed_at = NULL,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                   AND lifecycle_state IN ('QUEUED', 'LEASED', 'RUNNING')
                """, now, reason, now, now, uncertain, committed, pending, now,
                run.tenantId(), run.runId(), run.objectRevision());
        if (updated != 1) throw conflict("AUTOMATION_RUN_CAS_CONFLICT");
        jdbc.update("""
                UPDATE sys_automation_run_items
                   SET compensation_state = 'PENDING', updated_at = ?
                 WHERE tenant_id = ? AND run_id = ? AND effect_state = 'COMMITTED'
                   AND compensation_policy_ref IS NOT NULL
                   AND compensation_state = 'NOT_REQUIRED'
                """, now, run.tenantId(), run.runId());
        jdbc.update("""
                UPDATE sys_automation_item_attempts a
                   SET receipt_state = 'CANCELLED',
                       run_revision_snapshot = ?,
                       started_at = COALESCE(started_at, ?), completed_at = ?,
                       retryable_snapshot = FALSE,
                       error_code = COALESCE(a.error_code, 'RUN_CANCELLED')
                  FROM sys_automation_run_items i
                 WHERE a.tenant_id = ? AND a.run_id = ?
                   AND a.run_item_id = i.run_item_id
                   AND a.completed_at IS NULL
                   AND a.effect_state IN ('NOT_STARTED', 'NO_EFFECT_CONFIRMED')
                """, nextRevision, now, now,
                run.tenantId(), run.runId());
        jdbc.update("""
                UPDATE sys_automation_run_items
                   SET lifecycle_state = 'CANCELLED', updated_at = ?
                 WHERE tenant_id = ? AND run_id = ?
                   AND effect_state IN ('NOT_STARTED', 'NO_EFFECT_CONFIRMED')
                   AND lifecycle_state NOT IN
                       ('SUCCEEDED', 'FAILED_FINAL', 'CANCELLED', 'DEAD_LETTER')
                """, now, run.tenantId(), run.runId());
        if (immediate) {
            String outputDigest = owner.currentReceiptSet(
                    run.tenantId(), run.runId(), run.publicId(), nextRevision).digest();
            int terminal = jdbc.update("""
                    UPDATE sys_automation_runs
                       SET lifecycle_state = 'CANCELLED', output_digest = ?,
                           completed_at = ?, updated_at = ?
                     WHERE tenant_id = ? AND run_id = ? AND object_revision = ?
                       AND lifecycle_state = 'RUNNING'
                    """, outputDigest, now, now, run.tenantId(), run.runId(),
                    nextRevision);
            if (terminal != 1) {
                throw conflict("AUTOMATION_RUN_CANCEL_FINALIZE_CONFLICT");
            }
        }
        jdbc.update("""
                UPDATE sys_automation_run_attempts
                   SET worker_fence_epoch = worker_fence_epoch
                           + CASE WHEN worker_lease_token IS NULL THEN 0 ELSE 1 END,
                       reconciliation_fence_epoch = reconciliation_fence_epoch
                           + CASE WHEN reconciliation_lease_token IS NULL THEN 0 ELSE 1 END,
                       worker_lease_revoked_at = CASE WHEN worker_lease_token IS NULL
                           THEN worker_lease_revoked_at ELSE ? END,
                       reconciliation_lease_revoked_at = CASE
                           WHEN reconciliation_lease_token IS NULL
                           THEN reconciliation_lease_revoked_at ELSE ? END,
                       worker_lease_token = NULL, worker_leased_until = NULL,
                       worker_lease_fence_epoch_snapshot = NULL,
                       worker_control_fence_epoch_snapshot = NULL,
                       reconciliation_lease_token = NULL,
                       reconciliation_leased_until = NULL,
                       reconciliation_lease_fence_epoch_snapshot = NULL,
                       reconciliation_control_fence_epoch_snapshot = NULL,
                       reconciliation_required_at = CASE WHEN ? OR ?
                           THEN COALESCE(reconciliation_required_at, ?) ELSE NULL END,
                       reconciliation_required_reason = CASE
                           WHEN ? > 0 THEN 'CANCEL_EFFECT_EVIDENCE_REQUIRED'
                           WHEN ? > 0 OR ? > 0 THEN 'CANCEL_COMPENSATION_REQUIRED'
                           ELSE NULL END,
                       lifecycle_state = CASE WHEN ? THEN 'CANCELLED'
                           ELSE lifecycle_state END,
                       started_at = CASE WHEN ? THEN COALESCE(started_at, ?)
                           ELSE started_at END,
                       completed_at = CASE WHEN ? THEN ? ELSE completed_at END
                 WHERE tenant_id = ? AND run_id = ? AND attempt_number = ?
                """, now, now, uncertain > 0, committed > 0 || pending > 0, now,
                uncertain, committed, pending, immediate, immediate, now,
                immediate, now, run.tenantId(), run.runId(),
                run.currentAttempt());
        return lockRun(run.tenantId(), run.publicId());
    }
}
