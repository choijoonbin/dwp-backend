package com.dwp.services.platform.hrisconfiguration.integration;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.JdbcIntegrationRepositoryData.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Internal jdbc integration retry operations shared by the owner facade. */
final class JdbcIntegrationRetry {

    private final JdbcIntegrationRepositoryOperations owner;
    private final JdbcTemplate jdbc;
    private final IntegrationCanonicalJson canonical;

    JdbcIntegrationRetry(
            JdbcIntegrationRepositoryOperations owner,
            JdbcTemplate jdbc,
            IntegrationCanonicalJson canonical) {
        this.owner = owner;
        this.jdbc = jdbc;
        this.canonical = canonical;
    }


    public List<UUID> retryableItemIds(ExecutionRecord current) {
        return jdbc.query("""
                SELECT public_id
                  FROM sys_connector_execution_items
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = 'REJECTED_RETRYABLE'
                   AND retryable_snapshot = TRUE
                 ORDER BY public_id::text
                """, (rs, row) -> uuid(rs, "public_id"),
                current.tenantId(), current.internalId());
    }

    public ExecutionRecord createRetryAttempts(
            ExecutionRecord current,
            List<UUID> exactFailedItemIds,
            String failedItemSetDigest,
            long actorId,
            OffsetDateTime now) {
        long fence = current.attemptFence();
        for (UUID publicItemId : exactFailedItemIds) {
            RetryItem item;
            try {
                item = jdbc.queryForObject("""
                        SELECT execution_item_id, latest_attempt_number,
                               latest_attempt_fence, source_digest,
                               mapping_version_id, mapping_version_number
                          FROM sys_connector_execution_items
                         WHERE tenant_id = ? AND execution_id = ?
                           AND public_id = ?
                           AND lifecycle_state = 'REJECTED_RETRYABLE'
                           AND retryable_snapshot = TRUE
                         FOR UPDATE
                        """, (rs, row) -> new RetryItem(
                        uuid(rs, "execution_item_id"),
                        rs.getInt("latest_attempt_number"),
                        rs.getLong("latest_attempt_fence"),
                        rs.getString("source_digest"),
                        uuid(rs, "mapping_version_id"),
                        rs.getLong("mapping_version_number")),
                        current.tenantId(), current.internalId(), publicItemId);
            } catch (EmptyResultDataAccessException changed) {
                throw conflict("SYS008_RETRY_ITEM_SET_CHANGED");
            }
            fence++;
            int attemptNumber = item.attemptNumber() + 1;
            String providerKey = "sys008-" + IntegrationDomainPolicy.sha256(
                    current.providerIdempotencyKey() + ":retry:"
                            + current.retryGeneration() + 1 + ":" + publicItemId);
            String requestDigest = canonical.digest(
                    current.providerRequestDigest(), item.sourceDigest(),
                    Long.toString(item.mappingVersionNumber()),
                    Integer.toString(attemptNumber));
            String transformedDigest = canonical.digest(
                    item.sourceDigest(), current.mappingDigest());
            jdbc.update("""
                    INSERT INTO sys_connector_execution_attempts (
                        tenant_id, execution_attempt_id, public_id, execution_id,
                        execution_item_id, attempt_number, attempt_fence,
                        execution_revision_snapshot, lifecycle_state,
                        provider_idempotency_key, request_digest,
                        object_revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?, ?, 1, ?, ?)
                    """, current.tenantId(), UUID.randomUUID(), UUID.randomUUID(),
                    current.internalId(), item.executionItemId(), attemptNumber,
                    fence, current.aggregateRevision(), providerKey,
                    requestDigest, now, now);
            int itemChanged = jdbc.update("""
                    UPDATE sys_connector_execution_items
                       SET lifecycle_state = 'DISPATCHED',
                           transformed_digest = COALESCE(transformed_digest, ?),
                           provider_item_key = COALESCE(provider_item_key, ?),
                           quarantined_snapshot = FALSE,
                           latest_attempt_number = ?, latest_attempt_fence = ?,
                           object_revision = object_revision + 1, updated_at = ?
                     WHERE tenant_id = ? AND execution_item_id = ?
                       AND lifecycle_state = 'REJECTED_RETRYABLE'
                       AND latest_attempt_number = ? AND latest_attempt_fence = ?
                    """, transformedDigest, providerKey,
                    attemptNumber, fence, now, current.tenantId(),
                    item.executionItemId(), item.attemptNumber(), item.attemptFence());
            if (itemChanged != 1) throw conflict("SYS008_RETRY_ITEM_FENCE_CONFLICT");
        }
        int parent = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'ACCEPTED',
                       selected_item_count = ?, selected_item_digest = ?,
                       accepted_count = (
                           SELECT COUNT(*)
                             FROM sys_connector_execution_items item
                            WHERE item.tenant_id = sys_connector_executions.tenant_id
                              AND item.execution_id =
                                  sys_connector_executions.execution_id
                              AND item.lifecycle_state IN (
                                  'ACCEPTED', 'RECONCILED')),
                       rejected_count = (
                           SELECT COUNT(*)
                             FROM sys_connector_execution_items item
                            WHERE item.tenant_id = sys_connector_executions.tenant_id
                              AND item.execution_id =
                                  sys_connector_executions.execution_id
                              AND item.lifecycle_state IN (
                                  'REJECTED_RETRYABLE', 'REJECTED_FINAL')),
                       quarantined_count = (
                           SELECT COUNT(*)
                             FROM sys_connector_execution_items item
                            WHERE item.tenant_id = sys_connector_executions.tenant_id
                              AND item.execution_id =
                                  sys_connector_executions.execution_id
                              AND item.quarantined_snapshot = TRUE),
                       retry_generation = retry_generation + 1,
                       current_attempt_fence = ?,
                       provider_failure_conclusive = FALSE,
                       unresolved_external_effect = FALSE,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state IN ('FAILED', 'PARTIAL')
                   AND object_revision = ? AND current_attempt_fence = ?
                """, exactFailedItemIds.size(), failedItemSetDigest, fence, now,
                current.tenantId(), current.internalId(),
                current.aggregateRevision(), current.attemptFence());
        if (parent != 1) throw conflict("SYS008_RETRY_PARENT_CAS_CONFLICT");
        return owner.lockExecution(current.tenantId(), current.executionId());
    }

    public ExecutionRecord deadLetter(
            ExecutionRecord current,
            long actorId,
            OffsetDateTime now) {
        if (current.state() != ExecutionState.FAILED
                && current.state() != ExecutionState.PARTIAL) {
            throw conflict("SYS008_DEAD_LETTER_STATE_INVALID");
        }
        int changed = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'DEAD_LETTER',
                       object_revision = object_revision + 1,
                       completed_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = ? AND object_revision = ?
                """, now, now, current.tenantId(), current.internalId(),
                current.state().name(), current.aggregateRevision());
        if (changed != 1) throw conflict("SYS008_DEAD_LETTER_CAS_CONFLICT");
        return owner.lockExecution(current.tenantId(), current.executionId());
    }

    public ExecutionRecord cancel(
            ExecutionRecord current,
            String compensationPolicyRef,
            String compensationPolicyDigest,
            String compensationReceiptRef,
            String compensationReceiptDigest,
            long actorId,
            OffsetDateTime now) {
        IntegrationDomainPolicy.requireTransition(
                current.state(), ExecutionState.CANCELLED);
        int parent = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'CANCELLED',
                       compensation_policy_ref = ?, compensation_policy_digest = ?,
                       compensation_receipt_ref = ?, compensation_receipt_digest = ?,
                       unresolved_external_effect = FALSE,
                       object_revision = object_revision + 1,
                       completed_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = ? AND object_revision = ?
                """, compensationPolicyRef, compensationPolicyDigest,
                compensationReceiptRef, compensationReceiptDigest,
                now, now, current.tenantId(), current.internalId(),
                current.state().name(), current.aggregateRevision());
        if (parent != 1) throw conflict("SYS008_CANCEL_PARENT_CAS_CONFLICT");
        jdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'CANCELLED',
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state IN ('PENDING', 'TRANSFORMED', 'DISPATCHED',
                       'RESULT_UNKNOWN', 'ACCEPTED', 'REJECTED_RETRYABLE')
                """, now, current.tenantId(), current.internalId());
        jdbc.update("""
                UPDATE sys_connector_execution_attempts
                   SET lifecycle_state = 'CANCELLED', completed_at = ?,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state IN (
                       'READY', 'DISPATCHED', 'RESULT_UNKNOWN', 'ACKNOWLEDGED')
                """, now, now, current.tenantId(), current.internalId());
        return owner.lockExecution(current.tenantId(), current.executionId());
    }
}
