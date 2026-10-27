package com.dwp.services.platform.hrisconfiguration.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.ExecutionState;
import static org.assertj.core.api.Assertions.assertThat;

/** Inherited direct-SQL transition and retry evidence for SYS-008. */
abstract class IntegrationDatabaseTransitionEvidence extends IntegrationDatabaseEvidenceFixture {

    @Test
    void directSqlRejectsSnapshotMutationInvalidChildStateStaleFenceAndCancelBypass() {
        Graph accepted = seedGraph(cleanJdbc, 82201, "direct-accepted", false);
        UUID nullableDefinitionVersion = uuid(
                "direct-accepted-nullable-definition-version");
        cleanJdbc.update("""
                INSERT INTO sys_connector_definition_versions (
                    tenant_id, definition_version_id, definition_id,
                    version_number, connector_type, endpoint_uri,
                    endpoint_policy_key, endpoint_policy_revision, secret_ref,
                    secret_revision, definition_digest, author_actor_id,
                    author_scope_digest, created_at, updated_at)
                VALUES (?, ?, ?, 2, 'HTTPS', 'https://api.example.com/hris-v2',
                        'egress.sys008', 1, 'secret_abcdefghijklmnop', 1,
                        repeat('a', 64), ?, repeat('b', 64),
                        statement_timestamp(), statement_timestamp())
                """, accepted.tenantId(), nullableDefinitionVersion,
                accepted.definitionId(), accepted.tenantId() + 10);
        assertRejected("ck_sys_connector_definition_validation_evidence", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_definition_versions
                           SET lifecycle_state = 'VALIDATED',
                               validation_receipt_id = gen_random_uuid(),
                               validation_evidence_digest = NULL,
                               validated_by_actor_id = ?,
                               validated_at = statement_timestamp(),
                               object_revision = 2,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND definition_version_id = ?
                        """, accepted.tenantId() + 10, accepted.tenantId(),
                        nullableDefinitionVersion));
        UUID nullableMapping = uuid("direct-accepted-nullable-mapping");
        cleanJdbc.update("""
                INSERT INTO sys_connector_mapping_versions (
                    tenant_id, mapping_version_id, definition_id, mapping_key,
                    version_number, direction, source_schema_version,
                    source_schema_digest, target_schema_version,
                    target_schema_digest, mapping_ref, mapping_digest,
                    compatibility_mode, effective_from, author_actor_id,
                    author_scope_digest, created_at, updated_at)
                VALUES (?, ?, ?, 'default', 2, 'OUTBOUND', 1, repeat('5', 64),
                        1, repeat('6', 64), 'ref_nullable_mapping_0001',
                        repeat('7', 64), 'FULL', statement_timestamp(), ?,
                        repeat('8', 64), statement_timestamp(), statement_timestamp())
                """, accepted.tenantId(), nullableMapping,
                accepted.definitionId(), accepted.tenantId() + 10);
        assertRejected("ck_sys_connector_mapping_dry_run_evidence", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_mapping_versions
                           SET dry_run_receipt_id = gen_random_uuid(),
                               dry_run_result_ref = 'result/dry-run/nullable',
                               dry_run_result_digest = NULL,
                               dry_run_evidence_digest = repeat('9', 64),
                               dry_run_source_count = NULL,
                               dry_run_rejected_count = NULL,
                               dry_run_by_actor_id = ?,
                               dry_run_at = statement_timestamp(),
                               object_revision = 2,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND mapping_version_id = ?
                        """, accepted.tenantId() + 10, accepted.tenantId(),
                        nullableMapping));
        assertRejected("SYS008_DEFINITION_VERSION_SUBJECT_IMMUTABLE", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_definition_versions
                           SET endpoint_uri = 'https://substitution.example/hris',
                               object_revision = object_revision + 1
                         WHERE tenant_id = ? AND definition_version_id = ?
                        """, accepted.tenantId(), accepted.definitionVersionId()));
        assertRejected("SYS008_EXECUTION_ACCEPTANCE_SNAPSHOT_IMMUTABLE", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_executions
                           SET source_count = 2,
                               object_revision = object_revision + 1
                         WHERE tenant_id = ? AND execution_id = ?
                        """, accepted.tenantId(), accepted.executionId()));
        UUID mismatchReceipt = uuid("direct-accepted-policy-mismatch-receipt");
        insertExecutionReceipt(cleanJdbc, accepted.tenantId(), mismatchReceipt,
                "direct-accepted-policy-mismatch");
        assertRejected("SYS008_APPROVED_RECEIPT_FIRST_EXECUTION_REQUIRED", () ->
                cleanJdbc.update("""
                        INSERT INTO sys_connector_executions (
                            tenant_id, execution_id, public_id, definition_id,
                            definition_version_id, definition_version_number,
                            mapping_version_id, mapping_version_number,
                            endpoint_policy_revision, secret_revision, operation,
                            lifecycle_state, payload_ref, payload_digest,
                            source_count, source_item_set_digest,
                            selected_item_count, selected_item_digest,
                            provider_key, provider_operation,
                            provider_idempotency_key, provider_request_digest,
                            authorization_revision, original_command_receipt_id,
                            requested_by_actor_id, requested_scope_digest,
                            requested_at, updated_at)
                        SELECT tenant_id, gen_random_uuid(), gen_random_uuid(),
                               definition_id, definition_version_id,
                               definition_version_number, mapping_version_id,
                               mapping_version_number, endpoint_policy_revision + 1,
                               secret_revision, operation, 'ACCEPTED', payload_ref,
                               payload_digest, source_count, source_item_set_digest,
                               selected_item_count, selected_item_digest,
                               provider_key, provider_operation,
                               'provider-policy-mismatch-0001',
                               provider_request_digest, authorization_revision, ?,
                               requested_by_actor_id, requested_scope_digest,
                               statement_timestamp(), statement_timestamp()
                          FROM sys_connector_executions
                         WHERE tenant_id = ? AND execution_id = ?
                        """, mismatchReceipt, accepted.tenantId(),
                        accepted.executionId()));
        assertRejected("SYS008_EXECUTION_INITIAL_STATE_REJECTED", () ->
                cleanJdbc.update("""
                        INSERT INTO sys_connector_executions (
                            tenant_id, execution_id, public_id, definition_id,
                            definition_version_id, definition_version_number,
                            mapping_version_id, mapping_version_number,
                            endpoint_policy_revision, secret_revision, operation,
                            lifecycle_state, payload_ref, payload_digest,
                            source_count, source_item_set_digest,
                            selected_item_count, selected_item_digest,
                            provider_key, provider_operation,
                            provider_idempotency_key, provider_request_digest,
                            provider_receipt_digest, authorization_revision,
                            original_command_receipt_id, requested_by_actor_id,
                            requested_scope_digest, requested_at, updated_at)
                        SELECT tenant_id, gen_random_uuid(), gen_random_uuid(),
                               definition_id, definition_version_id,
                               definition_version_number, mapping_version_id,
                               mapping_version_number, endpoint_policy_revision,
                               secret_revision, operation, 'ACCEPTED', payload_ref,
                               payload_digest, source_count, source_item_set_digest,
                               selected_item_count, selected_item_digest,
                               provider_key, provider_operation,
                               'provider-orphan-evidence-0001',
                               provider_request_digest, repeat('1', 64),
                               authorization_revision, ?, requested_by_actor_id,
                               requested_scope_digest, statement_timestamp(),
                               statement_timestamp()
                          FROM sys_connector_executions
                         WHERE tenant_id = ? AND execution_id = ?
                        """, mismatchReceipt, accepted.tenantId(),
                        accepted.executionId()));
        assertRejected("SYS008_APPROVED_RECEIPT_FIRST_EXECUTION_REQUIRED", () ->
                cleanJdbc.update("""
                        INSERT INTO sys_connector_executions (
                            tenant_id, execution_id, public_id, definition_id,
                            definition_version_id, definition_version_number,
                            mapping_version_id, mapping_version_number,
                            endpoint_policy_revision, secret_revision, operation,
                            lifecycle_state, payload_ref, payload_digest,
                            source_count, source_item_set_digest,
                            selected_item_count, selected_item_digest,
                            provider_key, provider_operation,
                            provider_idempotency_key, provider_request_digest,
                            authorization_revision, original_command_receipt_id,
                            requested_by_actor_id, requested_scope_digest,
                            requested_at, updated_at)
                        SELECT tenant_id, gen_random_uuid(), gen_random_uuid(),
                               definition_id, definition_version_id,
                               definition_version_number, mapping_version_id,
                               mapping_version_number, endpoint_policy_revision,
                               secret_revision, operation, 'ACCEPTED', payload_ref,
                               payload_digest, source_count, source_item_set_digest,
                               selected_item_count, selected_item_digest,
                               provider_key, provider_operation,
                               'provider-missing-receipt-0001',
                               provider_request_digest, authorization_revision,
                               gen_random_uuid(), requested_by_actor_id,
                               requested_scope_digest, statement_timestamp(),
                               statement_timestamp()
                          FROM sys_connector_executions
                         WHERE tenant_id = ? AND execution_id = ?
                        """, accepted.tenantId(), accepted.executionId()));
        assertRejected("SYS008_EXECUTION_ITEM_TRANSITION_REJECTED", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'ACCEPTED',
                               object_revision = object_revision + 1
                         WHERE tenant_id = ? AND execution_item_id = ?
                        """, accepted.tenantId(), accepted.executionItemId()));
        assertRejected("SYS008_EXECUTION_ITEM_PARENT_SNAPSHOT_REJECTED", () ->
                cleanJdbc.update("""
                        INSERT INTO sys_connector_execution_items (
                            tenant_id, execution_item_id, public_id, execution_id,
                            item_ordinal, source_key_digest, source_digest,
                            mapping_version_id, mapping_version_number)
                        VALUES (?, gen_random_uuid(), gen_random_uuid(), ?, 1,
                                repeat('1', 64), repeat('2', 64),
                                gen_random_uuid(), 99)
                        """, accepted.tenantId(), accepted.executionId()));
        cleanJdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'TRANSFORMED',
                       transformed_digest = repeat('3', 64),
                       object_revision = object_revision + 1,
                       updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_item_id = ?
                """, accepted.tenantId(), accepted.executionItemId());
        assertRejected("SYS008_EXECUTION_ITEM_ATTEMPT_BINDING_REJECTED", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'DISPATCHED',
                               provider_item_key = 'attemptless-provider-item',
                               object_revision = object_revision + 1,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_item_id = ?
                        """, accepted.tenantId(), accepted.executionItemId()));
        assertRejected("ck_sys_connector_execution_compensation", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_executions
                           SET lifecycle_state = 'CANCELLED',
                               compensation_policy_digest = repeat('2', 64),
                               completed_at = GREATEST(
                                   statement_timestamp(), requested_at),
                               object_revision = object_revision + 1,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_id = ?
                        """, accepted.tenantId(), accepted.executionId()));

        Graph dispatched = seedGraph(cleanJdbc, 82202, "direct-dispatched", true);
        assertRejected("SYS008_EXECUTION_ATTEMPT_EVIDENCE_IMMUTABLE", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_execution_attempts
                           SET provider_receipt_ref = 'ref_preseed_attempt_0001',
                               provider_receipt_digest = repeat('1', 64),
                               response_digest = repeat('2', 64),
                               object_revision = object_revision + 1,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_attempt_id = ?
                        """, dispatched.tenantId(),
                        dispatched.executionAttemptId()));
        Graph staleRetry = seedGraph(
                cleanJdbc, 82210, "direct-stale-retry", false);
        prepareFailedRetryableGraph(cleanJdbc, staleRetry);
        assertRejected("SYS008_STALE_ATTEMPT_FENCE_REJECTED", () ->
                cleanJdbc.update("""
                        INSERT INTO sys_connector_execution_attempts (
                            tenant_id, execution_attempt_id, public_id, execution_id,
                            execution_item_id, attempt_number, attempt_fence,
                            execution_revision_snapshot, provider_idempotency_key,
                            request_digest)
                        VALUES (?, gen_random_uuid(), gen_random_uuid(), ?, ?, 1, 0,
                                3, 'stale-attempt-key-0001', repeat('4', 64))
                        """, staleRetry.tenantId(), staleRetry.executionId(),
                        staleRetry.executionItemId()));
        assertRejected("SYS008_POST_DISPATCH_COMPENSATION_REQUIRED", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_executions
                           SET lifecycle_state = 'CANCELLED',
                               unresolved_external_effect = FALSE,
                               completed_at = GREATEST(
                                   statement_timestamp(), requested_at),
                               object_revision = object_revision + 1,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_id = ?
                        """, dispatched.tenantId(), dispatched.executionId()));

        Graph terminal = seedGraph(cleanJdbc, 82203, "direct-terminal", false);
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'CANCELLED', completed_at = GREATEST(
                       statement_timestamp(), requested_at),
                       object_revision = 2, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, terminal.tenantId(), terminal.executionId());
        assertRejected("SYS008_EXECUTION_ITEM_PARENT_SNAPSHOT_REJECTED", () ->
                cleanJdbc.update("""
                        INSERT INTO sys_connector_execution_items (
                            tenant_id, execution_item_id, public_id, execution_id,
                            item_ordinal, source_key_digest, source_digest,
                            mapping_version_id, mapping_version_number)
                        VALUES (?, gen_random_uuid(), gen_random_uuid(), ?, 1,
                                repeat('1', 64), repeat('2', 64), ?, 1)
                        """, terminal.tenantId(), terminal.executionId(),
                        terminal.mappingVersionId()));
        assertRejected("SYS008_EXECUTION_ATTEMPT_PARENT_STATE_REJECTED", () ->
                cleanJdbc.update("""
                        INSERT INTO sys_connector_execution_attempts (
                            tenant_id, execution_attempt_id, public_id, execution_id,
                            execution_item_id, attempt_number, attempt_fence,
                            execution_revision_snapshot, provider_idempotency_key,
                            request_digest)
                        VALUES (?, gen_random_uuid(), gen_random_uuid(), ?, ?, 1, 1,
                                2, 'terminal-attempt-key-0001', repeat('3', 64))
                        """, terminal.tenantId(), terminal.executionId(),
                        terminal.executionItemId()));

        Graph finalClassification = seedGraph(
                cleanJdbc, 82209, "direct-final-classification", false);
        assertRejected("SYS008_FINAL_ITEM_CLASSIFICATION_REQUIRED", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'REJECTED_FINAL',
                               object_revision = object_revision + 1,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_item_id = ?
                        """, finalClassification.tenantId(),
                        finalClassification.executionItemId()));

        Graph retry = seedGraph(cleanJdbc, 82204, "direct-retry", false);
        cleanJdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'REJECTED_RETRYABLE',
                       failure_class = 'PROVIDER_RETRYABLE', retryable_snapshot = TRUE,
                       object_revision = 2, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_item_id = ?
                """, retry.tenantId(), retry.executionItemId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'TRANSFORMING', object_revision = 2,
                       updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, retry.tenantId(), retry.executionId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'FAILED', object_revision = 3,
                       updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, retry.tenantId(), retry.executionId());
        assertRejected("SYS008_FAILED_ONLY_RETRY_SET_REJECTED", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_executions
                           SET lifecycle_state = 'ACCEPTED', retry_generation = 1,
                               selected_item_count = 1,
                               selected_item_digest = repeat('4', 64),
                               current_attempt_fence = 1, object_revision = 4,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_id = ?
                        """, retry.tenantId(), retry.executionId()));
        JdbcIntegrationRepository retryRepository = new JdbcIntegrationRepository(
                cleanJdbc, new ObjectMapper().findAndRegisterModules());
        String exactRetryDigest = IntegrationDomainPolicy.sha256(
                List.of(retry.executionItemPublicId()).toString());
        var retried = cleanTransactions.execute(ignored -> {
            var failed = retryRepository.lockExecution(
                    retry.tenantId(), retry.executionPublicId());
            return retryRepository.createRetryAttempts(
                    failed, List.of(retry.executionItemPublicId()),
                    exactRetryDigest, retry.tenantId() + 20,
                    OffsetDateTime.now(ZoneOffset.UTC));
        });
        assertThat(retried.state()).isEqualTo(ExecutionState.ACCEPTED);
        assertThat(retried.selectedItemCount()).isEqualTo(1);
        assertThat(retried.attemptFence()).isEqualTo(1);

        Graph multi = seedGraph(cleanJdbc, 82206, "direct-multi-retry", false, 2);
        cleanJdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'REJECTED_RETRYABLE',
                       failure_class = 'PROVIDER_RETRYABLE', retryable_snapshot = TRUE,
                       object_revision = 2, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, multi.tenantId(), multi.executionId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'TRANSFORMING', object_revision = 2,
                       updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, multi.tenantId(), multi.executionId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'FAILED', object_revision = 3,
                       updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, multi.tenantId(), multi.executionId());
        assertRejected("SYS008_RETRYABLE_ITEM_FINALIZATION_REJECTED", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'REJECTED_FINAL',
                               object_revision = object_revision + 1,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_item_id = ?
                        """, multi.tenantId(), multi.executionItemId()));
        List<UUID> multiItemIds = List.of(
                multi.executionItemPublicId(),
                multi.secondExecutionItemPublicId()).stream().sorted().toList();
        String multiDigest = IntegrationDomainPolicy.sha256(
                multiItemIds.toString());
        var multiRetried = cleanTransactions.execute(ignored -> {
            var failedMulti = retryRepository.lockExecution(
                    multi.tenantId(), multi.executionPublicId());
            return retryRepository.createRetryAttempts(
                    failedMulti, multiItemIds, multiDigest,
                    multi.tenantId() + 20, OffsetDateTime.now(ZoneOffset.UTC));
        });
        assertThat(multiRetried.attemptFence()).isEqualTo(2);
        var multiDispatched = cleanTransactions.execute(ignored ->
                retryRepository.beginDispatch(
                        retryRepository.lockExecution(
                                multi.tenantId(), multi.executionPublicId()),
                        2, OffsetDateTime.now(ZoneOffset.UTC)));
        assertThat(multiDispatched.state()).isEqualTo(ExecutionState.DISPATCHED);
        assertThat(multiDispatched.attemptFence()).isEqualTo(2);
        assertThat(cleanJdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_connector_execution_attempts
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = 'DISPATCHED'
                """, Integer.class, multi.tenantId(), multi.executionId()))
                .isEqualTo(2);

        Graph deadLetter = seedGraph(
                cleanJdbc, 82205, "direct-dead-letter", false);
        cleanJdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'REJECTED_RETRYABLE',
                       failure_class = 'PROVIDER_RETRYABLE', retryable_snapshot = TRUE,
                       object_revision = 2, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_item_id = ?
                """, deadLetter.tenantId(), deadLetter.executionItemId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'TRANSFORMING', object_revision = 2,
                       updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, deadLetter.tenantId(), deadLetter.executionId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'FAILED', object_revision = 3,
                       updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, deadLetter.tenantId(), deadLetter.executionId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'DEAD_LETTER', completed_at = GREATEST(
                       statement_timestamp(), requested_at),
                       object_revision = 4, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, deadLetter.tenantId(), deadLetter.executionId());
        assertThat(cleanJdbc.queryForObject("""
                SELECT lifecycle_state FROM sys_connector_execution_items
                 WHERE tenant_id = ? AND execution_item_id = ?
                """, String.class, deadLetter.tenantId(),
                deadLetter.executionItemId()))
                .isEqualTo("REJECTED_FINAL");
    }

    @Test
    void retryGenerationAdvancesProviderAndReconciliationEvidenceWithoutMutationBypass() {
        Graph graph = seedGraph(cleanJdbc, 82207, "two-generation", true);

        cleanJdbc.update("""
                UPDATE sys_connector_execution_attempts
                   SET lifecycle_state = 'DISPATCHED',
                       dispatched_at = statement_timestamp(),
                       object_revision = 2, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_attempt_id = ?
                """, graph.tenantId(), graph.executionAttemptId());
        cleanJdbc.update("""
                UPDATE sys_connector_execution_attempts
                   SET lifecycle_state = 'RESULT_UNKNOWN',
                       provider_receipt_ref = 'ref_first_provider_0001',
                       provider_receipt_digest = repeat('1', 64),
                       response_digest = repeat('2', 64),
                       object_revision = 3, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_attempt_id = ?
                """, graph.tenantId(), graph.executionAttemptId());
        cleanJdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'RESULT_UNKNOWN',
                       provider_receipt_ref = 'ref_first_provider_0001',
                       provider_receipt_digest = repeat('1', 64),
                       provider_evidence_digest = repeat('2', 64),
                       object_revision = 4, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_item_id = ?
                """, graph.tenantId(), graph.executionItemId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'RESULT_UNKNOWN',
                       provider_receipt_ref = 'ref_first_provider_0001',
                       provider_receipt_digest = repeat('1', 64),
                       provider_evidence_digest = repeat('2', 64),
                       unresolved_external_effect = TRUE,
                       object_revision = 4, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, graph.tenantId(), graph.executionId());
        assertRejected("SYS008_EXECUTION_EVIDENCE_IMMUTABLE", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_executions
                           SET lifecycle_state = 'RECONCILING',
                               provider_receipt_ref =
                                   'ref_same_generation_wrong_01',
                               provider_receipt_digest = repeat('9', 64),
                               reconciliation_receipt_ref =
                                   'ref_same_generation_wrong_01',
                               reconciliation_receipt_digest = repeat('9', 64),
                               object_revision = 5,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_id = ?
                        """, graph.tenantId(), graph.executionId()));
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'RECONCILING',
                       reconciliation_receipt_ref = 'ref_first_provider_0001',
                       reconciliation_receipt_digest = repeat('1', 64),
                       object_revision = 5, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, graph.tenantId(), graph.executionId());
        assertRejected("SYS008_EXECUTION_ATTEMPT_EVIDENCE_IMMUTABLE", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_execution_attempts
                           SET lifecycle_state = 'FAILED',
                               provider_receipt_ref =
                                   'ref_same_generation_wrong_02',
                               provider_receipt_digest = repeat('9', 64),
                               reconciliation_digest = repeat('4', 64),
                               failure_class = 'PROVIDER_RETRYABLE',
                               completed_at = statement_timestamp(),
                               object_revision = 4,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_attempt_id = ?
                        """, graph.tenantId(), graph.executionAttemptId()));
        assertRejected("SYS008_EXECUTION_ITEM_EVIDENCE_IMMUTABLE", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'REJECTED_RETRYABLE',
                               provider_receipt_ref =
                                   'ref_same_generation_wrong_03',
                               provider_receipt_digest = repeat('9', 64),
                               reconciliation_digest = repeat('4', 64),
                               failure_class = 'PROVIDER_RETRYABLE',
                               retryable_snapshot = TRUE,
                               object_revision = 5,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_item_id = ?
                        """, graph.tenantId(), graph.executionItemId()));
        cleanJdbc.update("""
                UPDATE sys_connector_execution_attempts
                   SET lifecycle_state = 'FAILED',
                       reconciliation_digest = repeat('4', 64),
                       failure_class = 'PROVIDER_RETRYABLE',
                       completed_at = statement_timestamp(),
                       object_revision = 4, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_attempt_id = ?
                """, graph.tenantId(), graph.executionAttemptId());
        cleanJdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'REJECTED_RETRYABLE',
                       reconciliation_digest = repeat('4', 64),
                       failure_class = 'PROVIDER_RETRYABLE',
                       retryable_snapshot = TRUE,
                       object_revision = 5, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_item_id = ?
                """, graph.tenantId(), graph.executionItemId());
        assertRejected("SYS008_RECONCILIATION_CHILD_COUNTS_REJECTED", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_executions
                           SET lifecycle_state = 'PARTIAL', accepted_count = 0,
                               rejected_count = 1, quarantined_count = 1,
                               reconciliation_digest = repeat('4', 64),
                               unresolved_external_effect = FALSE,
                               object_revision = 6,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_id = ?
                        """, graph.tenantId(), graph.executionId()));
        assertRejected("SYS008_RECONCILIATION_CHILD_COUNTS_REJECTED", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_executions
                           SET lifecycle_state = 'PARTIAL', accepted_count = 0,
                               rejected_count = 1, quarantined_count = 0,
                               reconciliation_digest = repeat('5', 64),
                               unresolved_external_effect = FALSE,
                               object_revision = 6,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_id = ?
                        """, graph.tenantId(), graph.executionId()));
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'PARTIAL', accepted_count = 0,
                       rejected_count = 1, quarantined_count = 0,
                       reconciliation_digest = repeat('4', 64),
                       unresolved_external_effect = FALSE,
                       object_revision = 6, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, graph.tenantId(), graph.executionId());

        JdbcIntegrationRepository repository = new JdbcIntegrationRepository(
                cleanJdbc, new ObjectMapper().findAndRegisterModules());
        String failedSetDigest = IntegrationDomainPolicy.sha256(
                List.of(graph.executionItemPublicId()).toString());
        var retried = cleanTransactions.execute(ignored ->
                repository.createRetryAttempts(
                        repository.lockExecution(
                                graph.tenantId(), graph.executionPublicId()),
                        List.of(graph.executionItemPublicId()), failedSetDigest,
                        graph.tenantId() + 20,
                        OffsetDateTime.now(ZoneOffset.UTC)));
        assertThat(retried.retryGeneration()).isEqualTo(1);
        assertThat(retried.attemptFence()).isEqualTo(2);
        var dispatched = cleanTransactions.execute(ignored ->
                repository.beginDispatch(
                        repository.lockExecution(
                                graph.tenantId(), graph.executionPublicId()),
                        2, OffsetDateTime.now(ZoneOffset.UTC)));
        assertThat(dispatched.state()).isEqualTo(ExecutionState.DISPATCHED);
        assertThat(dispatched.attemptFence()).isEqualTo(2);

        UUID secondAttempt = cleanJdbc.queryForObject("""
                SELECT execution_attempt_id
                  FROM sys_connector_execution_attempts
                 WHERE tenant_id = ? AND execution_id = ? AND attempt_number = 2
                """, UUID.class, graph.tenantId(), graph.executionId());
        assertThat(cleanJdbc.queryForObject("""
                SELECT lifecycle_state FROM sys_connector_execution_attempts
                 WHERE tenant_id = ? AND execution_attempt_id = ?
                """, String.class, graph.tenantId(), secondAttempt))
                .isEqualTo("DISPATCHED");
        cleanJdbc.update("""
                UPDATE sys_connector_execution_attempts
                   SET lifecycle_state = 'RESULT_UNKNOWN',
                       response_digest = repeat('6', 64),
                       object_revision = 3, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_attempt_id = ?
                """, graph.tenantId(), secondAttempt);
        cleanJdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'RESULT_UNKNOWN',
                       provider_evidence_digest = repeat('6', 64),
                       object_revision = 7, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_item_id = ?
                """, graph.tenantId(), graph.executionItemId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'RESULT_UNKNOWN',
                       provider_evidence_digest = repeat('6', 64),
                       unresolved_external_effect = TRUE,
                       object_revision = 10, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, graph.tenantId(), graph.executionId());
        assertRejected("SYS008_EXECUTION_EVIDENCE_IMMUTABLE", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_executions
                           SET provider_receipt_ref = 'ref_illegal_overwrite_001',
                               provider_receipt_digest = repeat('7', 64),
                               object_revision = object_revision + 1,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_id = ?
                        """, graph.tenantId(), graph.executionId()));
        assertRejected("SYS008_EXECUTION_ITEM_EVIDENCE_IMMUTABLE", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET provider_receipt_ref = 'ref_illegal_overwrite_002',
                               provider_receipt_digest = repeat('8', 64),
                               object_revision = object_revision + 1,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_item_id = ?
                        """, graph.tenantId(), graph.executionItemId()));
        var secondReconciling = cleanTransactions.execute(ignored ->
                repository.beginReconciliation(
                        repository.lockExecution(
                                graph.tenantId(), graph.executionPublicId()),
                        "ref_second_reconcile_0001", "9".repeat(64),
                        OffsetDateTime.now(ZoneOffset.UTC).withNano(0)));
        assertThat(secondReconciling.providerReceiptRef())
                .isEqualTo("ref_second_reconcile_0001");
        assertRejected("SYS008_EXECUTION_ATTEMPT_EVIDENCE_IMMUTABLE", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_execution_attempts
                           SET lifecycle_state = 'RECONCILED',
                               provider_receipt_ref =
                                   'ref_wrong_reconcile_attempt_01',
                               provider_receipt_digest = repeat('7', 64),
                               reconciliation_digest = repeat('a', 64),
                               completed_at = statement_timestamp(),
                               object_revision = 4,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_attempt_id = ?
                        """, graph.tenantId(), secondAttempt));
        assertRejected("SYS008_EXECUTION_ITEM_EVIDENCE_IMMUTABLE", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'RECONCILED',
                               provider_receipt_ref =
                                   'ref_wrong_reconcile_item_0001',
                               provider_receipt_digest = repeat('8', 64),
                               reconciliation_digest = repeat('a', 64),
                               failure_class = NULL, retryable_snapshot = NULL,
                               object_revision = 8,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_item_id = ?
                        """, graph.tenantId(), graph.executionItemId()));
        cleanJdbc.update("""
                UPDATE sys_connector_execution_attempts
                   SET lifecycle_state = 'RECONCILED',
                       provider_receipt_ref = 'ref_second_reconcile_0001',
                       provider_receipt_digest = repeat('9', 64),
                       reconciliation_digest = repeat('a', 64),
                       completed_at = statement_timestamp(),
                       object_revision = 4, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_attempt_id = ?
                """, graph.tenantId(), secondAttempt);
        assertRejected("SYS008_EXECUTION_ITEM_RECONCILIATION_REJECTED", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'RECONCILED',
                               reconciliation_digest = repeat('a', 64),
                               failure_class = NULL, retryable_snapshot = NULL,
                               object_revision = 8,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_item_id = ?
                        """, graph.tenantId(), graph.executionItemId()));
        cleanJdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'RECONCILED',
                       provider_receipt_ref = 'ref_second_reconcile_0001',
                       provider_receipt_digest = repeat('9', 64),
                       reconciliation_digest = repeat('a', 64),
                       failure_class = NULL, retryable_snapshot = NULL,
                       object_revision = 8, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_item_id = ?
                """, graph.tenantId(), graph.executionItemId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'RECONCILED', accepted_count = 1,
                       rejected_count = 0, quarantined_count = 0,
                       reconciliation_digest = repeat('a', 64),
                       unresolved_external_effect = FALSE,
                       completed_at = GREATEST(statement_timestamp(), requested_at),
                       object_revision = 12, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, graph.tenantId(), graph.executionId());

        assertThat(cleanJdbc.queryForMap("""
                SELECT lifecycle_state, retry_generation,
                       provider_receipt_ref, reconciliation_receipt_ref,
                       reconciliation_digest
                  FROM sys_connector_executions
                 WHERE tenant_id = ? AND execution_id = ?
                """, graph.tenantId(), graph.executionId()))
                .containsEntry("lifecycle_state", "RECONCILED")
                .containsEntry("retry_generation", 1)
                .containsEntry("provider_receipt_ref", "ref_second_reconcile_0001")
                .containsEntry("reconciliation_receipt_ref",
                        "ref_second_reconcile_0001")
                .containsEntry("reconciliation_digest", "a".repeat(64));
    }
}
