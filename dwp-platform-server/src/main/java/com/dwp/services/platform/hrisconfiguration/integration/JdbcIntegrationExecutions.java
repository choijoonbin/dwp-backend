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

/** Internal jdbc integration executions operations shared by the owner facade. */
final class JdbcIntegrationExecutions {

    private final JdbcIntegrationRepositoryOperations owner;
    private final JdbcTemplate jdbc;
    private final IntegrationCanonicalJson canonical;

    JdbcIntegrationExecutions(
            JdbcIntegrationRepositoryOperations owner,
            JdbcTemplate jdbc,
            IntegrationCanonicalJson canonical) {
        this.owner = owner;
        this.jdbc = jdbc;
        this.canonical = canonical;
    }


    public ExecutionRecord insertExecution(
            DefinitionRecord definition,
            MappingRecord mapping,
            UUID executionPublicId,
            long actorId,
            UUID commandReceiptId,
            ExecutionCommand command,
            String providerIdempotencyKey,
            String providerRequestDigest,
            long authorizationRevision,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId,
            List<IntegrationRuntimePorts.SourceManifestItem> sourceManifest,
            OffsetDateTime now) {
        UUID internalId = UUID.randomUUID();
        List<IntegrationRuntimePorts.SourceManifestItem> manifest =
                List.copyOf(sourceManifest);
        if (manifest.isEmpty() || manifest.size() > 10_000) {
            throw conflict("SYS008_SOURCE_MANIFEST_INVALID");
        }
        String sourceSetDigest = canonical.digest(manifest);
        jdbc.update("""
                INSERT INTO sys_connector_executions (
                    tenant_id, execution_id, public_id, definition_id,
                    definition_version_id, definition_version_number,
                    mapping_version_id, mapping_version_number,
                    endpoint_policy_revision, secret_revision, operation,
                    lifecycle_state, payload_ref, payload_digest,
                    source_count, source_item_set_digest,
                    selected_item_count, selected_item_digest,
                    accepted_count, rejected_count, quarantined_count,
                    provider_key, provider_operation,
                    provider_idempotency_key, provider_request_digest,
                    authorization_revision, population_kind,
                    legal_entity_public_id, original_command_receipt_id,
                    retry_generation, current_attempt_fence, object_revision,
                    requested_by_actor_id, requested_scope_digest,
                    requested_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACCEPTED', ?, ?,
                        ?, ?, ?, ?, 0, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 1,
                        ?, ?, ?, ?)
                """, definition.tenantId(), internalId, executionPublicId,
                definition.definitionId(), definition.definitionVersionId(),
                definition.definitionVersion(), mapping.mappingId(),
                mapping.version(), definition.endpointPolicyRevision(),
                definition.secretRevision(), command.operation().name(),
                command.payloadRef(), command.payloadDigest(), manifest.size(),
                sourceSetDigest, manifest.size(), sourceSetDigest,
                definition.connectorId().toString(),
                command.operation().name(), providerIdempotencyKey,
                providerRequestDigest, authorizationRevision, populationKind,
                legalEntityPublicId, commandReceiptId,
                manifest.size(), actorId, requestedScopeDigest, now, now);
        int ordinal = 0;
        for (IntegrationRuntimePorts.SourceManifestItem source : manifest) {
            ordinal++;
            UUID itemId = UUID.randomUUID();
            UUID itemPublicId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO sys_connector_execution_items (
                        tenant_id, execution_item_id, public_id, execution_id,
                        item_ordinal, source_key_digest, source_digest,
                        mapping_version_id, mapping_version_number,
                        created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, definition.tenantId(), itemId, itemPublicId,
                    internalId, ordinal, source.sourceKeyDigest(),
                    source.sourceDigest(), mapping.mappingId(), mapping.version(),
                    now, now);
            String itemProviderKey = "sys008-item-" +
                    IntegrationDomainPolicy.sha256(providerIdempotencyKey
                            + ":" + source.sourceKeyDigest());
            String itemRequestDigest = canonical.digest(
                    providerRequestDigest, source.sourceKeyDigest(),
                    source.sourceDigest(), "1");
            jdbc.update("""
                    INSERT INTO sys_connector_execution_attempts (
                        tenant_id, execution_attempt_id, public_id, execution_id,
                        execution_item_id, attempt_number, attempt_fence,
                        execution_revision_snapshot, lifecycle_state,
                        provider_idempotency_key, request_digest,
                        object_revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 1, ?, 1, 'READY', ?, ?, 1, ?, ?)
                    """, definition.tenantId(), UUID.randomUUID(), UUID.randomUUID(),
                    internalId, itemId, ordinal, itemProviderKey,
                    itemRequestDigest, now, now);
        }
        return lockExecution(definition.tenantId(), executionPublicId);
    }

    public ExecutionRecord lockExecution(
            long tenantId, UUID executionId) {
        try {
            return jdbc.queryForObject("""
                    SELECT e.*, d.public_id AS connector_public_id,
                           v.endpoint_uri, v.endpoint_policy_key, v.secret_ref,
                           m.mapping_ref, m.mapping_digest, m.direction,
                           m.source_schema_digest, m.target_schema_digest,
                           receipt.correlation_id,
                           receipt.idempotency_key AS owner_idempotency_key
                      FROM sys_connector_executions e
                      JOIN sys_connector_definitions d
                        ON d.tenant_id = e.tenant_id
                       AND d.definition_id = e.definition_id
                      JOIN sys_connector_mapping_versions m
                        ON m.tenant_id = e.tenant_id
                       AND m.mapping_version_id = e.mapping_version_id
                      JOIN sys_connector_definition_versions v
                        ON v.tenant_id = e.tenant_id
                       AND v.definition_version_id = e.definition_version_id
                      JOIN sys_hris_command_receipts receipt
                        ON receipt.tenant_id = e.tenant_id
                       AND receipt.public_id = e.original_command_receipt_id
                     WHERE e.tenant_id = ? AND e.public_id = ?
                     FOR UPDATE OF e
                    """, (rs, row) -> owner.execution(rs), tenantId, executionId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("SYS008_EXECUTION_NOT_FOUND");
        }
    }

    public ExecutionRecord lockExecutionAuthorized(
            long tenantId,
            UUID executionId,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId) {
        try {
            return jdbc.queryForObject("""
                    SELECT e.*, d.public_id AS connector_public_id,
                           v.endpoint_uri, v.endpoint_policy_key, v.secret_ref,
                           m.mapping_ref, m.mapping_digest, m.direction,
                           m.source_schema_digest, m.target_schema_digest,
                           receipt.correlation_id,
                           receipt.idempotency_key AS owner_idempotency_key
                      FROM sys_connector_executions e
                      JOIN sys_connector_definitions d
                        ON d.tenant_id = e.tenant_id
                       AND d.definition_id = e.definition_id
                      JOIN sys_connector_mapping_versions m
                        ON m.tenant_id = e.tenant_id
                       AND m.mapping_version_id = e.mapping_version_id
                      JOIN sys_connector_definition_versions v
                        ON v.tenant_id = e.tenant_id
                       AND v.definition_version_id = e.definition_version_id
                      JOIN sys_hris_command_receipts receipt
                        ON receipt.tenant_id = e.tenant_id
                       AND receipt.public_id = e.original_command_receipt_id
                     WHERE e.tenant_id = ? AND e.public_id = ?
                       AND e.requested_scope_digest = ?
                       AND e.population_kind = ?
                       AND e.legal_entity_public_id IS NOT DISTINCT FROM CAST(? AS UUID)
                     FOR UPDATE OF e
                    """, (rs, row) -> owner.execution(rs), tenantId, executionId,
                    requestedScopeDigest, populationKind, legalEntityPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("SYS008_EXECUTION_NOT_FOUND");
        }
    }

    public ExecutionResponse executionResponse(
            long tenantId, UUID executionId) {
        ExecutionRecord record;
        try {
            record = jdbc.queryForObject("""
                    SELECT e.*, d.public_id AS connector_public_id,
                           v.endpoint_uri, v.endpoint_policy_key, v.secret_ref,
                           m.mapping_ref, m.mapping_digest, m.direction,
                           m.source_schema_digest, m.target_schema_digest,
                           receipt.correlation_id,
                           receipt.idempotency_key AS owner_idempotency_key
                      FROM sys_connector_executions e
                      JOIN sys_connector_definitions d
                        ON d.tenant_id = e.tenant_id
                       AND d.definition_id = e.definition_id
                      JOIN sys_connector_mapping_versions m
                        ON m.tenant_id = e.tenant_id
                       AND m.mapping_version_id = e.mapping_version_id
                      JOIN sys_connector_definition_versions v
                        ON v.tenant_id = e.tenant_id
                       AND v.definition_version_id = e.definition_version_id
                      JOIN sys_hris_command_receipts receipt
                        ON receipt.tenant_id = e.tenant_id
                       AND receipt.public_id = e.original_command_receipt_id
                     WHERE e.tenant_id = ? AND e.public_id = ?
                    """, (rs, row) -> owner.execution(rs), tenantId, executionId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("SYS008_EXECUTION_NOT_FOUND");
        }
        return new ExecutionResponse(record.executionId(),
                record.mappingVersion(), record.state(), record.sourceCount(),
                record.acceptedCount(), record.rejectedCount(),
                record.selectedItemCount(), record.selectedItemDigest(),
                record.reconciliationDigest(), record.aggregateRevision());
    }

    public ExecutionResponse executionResponseAuthorized(
            long tenantId,
            UUID executionId,
            String requestedScopeDigest,
            String populationKind,
            UUID legalEntityPublicId) {
        ExecutionRecord record;
        try {
            record = jdbc.queryForObject("""
                    SELECT e.*, d.public_id AS connector_public_id,
                           v.endpoint_uri, v.endpoint_policy_key, v.secret_ref,
                           m.mapping_ref, m.mapping_digest, m.direction,
                           m.source_schema_digest, m.target_schema_digest,
                           receipt.correlation_id,
                           receipt.idempotency_key AS owner_idempotency_key
                      FROM sys_connector_executions e
                      JOIN sys_connector_definitions d
                        ON d.tenant_id = e.tenant_id
                       AND d.definition_id = e.definition_id
                      JOIN sys_connector_mapping_versions m
                        ON m.tenant_id = e.tenant_id
                       AND m.mapping_version_id = e.mapping_version_id
                      JOIN sys_connector_definition_versions v
                        ON v.tenant_id = e.tenant_id
                       AND v.definition_version_id = e.definition_version_id
                      JOIN sys_hris_command_receipts receipt
                        ON receipt.tenant_id = e.tenant_id
                       AND receipt.public_id = e.original_command_receipt_id
                     WHERE e.tenant_id = ? AND e.public_id = ?
                       AND e.requested_scope_digest = ?
                       AND e.population_kind = ?
                       AND e.legal_entity_public_id IS NOT DISTINCT FROM CAST(? AS UUID)
                    """, (rs, row) -> owner.execution(rs), tenantId, executionId,
                    requestedScopeDigest, populationKind, legalEntityPublicId);
        } catch (EmptyResultDataAccessException missing) {
            throw notFound("SYS008_EXECUTION_NOT_FOUND");
        }
        return new ExecutionResponse(record.executionId(),
                record.mappingVersion(), record.state(), record.sourceCount(),
                record.acceptedCount(), record.rejectedCount(),
                record.selectedItemCount(), record.selectedItemDigest(),
                record.reconciliationDigest(), record.aggregateRevision());
    }

    public ExecutionRecord beginDispatch(
            ExecutionRecord current,
            long expectedFence,
            OffsetDateTime now) {
        if (current.attemptFence() != expectedFence) {
            throw conflict("SYS008_STALE_ATTEMPT_FENCE");
        }
        int transforming = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'TRANSFORMING',
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = 'ACCEPTED' AND object_revision = ?
                   AND current_attempt_fence = ?
                """, now, current.tenantId(), current.internalId(),
                current.aggregateRevision(), expectedFence);
        if (transforming != 1) throw conflict("SYS008_DISPATCH_CAS_CONFLICT");
        if (current.retryGeneration() == 0) {
            List<InitialItem> items = jdbc.query("""
                    SELECT i.execution_item_id, i.source_digest,
                           a.provider_idempotency_key, a.attempt_fence
                      FROM sys_connector_execution_items i
                      JOIN sys_connector_execution_attempts a
                        ON a.tenant_id = i.tenant_id
                       AND a.execution_item_id = i.execution_item_id
                       AND a.attempt_number = 1
                       AND a.lifecycle_state = 'READY'
                     WHERE i.tenant_id = ? AND i.execution_id = ?
                       AND i.lifecycle_state = 'PENDING'
                     ORDER BY i.item_ordinal
                     FOR UPDATE OF i, a
                    """, (rs, row) -> new InitialItem(
                    uuid(rs, "execution_item_id"),
                    rs.getString("source_digest"),
                    rs.getString("provider_idempotency_key"),
                    rs.getLong("attempt_fence")),
                    current.tenantId(), current.internalId());
            if (items.size() != current.sourceCount()) {
                throw conflict("SYS008_INITIAL_SOURCE_SET_CHANGED");
            }
            for (InitialItem item : items) {
                String transformedDigest = canonical.digest(
                        item.sourceDigest(), current.mappingDigest());
                int transformedItem = jdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'TRANSFORMED',
                               transformed_digest = ?,
                               object_revision = object_revision + 1,
                               updated_at = ?
                         WHERE tenant_id = ? AND execution_item_id = ?
                           AND lifecycle_state = 'PENDING'
                        """, transformedDigest, now, current.tenantId(),
                        item.itemId());
                if (transformedItem != 1) {
                    throw conflict("SYS008_INITIAL_ITEM_TRANSFORM_CONFLICT");
                }
                int dispatchedItem = jdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'DISPATCHED',
                               provider_item_key = ?,
                               latest_attempt_number = 1,
                               latest_attempt_fence = ?,
                               object_revision = object_revision + 1,
                               updated_at = ?
                         WHERE tenant_id = ? AND execution_item_id = ?
                           AND lifecycle_state = 'TRANSFORMED'
                        """, item.providerIdempotencyKey(), item.attemptFence(),
                        now, current.tenantId(), item.itemId());
                if (dispatchedItem != 1) {
                    throw conflict("SYS008_INITIAL_ITEM_DISPATCH_CONFLICT");
                }
            }
        }
        int dispatched = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'DISPATCHED',
                       unresolved_external_effect = TRUE,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = 'TRANSFORMING'
                   AND object_revision = ?
                   AND current_attempt_fence = ?
                """, now, current.tenantId(), current.internalId(),
                current.aggregateRevision() + 1, expectedFence);
        if (dispatched != 1) throw conflict("SYS008_DISPATCH_FENCE_CONFLICT");
        int dispatchedAttempts = jdbc.update("""
                UPDATE sys_connector_execution_attempts
                   SET lifecycle_state = 'DISPATCHED', dispatched_at = ?,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = 'READY'
                """, now, now, current.tenantId(), current.internalId());
        if (dispatchedAttempts < 1) {
            throw conflict("SYS008_READY_ATTEMPT_REQUIRED");
        }
        return lockExecution(current.tenantId(), current.executionId());
    }

    public List<AttemptRecord> currentAttempts(ExecutionRecord current) {
        List<AttemptRecord> attempts = jdbc.query("""
                SELECT a.execution_attempt_id, a.execution_item_id,
                       i.public_id AS item_public_id, a.attempt_number,
                       a.attempt_fence, a.provider_idempotency_key,
                       a.request_digest
                  FROM sys_connector_execution_attempts a
                  JOIN sys_connector_execution_items i
                    ON i.tenant_id = a.tenant_id
                   AND i.execution_item_id = a.execution_item_id
                   AND i.latest_attempt_number = a.attempt_number
                   AND i.latest_attempt_fence = a.attempt_fence
                 WHERE a.tenant_id = ? AND a.execution_id = ?
                   AND i.lifecycle_state IN (
                       'DISPATCHED', 'RESULT_UNKNOWN', 'ACCEPTED',
                       'REJECTED_RETRYABLE')
                   AND a.lifecycle_state IN (
                       'DISPATCHED', 'RESULT_UNKNOWN', 'ACKNOWLEDGED',
                       'RECONCILED', 'FAILED')
                 ORDER BY a.attempt_fence
                 FOR UPDATE OF a, i
                """, (rs, row) -> new AttemptRecord(
                uuid(rs, "execution_attempt_id"),
                uuid(rs, "execution_item_id"), uuid(rs, "item_public_id"),
                rs.getInt("attempt_number"), rs.getLong("attempt_fence"),
                rs.getString("provider_idempotency_key"),
                rs.getString("request_digest")),
                current.tenantId(), current.internalId());
        if (attempts.isEmpty()) {
            throw conflict("SYS008_CURRENT_ATTEMPT_REQUIRED");
        }
        return List.copyOf(attempts);
    }

    public ExecutionRecord transitionExecution(
            ExecutionRecord current,
            ExecutionState target,
            OffsetDateTime now) {
        IntegrationDomainPolicy.requireTransition(current.state(), target);
        int changed = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = ?, object_revision = object_revision + 1,
                       updated_at = ?, completed_at = CASE
                           WHEN ? IN ('RECONCILED', 'DEAD_LETTER', 'CANCELLED')
                           THEN ? ELSE completed_at END
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = ? AND object_revision = ?
                """, target.name(), now, target.name(), now,
                current.tenantId(), current.internalId(), current.state().name(),
                current.aggregateRevision());
        if (changed != 1) throw conflict("SYS008_EXECUTION_CAS_CONFLICT");
        return lockExecution(current.tenantId(), current.executionId());
    }
}
