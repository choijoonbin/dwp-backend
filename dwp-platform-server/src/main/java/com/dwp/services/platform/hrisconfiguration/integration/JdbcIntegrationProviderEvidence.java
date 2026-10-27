package com.dwp.services.platform.hrisconfiguration.integration;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.JdbcIntegrationRepositoryData.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Internal jdbc integration provider evidence operations shared by the owner facade. */
final class JdbcIntegrationProviderEvidence {

    private final JdbcIntegrationRepositoryOperations owner;
    private final JdbcTemplate jdbc;
    private final IntegrationCanonicalJson canonical;

    JdbcIntegrationProviderEvidence(
            JdbcIntegrationRepositoryOperations owner,
            JdbcTemplate jdbc,
            IntegrationCanonicalJson canonical) {
        this.owner = owner;
        this.jdbc = jdbc;
        this.canonical = canonical;
    }


    public ExecutionRecord applyProviderEvidence(
            ExecutionRecord current,
            IntegrationRuntimePorts.ProviderEvidence evidence,
            ExecutionState target,
            OffsetDateTime now) {
        IntegrationDomainPolicy.requireTransition(current.state(), target);
        if (evidence.sourceCount() != current.sourceCount()) {
            throw conflict("SYS008_PROVIDER_SOURCE_SNAPSHOT_CHANGED");
        }
        String evidenceDigest = canonical.digest(evidence);
        String operationsExceptionRef = target == ExecutionState.RESULT_UNKNOWN
                ? "hris-operations/sys008/" + current.executionId() : null;
        applyAttemptAndItemEvidence(current, evidence, evidenceDigest, target, now);
        int changed = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = ?,
                       provider_receipt_ref = CASE
                           WHEN lifecycle_state = 'DISPATCHED'
                           THEN ?
                           ELSE provider_receipt_ref END,
                       provider_receipt_digest = CASE
                           WHEN lifecycle_state = 'DISPATCHED'
                           THEN ?
                           ELSE provider_receipt_digest END,
                       provider_evidence_digest = CASE
                           WHEN lifecycle_state = 'DISPATCHED'
                           THEN ? ELSE provider_evidence_digest END,
                       provider_failure_conclusive = ?,
                       unresolved_external_effect = ?,
                       accepted_count = ?, rejected_count = ?,
                       quarantined_count = ?, reconciliation_digest = CASE
                           WHEN lifecycle_state = 'RECONCILING'
                           THEN ? ELSE reconciliation_digest END,
                       operations_exception_ref = COALESCE(?, operations_exception_ref),
                       object_revision = object_revision + 1, updated_at = ?,
                       completed_at = CASE WHEN ? = 'RECONCILED' THEN ?
                                           ELSE completed_at END
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = ? AND object_revision = ?
                   AND provider_idempotency_key = ?
                   AND provider_request_digest = ?
                """, target.name(), evidence.receiptRef(),
                evidence.receiptDigest(), evidenceDigest,
                target == ExecutionState.FAILED,
                target == ExecutionState.RESULT_UNKNOWN,
                evidence.acceptedCount(), evidence.rejectedCount(),
                evidence.quarantinedCount(),
                evidence.reconciliationDigest(), operationsExceptionRef, now,
                target.name(), now, current.tenantId(), current.internalId(),
                current.state().name(), current.aggregateRevision(),
                current.providerIdempotencyKey(), current.providerRequestDigest());
        if (changed != 1) throw conflict("SYS008_PROVIDER_EVIDENCE_CAS_CONFLICT");
        return owner.lockExecution(current.tenantId(), current.executionId());
    }

    void applyAttemptAndItemEvidence(
            ExecutionRecord current,
            IntegrationRuntimePorts.ProviderEvidence evidence,
            String evidenceDigest,
            ExecutionState target,
            OffsetDateTime now) {
        List<AttemptRecord> attempts = owner.currentAttempts(current);
        Map<UUID, IntegrationRuntimePorts.ProviderItemEvidence> itemEvidence =
                normalizedItemEvidence(current, evidence, attempts, target);
        if (current.state() == ExecutionState.DISPATCHED) {
            for (AttemptRecord attempt : attempts) {
                var item = itemEvidence.get(attempt.itemPublicId());
                requireAttemptBinding(attempt, item);
                String attemptTarget = switch (item.outcome()) {
                    case ACKNOWLEDGED -> "ACKNOWLEDGED";
                    case FAILED -> "FAILED";
                    case RESULT_UNKNOWN -> "RESULT_UNKNOWN";
                    default -> throw conflict(
                            "SYS008_ATTEMPT_PROVIDER_OUTCOME_INVALID");
                };
                String receiptRef = item.receiptRef() == null
                        ? evidence.receiptRef() : item.receiptRef();
                String receiptDigest = item.receiptDigest() == null
                        ? evidence.receiptDigest() : item.receiptDigest();
                String failureClass = "FAILED".equals(attemptTarget)
                        ? requiredFailureClass(item, "PROVIDER_FAILURE") : null;
                int attemptChanged = jdbc.update("""
                        UPDATE sys_connector_execution_attempts
                           SET lifecycle_state = ?,
                               provider_receipt_ref = ?,
                               provider_receipt_digest = ?,
                               response_digest = ?, failure_class = ?,
                               acknowledged_at = CASE WHEN ? = 'ACKNOWLEDGED'
                                   THEN ? ELSE acknowledged_at END,
                               completed_at = CASE WHEN ? = 'FAILED'
                                   THEN ? ELSE completed_at END,
                               object_revision = object_revision + 1,
                               updated_at = ?
                         WHERE tenant_id = ? AND execution_attempt_id = ?
                           AND lifecycle_state = 'DISPATCHED'
                        """, attemptTarget, receiptRef, receiptDigest,
                        evidenceDigest, failureClass, attemptTarget, now,
                        attemptTarget, now, now, current.tenantId(),
                        attempt.attemptId());
                if (attemptChanged != 1) {
                    throw conflict("SYS008_ATTEMPT_PROVIDER_EVIDENCE_CONFLICT");
                }
                String itemTarget = switch (item.outcome()) {
                    case ACKNOWLEDGED -> "ACCEPTED";
                    case FAILED -> item.retryable()
                            ? "REJECTED_RETRYABLE" : "REJECTED_FINAL";
                    case RESULT_UNKNOWN -> "RESULT_UNKNOWN";
                    default -> throw conflict(
                            "SYS008_ITEM_PROVIDER_OUTCOME_INVALID");
                };
                int itemChanged = jdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = ?,
                               provider_receipt_ref = ?,
                               provider_receipt_digest = ?,
                               provider_evidence_digest = ?,
                               failure_class = ?,
                               retryable_snapshot = CASE
                                   WHEN ? = 'REJECTED_RETRYABLE' THEN TRUE
                                   WHEN ? = 'REJECTED_FINAL' THEN FALSE
                                   ELSE NULL END,
                               quarantined_snapshot = CASE
                                   WHEN ? IN (
                                       'REJECTED_RETRYABLE', 'REJECTED_FINAL')
                                   THEN ? ELSE FALSE END,
                               object_revision = object_revision + 1,
                               updated_at = ?
                         WHERE tenant_id = ? AND execution_item_id = ?
                           AND lifecycle_state = 'DISPATCHED'
                        """, itemTarget, receiptRef, receiptDigest,
                        evidenceDigest, failureClass, itemTarget, itemTarget,
                        itemTarget, item.quarantined(), now,
                        current.tenantId(), attempt.itemId());
                if (itemChanged != 1) {
                    throw conflict("SYS008_ITEM_PROVIDER_EVIDENCE_CONFLICT");
                }
            }
            requireRelationalCounts(current, evidence);
            return;
        }
        if (current.state() != ExecutionState.RECONCILING
                || target == ExecutionState.RESULT_UNKNOWN) {
            return;
        }
        for (AttemptRecord attempt : attempts) {
            var item = itemEvidence.get(attempt.itemPublicId());
            requireAttemptBinding(attempt, item);
            boolean reconciled = item.outcome()
                    == IntegrationRuntimePorts.ProviderEvidence.Outcome.RECONCILED;
            String attemptTarget = reconciled ? "RECONCILED" : "FAILED";
            String itemTarget = reconciled ? "RECONCILED"
                    : item.retryable() ? "REJECTED_RETRYABLE" : "REJECTED_FINAL";
            String receiptRef = item.receiptRef() == null
                    ? current.providerReceiptRef() : item.receiptRef();
            String receiptDigest = item.receiptDigest() == null
                    ? current.providerReceiptDigest() : item.receiptDigest();
            String reconciliationDigest = item.reconciliationDigest() == null
                    ? evidence.reconciliationDigest()
                    : item.reconciliationDigest();
            String failureClass = reconciled ? null
                    : requiredFailureClass(item, "RECONCILIATION_FAILURE");
            int attemptChanged = jdbc.update("""
                    UPDATE sys_connector_execution_attempts
                       SET lifecycle_state = ?, provider_receipt_ref = ?,
                           provider_receipt_digest = ?,
                           reconciliation_digest = ?, failure_class = ?,
                           completed_at = ?,
                           object_revision = object_revision + 1,
                           updated_at = ?
                     WHERE tenant_id = ? AND execution_attempt_id = ?
                       AND lifecycle_state IN ('RESULT_UNKNOWN', 'ACKNOWLEDGED')
                    """, attemptTarget, receiptRef, receiptDigest,
                    reconciliationDigest, failureClass, now, now,
                    current.tenantId(), attempt.attemptId());
            if (attemptChanged != 1) {
                throw conflict("SYS008_ATTEMPT_RECONCILIATION_CONFLICT");
            }
            int itemChanged = jdbc.update("""
                    UPDATE sys_connector_execution_items
                       SET lifecycle_state = ?, provider_receipt_ref = ?,
                           provider_receipt_digest = ?,
                           reconciliation_digest = ?, failure_class = ?,
                           retryable_snapshot = CASE
                               WHEN ? = 'REJECTED_RETRYABLE' THEN TRUE
                               WHEN ? = 'REJECTED_FINAL' THEN FALSE
                               ELSE NULL END,
                           quarantined_snapshot = CASE
                               WHEN ? IN (
                                   'REJECTED_RETRYABLE', 'REJECTED_FINAL')
                               THEN ? ELSE FALSE END,
                           object_revision = object_revision + 1,
                           updated_at = ?
                     WHERE tenant_id = ? AND execution_item_id = ?
                       AND lifecycle_state IN ('RESULT_UNKNOWN', 'ACCEPTED')
                    """, itemTarget, receiptRef, receiptDigest,
                    reconciliationDigest, failureClass, itemTarget, itemTarget,
                    itemTarget, item.quarantined(), now,
                    current.tenantId(), attempt.itemId());
            if (itemChanged != 1) {
                throw conflict("SYS008_ITEM_RECONCILIATION_CONFLICT");
            }
        }
        requireRelationalCounts(current, evidence);
    }

    Map<UUID, IntegrationRuntimePorts.ProviderItemEvidence>
            normalizedItemEvidence(
                    ExecutionRecord current,
                    IntegrationRuntimePorts.ProviderEvidence evidence,
                    List<AttemptRecord> attempts,
                    ExecutionState target) {
        List<IntegrationRuntimePorts.ProviderItemEvidence> supplied =
                evidence.items();
        if (supplied.isEmpty() && attempts.size() == 1) {
            AttemptRecord attempt = attempts.getFirst();
            boolean failed = target == ExecutionState.FAILED
                    || target == ExecutionState.PARTIAL;
            supplied = List.of(new IntegrationRuntimePorts.ProviderItemEvidence(
                    attempt.itemPublicId(), evidence.outcome(),
                    attempt.providerIdempotencyKey(), attempt.requestDigest(),
                    evidence.receiptRef(), evidence.receiptDigest(),
                    evidence.reconciliationDigest(),
                    evidence.quarantinedCount() > 0,
                    failed ? "PROVIDER_RETRYABLE" : null, failed));
        }
        Map<UUID, IntegrationRuntimePorts.ProviderItemEvidence> indexed =
                new LinkedHashMap<>();
        for (var item : supplied) {
            if (indexed.put(item.itemPublicId(), item) != null) {
                throw conflict("SYS008_PROVIDER_ITEM_EVIDENCE_DUPLICATE");
            }
        }
        Set<UUID> expected = attempts.stream()
                .map(AttemptRecord::itemPublicId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!indexed.keySet().equals(expected)
                || indexed.size() != current.selectedItemCount()) {
            throw conflict("SYS008_PROVIDER_ITEM_EVIDENCE_SET_CHANGED");
        }
        return Map.copyOf(indexed);
    }

    void requireRelationalCounts(
            ExecutionRecord current,
            IntegrationRuntimePorts.ProviderEvidence evidence) {
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT COUNT(*) AS source_count,
                       COUNT(*) FILTER (WHERE lifecycle_state IN (
                           'ACCEPTED', 'RECONCILED')) AS accepted_count,
                       COUNT(*) FILTER (WHERE lifecycle_state IN (
                           'REJECTED_RETRYABLE', 'REJECTED_FINAL')) AS rejected_count,
                       COUNT(*) FILTER (
                           WHERE quarantined_snapshot = TRUE) AS quarantined_count
                  FROM sys_connector_execution_items
                 WHERE tenant_id = ? AND execution_id = ?
                """, current.tenantId(), current.internalId());
        if (((Number) counts.get("source_count")).longValue()
                    != evidence.sourceCount()
                || ((Number) counts.get("accepted_count")).longValue()
                    != evidence.acceptedCount()
                || ((Number) counts.get("rejected_count")).longValue()
                    != evidence.rejectedCount()
                || ((Number) counts.get("quarantined_count")).longValue()
                    != evidence.quarantinedCount()) {
            throw conflict("SYS008_PROVIDER_EVIDENCE_COUNTS_CHANGED");
        }
    }
}
