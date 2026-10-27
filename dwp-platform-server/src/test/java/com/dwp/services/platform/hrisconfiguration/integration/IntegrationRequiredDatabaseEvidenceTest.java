package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandSeal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.ExecutionState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Required clean/upgrade, RLS and direct-SQL PostgreSQL evidence for SYS-008. */
@Testcontainers(disabledWithoutDocker = true)
class IntegrationRequiredDatabaseEvidenceTest extends IntegrationDatabaseTransitionEvidence {

    @Test
    void cleanAndV333UpgradeReachV334AndPreserveSharedEvidence() {
        assertThat(currentVersion(cleanJdbc)).isEqualTo("334");
        assertThat(currentVersion(upgradeJdbc)).isEqualTo("334");
        for (JdbcTemplate database : List.of(cleanJdbc, upgradeJdbc)) {
            assertThat(database.queryForList("""
                    SELECT table_name
                      FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_name LIKE 'sys_connector_%'
                     ORDER BY table_name
                    """, String.class))
                    .containsExactlyInAnyOrderElementsOf(TABLES);
        }
        assertThat(upgradeJdbc.queryForObject("""
                SELECT public_id FROM sys_hris_command_receipts
                 WHERE tenant_id = 82001 AND public_id = ?
                """, UUID.class, legacyReceiptPublicId))
                .isEqualTo(legacyReceiptPublicId);
    }

    @Test
    void everyOwnedTableDeniesMissingContextAndCrossTenantAccess() {
        seedGraph(cleanJdbc, 82101, "rls-one", true);
        seedGraph(cleanJdbc, 82102, "rls-two", true);
        provisionApplicationRole();

        PGSimpleDataSource appSource = dataSource(
                CLEAN_DATABASE, APP_ROLE, APP_PASSWORD);
        appSource.setCurrentSchema(SCHEMA);
        JdbcTemplate appJdbc = new JdbcTemplate(appSource);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(appSource));

        TABLES.forEach(table -> assertThat(appJdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class))
                .as("missing tenant context hides %s", table).isZero());
        assertThat(cleanJdbc.queryForMap("""
                SELECT rolsuper, rolinherit, rolbypassrls
                  FROM pg_roles WHERE rolname = ?
                """, APP_ROLE))
                .containsEntry("rolsuper", false)
                .containsEntry("rolinherit", false)
                .containsEntry("rolbypassrls", false);
        TABLES.forEach(table -> {
            assertThat(cleanJdbc.queryForObject("""
                    SELECT NOT EXISTS (
                        SELECT 1
                          FROM pg_class relation
                          CROSS JOIN LATERAL aclexplode(
                              COALESCE(relation.relacl,
                                  acldefault('r', relation.relowner))) acl
                         WHERE relation.oid = to_regclass(?)
                           AND acl.grantee = 0)
                    """, Boolean.class, table))
                    .as("PUBLIC has no ACL on %s", table).isTrue();
            assertThat(cleanJdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'TRUNCATE')",
                    Boolean.class, APP_ROLE, table))
                    .as("runtime role cannot truncate %s", table).isFalse();
        });

        transactions.executeWithoutResult(ignored -> {
            appJdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', '82101', true)",
                    String.class);
            TABLES.forEach(table -> {
                assertThat(appJdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table, Integer.class))
                        .as("bound tenant sees %s", table).isOne();
                assertThat(appJdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table
                                + " WHERE tenant_id = 82102", Integer.class))
                        .as("cross tenant remains hidden in %s", table).isZero();
            });
        });

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            appJdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', '82101', true)",
                    String.class);
            appJdbc.update("""
                    INSERT INTO sys_connector_definitions (
                        tenant_id, connector_key)
                    VALUES (82102, 'cross.tenant.denied')
                    """);
        })).isInstanceOf(DataAccessException.class);
    }

    @Test
    void runtimeRoleExecutesDirectTransitionFenceReplayCompensationAndKeyNegatives() {
        provisionApplicationRole();
        long tenantId = 82110;
        Graph invalid = seedGraph(cleanJdbc, tenantId, "role-invalid", true);
        Graph terminal = seedGraph(cleanJdbc, tenantId + 1, "role-terminal", false);
        Graph success = seedGraph(cleanJdbc, tenantId + 2, "role-success", true);
        Graph stale = seedGraph(cleanJdbc, tenantId + 3, "role-stale", false);
        Graph compensation = seedGraph(
                cleanJdbc, tenantId + 4, "role-compensation", true);
        Graph crossParent = seedGraph(
                cleanJdbc, tenantId + 5, "role-cross-parent", true);
        Graph crossItem = seedGraph(
                cleanJdbc, tenantId + 5, "role-cross-item", true);
        Graph unique = seedGraph(cleanJdbc, tenantId + 6, "role-unique", false);

        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'CANCELLED',
                       completed_at = GREATEST(statement_timestamp(), requested_at),
                       object_revision = 2, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, terminal.tenantId(), terminal.executionId());
        cleanJdbc.update("""
                UPDATE sys_connector_execution_attempts
                   SET lifecycle_state = 'DISPATCHED',
                       dispatched_at = statement_timestamp(),
                       object_revision = 2, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_attempt_id = ?
                """, success.tenantId(), success.executionAttemptId());
        cleanJdbc.update("""
                UPDATE sys_connector_execution_attempts
                   SET lifecycle_state = 'ACKNOWLEDGED',
                       provider_receipt_ref = 'ref_runtime_success_00001',
                       provider_receipt_digest = repeat('1', 64),
                       response_digest = repeat('2', 64),
                       acknowledged_at = statement_timestamp(),
                       object_revision = 3, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_attempt_id = ?
                """, success.tenantId(), success.executionAttemptId());
        cleanJdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'ACCEPTED',
                       provider_receipt_ref = 'ref_runtime_success_00001',
                       provider_receipt_digest = repeat('1', 64),
                       provider_evidence_digest = repeat('2', 64),
                       object_revision = 4, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_item_id = ?
                """, success.tenantId(), success.executionItemId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'ACKNOWLEDGED',
                       provider_receipt_ref = 'ref_runtime_success_00001',
                       provider_receipt_digest = repeat('1', 64),
                       provider_evidence_digest = repeat('2', 64),
                       unresolved_external_effect = FALSE,
                       object_revision = 4, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, success.tenantId(), success.executionId());
        prepareFailedRetryableGraph(cleanJdbc, stale);

        PGSimpleDataSource appSource = dataSource(
                CLEAN_DATABASE, APP_ROLE, APP_PASSWORD);
        appSource.setCurrentSchema(SCHEMA);
        JdbcTemplate appJdbc = new JdbcTemplate(appSource);
        TransactionTemplate appTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(appSource));

        assertRuntimeRejected(appJdbc, appTransactions, invalid.tenantId(),
                "SYS008_EXECUTION_TRANSITION_REJECTED", jdbc -> jdbc.update("""
                        UPDATE sys_connector_executions
                           SET lifecycle_state = 'TRANSFORMING',
                               object_revision = 4,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_id = ?
                        """, invalid.tenantId(), invalid.executionId()));
        assertRuntimeRejected(appJdbc, appTransactions, terminal.tenantId(),
                "SYS008_TERMINAL_EXECUTION_IMMUTABLE", jdbc -> jdbc.update("""
                        UPDATE sys_connector_executions
                           SET lifecycle_state = 'ACCEPTED', completed_at = NULL,
                               object_revision = 3,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_id = ?
                        """, terminal.tenantId(), terminal.executionId()));
        assertRuntimeRejected(appJdbc, appTransactions, terminal.tenantId(),
                "SYS008_EXECUTION_DELETE_REJECTED", jdbc -> jdbc.update("""
                        DELETE FROM sys_connector_executions
                         WHERE tenant_id = ? AND execution_id = ?
                        """, terminal.tenantId(), terminal.executionId()));
        assertRuntimeRejected(appJdbc, appTransactions, unique.tenantId(),
                "SYS008_ITEM_PARENT_CANCEL_REQUIRED", jdbc -> jdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'CANCELLED',
                               object_revision = 2,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_item_id = ?
                        """, unique.tenantId(), unique.executionItemId()));
        assertRuntimeRejected(appJdbc, appTransactions, invalid.tenantId(),
                "SYS008_ATTEMPT_PARENT_CANCEL_REQUIRED", jdbc -> jdbc.update("""
                        UPDATE sys_connector_execution_attempts
                           SET lifecycle_state = 'CANCELLED',
                               completed_at = statement_timestamp(),
                               object_revision = 2,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_attempt_id = ?
                        """, invalid.tenantId(), invalid.executionAttemptId()));
        assertRuntimeRejected(appJdbc, appTransactions, success.tenantId(),
                "SYS008_SAME_KEY_RECONCILIATION_REQUIRED", jdbc -> jdbc.update("""
                        UPDATE sys_connector_executions
                           SET lifecycle_state = 'RECONCILING',
                               object_revision = 5,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_id = ?
                        """, success.tenantId(), success.executionId()));
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'RECONCILING',
                       reconciliation_receipt_ref = 'ref_runtime_success_00001',
                       reconciliation_receipt_digest = repeat('1', 64),
                       object_revision = 5, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, success.tenantId(), success.executionId());
        assertRuntimeRejected(appJdbc, appTransactions, success.tenantId(),
                "SYS008_ATTEMPT_RECONCILIATION_EVIDENCE_REQUIRED", jdbc ->
                        jdbc.update("""
                                UPDATE sys_connector_execution_attempts
                                   SET lifecycle_state = 'RECONCILED',
                                       completed_at = statement_timestamp(),
                                       object_revision = 4,
                                       updated_at = statement_timestamp()
                                 WHERE tenant_id = ? AND execution_attempt_id = ?
                                """, success.tenantId(),
                                success.executionAttemptId()));
        assertRuntimeRejected(appJdbc, appTransactions, success.tenantId(),
                "SYS008_EXECUTION_ATTEMPT_PARENT_STATE_REJECTED", jdbc ->
                        jdbc.update("""
                                INSERT INTO sys_connector_execution_attempts (
                                    tenant_id, execution_attempt_id, public_id,
                                    execution_id, execution_item_id,
                                    attempt_number, attempt_fence,
                                    execution_revision_snapshot,
                                    provider_idempotency_key, request_digest)
                                VALUES (?, gen_random_uuid(), gen_random_uuid(),
                                        ?, ?, 2, 2, 5,
                                        'role-success-replay-0001', repeat('3', 64))
                                """, success.tenantId(), success.executionId(),
                                success.executionItemId()));
        assertRuntimeRejected(appJdbc, appTransactions, stale.tenantId(),
                "SYS008_STALE_ATTEMPT_FENCE_REJECTED", jdbc -> jdbc.update("""
                        INSERT INTO sys_connector_execution_attempts (
                            tenant_id, execution_attempt_id, public_id,
                            execution_id, execution_item_id, attempt_number,
                            attempt_fence, execution_revision_snapshot,
                            provider_idempotency_key, request_digest)
                        VALUES (?, gen_random_uuid(), gen_random_uuid(), ?, ?, 1,
                                0, 3, 'role-stale-attempt-0001', repeat('4', 64))
                        """, stale.tenantId(), stale.executionId(),
                        stale.executionItemId()));
        assertRuntimeRejected(appJdbc, appTransactions, compensation.tenantId(),
                "SYS008_POST_DISPATCH_COMPENSATION_REQUIRED", jdbc -> jdbc.update("""
                        UPDATE sys_connector_executions
                           SET lifecycle_state = 'CANCELLED',
                               unresolved_external_effect = FALSE,
                               completed_at = GREATEST(
                                   statement_timestamp(), requested_at),
                               object_revision = 4,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_id = ?
                        """, compensation.tenantId(), compensation.executionId()));
        assertRuntimeRejected(appJdbc, appTransactions, crossParent.tenantId(),
                "SYS008_EXECUTION_ATTEMPT_PARENT_STATE_REJECTED", jdbc ->
                        jdbc.update("""
                                INSERT INTO sys_connector_execution_attempts (
                                    tenant_id, execution_attempt_id, public_id,
                                    execution_id, execution_item_id,
                                    attempt_number, attempt_fence,
                                    execution_revision_snapshot,
                                    provider_idempotency_key, request_digest)
                                VALUES (?, gen_random_uuid(), gen_random_uuid(),
                                        ?, ?, 2, 2, 3,
                                        'role-cross-child-000001', repeat('5', 64))
                                """, crossParent.tenantId(),
                                crossParent.executionId(), crossItem.executionItemId()));

        UUID duplicateReceipt = uuid("role-unique-duplicate-receipt");
        insertExecutionReceipt(cleanJdbc, unique.tenantId(), duplicateReceipt,
                "role-unique-duplicate");
        assertRuntimeRejected(appJdbc, appTransactions, unique.tenantId(),
                uuid("role-unique-duplicate-subject"),
                "uq_sys_connector_provider_operation_key", jdbc -> jdbc.update("""
                        INSERT INTO sys_connector_executions (
                            tenant_id, execution_id, public_id, definition_id,
                            definition_version_id, definition_version_number,
                            mapping_version_id, mapping_version_number,
                            endpoint_policy_revision, secret_revision, operation,
                            payload_ref, payload_digest, source_count,
                            source_item_set_digest, selected_item_count,
                            selected_item_digest, provider_key, provider_operation,
                            provider_idempotency_key, provider_request_digest,
                            authorization_revision, population_kind,
                            legal_entity_public_id, original_command_receipt_id,
                            requested_by_actor_id, requested_scope_digest,
                            requested_at, updated_at)
                        SELECT tenant_id, gen_random_uuid(), gen_random_uuid(),
                               definition_id, definition_version_id,
                               definition_version_number, mapping_version_id,
                               mapping_version_number, endpoint_policy_revision,
                               secret_revision, operation, payload_ref,
                               payload_digest, source_count, source_item_set_digest,
                               selected_item_count, selected_item_digest,
                               provider_key, provider_operation,
                               provider_idempotency_key, provider_request_digest,
                               authorization_revision, population_kind,
                               legal_entity_public_id, ?, requested_by_actor_id,
                               requested_scope_digest, statement_timestamp(),
                               statement_timestamp()
                          FROM sys_connector_executions
                         WHERE tenant_id = ? AND execution_id = ?
                        """, duplicateReceipt, unique.tenantId(),
                        unique.executionId()));
    }

    @Test
    void receiptlessResultUnknownRecoversOnlyThroughValidatedReconciliationEdge() {
        Graph graph = seedGraph(cleanJdbc, 82210, "receiptless-unknown", true);
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
                       response_digest = repeat('1', 64),
                       object_revision = 3, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_attempt_id = ?
                """, graph.tenantId(), graph.executionAttemptId());
        cleanJdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'RESULT_UNKNOWN',
                       provider_evidence_digest = repeat('1', 64),
                       object_revision = 4, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_item_id = ?
                """, graph.tenantId(), graph.executionItemId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'RESULT_UNKNOWN',
                       provider_evidence_digest = repeat('1', 64),
                       unresolved_external_effect = TRUE,
                       object_revision = 4, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, graph.tenantId(), graph.executionId());

        assertRejected("SYS008_EXECUTION_EVIDENCE_IMMUTABLE", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_executions
                           SET provider_receipt_ref = 'ref_off_edge_parent_0001',
                               provider_receipt_digest = repeat('2', 64),
                               object_revision = 5,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_id = ?
                        """, graph.tenantId(), graph.executionId()));
        assertRejected("SYS008_EXECUTION_ITEM_EVIDENCE_IMMUTABLE", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET provider_receipt_ref = 'ref_off_edge_item_000001',
                               provider_receipt_digest = repeat('2', 64),
                               object_revision = 5,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_item_id = ?
                        """, graph.tenantId(), graph.executionItemId()));
        assertRejected("SYS008_EXECUTION_ATTEMPT_EVIDENCE_IMMUTABLE", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_execution_attempts
                           SET provider_receipt_ref = 'ref_off_edge_attempt_0001',
                               provider_receipt_digest = repeat('2', 64),
                               object_revision = 4,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_attempt_id = ?
                        """, graph.tenantId(), graph.executionAttemptId()));

        JdbcIntegrationRepository repository = new JdbcIntegrationRepository(
                cleanJdbc, new ObjectMapper().findAndRegisterModules());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        var reconciling = cleanTransactions.execute(ignored ->
                repository.beginReconciliation(
                        repository.lockExecution(
                                graph.tenantId(), graph.executionPublicId()),
                        "ref_owner_validated_reconcile_0001", "3".repeat(64), now));
        var reconciled = cleanTransactions.execute(ignored ->
                repository.applyProviderEvidence(
                        repository.lockExecution(
                                graph.tenantId(), graph.executionPublicId()),
                        new IntegrationRuntimePorts.ProviderEvidence(
                                IntegrationRuntimePorts.ProviderEvidence.Outcome.RECONCILED,
                                reconciling.providerIdempotencyKey(),
                                reconciling.providerRequestDigest(),
                                "ref_owner_validated_reconcile_0001", "3".repeat(64),
                                1, 1, 0, 0, "4".repeat(64), now),
                        ExecutionState.RECONCILED, now));

        assertThat(reconciled.state()).isEqualTo(ExecutionState.RECONCILED);
        assertThat(reconciled.providerReceiptRef())
                .isEqualTo("ref_owner_validated_reconcile_0001");
        assertThat(cleanJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_connector_execution_items item
                  JOIN sys_connector_execution_attempts attempt
                    ON attempt.tenant_id = item.tenant_id
                   AND attempt.execution_id = item.execution_id
                   AND attempt.execution_item_id = item.execution_item_id
                   AND attempt.attempt_number = item.latest_attempt_number
                   AND attempt.attempt_fence = item.latest_attempt_fence
                 WHERE item.tenant_id = ? AND item.execution_id = ?
                   AND item.lifecycle_state = 'RECONCILED'
                   AND item.provider_receipt_ref = 'ref_owner_validated_reconcile_0001'
                   AND item.reconciliation_digest = repeat('4', 64)
                   AND attempt.lifecycle_state = 'RECONCILED'
                   AND attempt.provider_receipt_ref =
                       'ref_owner_validated_reconcile_0001'
                   AND attempt.reconciliation_digest = repeat('4', 64)
                """, Integer.class, graph.tenantId(), graph.executionId())).isOne();

        Graph mismatch = seedGraph(
                cleanJdbc, 82211, "unchanged-receipt-mismatch", true);
        cleanJdbc.update("""
                UPDATE sys_connector_execution_attempts
                   SET lifecycle_state = 'DISPATCHED',
                       dispatched_at = statement_timestamp(),
                       object_revision = 2, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_attempt_id = ?
                """, mismatch.tenantId(), mismatch.executionAttemptId());
        cleanJdbc.update("""
                UPDATE sys_connector_execution_attempts
                   SET lifecycle_state = 'RESULT_UNKNOWN',
                       provider_receipt_ref = 'ref_attempt_authority_0001',
                       provider_receipt_digest = repeat('5', 64),
                       response_digest = repeat('6', 64),
                       object_revision = 3, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_attempt_id = ?
                """, mismatch.tenantId(), mismatch.executionAttemptId());
        cleanJdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'RESULT_UNKNOWN',
                       provider_receipt_ref = 'ref_attempt_authority_0001',
                       provider_receipt_digest = repeat('5', 64),
                       provider_evidence_digest = repeat('6', 64),
                       object_revision = 4, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_item_id = ?
                """, mismatch.tenantId(), mismatch.executionItemId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'RESULT_UNKNOWN',
                       provider_receipt_ref = 'ref_stale_parent_value_0001',
                       provider_receipt_digest = repeat('7', 64),
                       provider_evidence_digest = repeat('6', 64),
                       unresolved_external_effect = TRUE,
                       object_revision = 4, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, mismatch.tenantId(), mismatch.executionId());
        assertRejected("SYS008_SAME_KEY_RECONCILIATION_REQUIRED", () ->
                cleanJdbc.update("""
                        UPDATE sys_connector_executions
                           SET lifecycle_state = 'RECONCILING',
                               reconciliation_receipt_ref =
                                   'ref_stale_parent_value_0001',
                               reconciliation_receipt_digest = repeat('7', 64),
                               object_revision = 5,
                               updated_at = statement_timestamp()
                         WHERE tenant_id = ? AND execution_id = ?
                        """, mismatch.tenantId(), mismatch.executionId()));
    }

    @Test
    void conclusiveFailedExecutionCancelsWithoutCompensation() {
        Graph graph = seedGraph(cleanJdbc, 82208, "failed-no-effect", true);
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'FAILED',
                       provider_receipt_ref = 'ref_conclusive_failure_0001',
                       provider_receipt_digest = repeat('b', 64),
                       provider_evidence_digest = repeat('c', 64),
                       provider_failure_conclusive = TRUE,
                       unresolved_external_effect = FALSE,
                       object_revision = 4, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, graph.tenantId(), graph.executionId());
        cleanJdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'CANCELLED',
                       completed_at = GREATEST(statement_timestamp(), requested_at),
                       object_revision = 5, updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, graph.tenantId(), graph.executionId());
        assertThat(cleanJdbc.queryForMap("""
                SELECT lifecycle_state, compensation_policy_ref,
                       compensation_receipt_ref
                  FROM sys_connector_executions
                 WHERE tenant_id = ? AND execution_id = ?
                """, graph.tenantId(), graph.executionId()))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("compensation_policy_ref", null)
                .containsEntry("compensation_receipt_ref", null);
    }

    @Test
    void executionEventAuditAndCasRollbackAsOneTransaction() {
        Graph fixture = seedGraph(cleanJdbc, 82301, "atomic", false);
        JdbcIntegrationRepository repository = new JdbcIntegrationRepository(
                cleanJdbc, new ObjectMapper().findAndRegisterModules());
        UUID subject = uuid("atomic-subject");
        UUID correlation = uuid("atomic-correlation");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        CommandSeal seal = new CommandSeal(
                fixture.tenantId(), subject, "RUNTIME_PROVIDER_EFFECT",
                IntegrationService.CREATE_EXECUTION, "route.sys008.atomic",
                "psc-" + "a".repeat(64), "scope:tenant:82301",
                "psr-" + "b".repeat(64), "c".repeat(64), 1, 1,
                "HRIS_INTEGRATION_ADMIN", "atomic-command-key-0001",
                "d".repeat(64), correlation);

        assertThatThrownBy(() -> cleanTransactions.executeWithoutResult(ignored -> {
            repository.bindTenantPrincipal(fixture.tenantId(), subject);
            var current = repository.lockExecution(
                    fixture.tenantId(), fixture.executionPublicId());
            var changed = repository.transitionExecution(
                    current, ExecutionState.TRANSFORMING, now);
            repository.appendExecutionEventAndAudit(
                    seal, changed, "EXECUTION_TRANSFORMING", now);
            throw new InjectedRollback();
        })).isInstanceOf(InjectedRollback.class);

        assertThat(cleanJdbc.queryForMap("""
                SELECT lifecycle_state, object_revision
                  FROM sys_connector_executions
                 WHERE tenant_id = ? AND execution_id = ?
                """, fixture.tenantId(), fixture.executionId()))
                .containsEntry("lifecycle_state", "ACCEPTED")
                .containsEntry("object_revision", 1L);
        assertThat(cleanJdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_domain_event_outbox
                 WHERE tenant_id = ? AND correlation_id = ?
                """, Integer.class, fixture.tenantId(), correlation.toString()))
                .isZero();
        assertThat(cleanJdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_audit_outbox
                 WHERE tenant_id = ? AND payload ->> 'correlationId' = ?
                """, Integer.class, fixture.tenantId(), correlation.toString()))
                .isZero();
    }
}
