package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandSeal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.ExecutionState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Required clean/upgrade, RLS and direct-SQL PostgreSQL evidence for SYS-008. */
@Testcontainers(disabledWithoutDocker = true)
class IntegrationRequiredDatabaseEvidenceTest {

    private static final String CLEAN_DATABASE = "sys008_clean_evidence";
    private static final String UPGRADE_DATABASE = "sys008_upgrade_evidence";
    private static final String SCHEMA = "public";
    private static final String APP_ROLE = "sys008_evidence_app";
    private static final String APP_PASSWORD = "sys008-evidence-password";
    private static final String CREATE_EXECUTION_ACTION =
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.executions";
    private static final List<String> TABLES = List.of(
            "sys_connector_definitions",
            "sys_connector_definition_versions",
            "sys_connector_mapping_versions",
            "sys_connector_executions",
            "sys_connector_execution_items",
            "sys_connector_execution_attempts");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource cleanSource;
    private static JdbcTemplate cleanJdbc;
    private static JdbcTemplate upgradeJdbc;
    private static TransactionTemplate cleanTransactions;
    private static UUID legacyReceiptPublicId;

    @BeforeAll
    static void migrateCleanAndUpgradePaths() {
        JdbcTemplate admin = new JdbcTemplate(dataSource(
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        admin.execute("CREATE DATABASE " + CLEAN_DATABASE);
        admin.execute("CREATE DATABASE " + UPGRADE_DATABASE);

        cleanSource = dataSource(
                CLEAN_DATABASE, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway clean = flyway(cleanSource, "334");
        clean.clean();
        clean.migrate();
        cleanSource.setCurrentSchema(SCHEMA);
        cleanJdbc = new JdbcTemplate(cleanSource);
        cleanTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(cleanSource));

        PGSimpleDataSource upgradeSource = dataSource(
                UPGRADE_DATABASE, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway throughV333 = flyway(upgradeSource, "333");
        throughV333.clean();
        throughV333.migrate();
        upgradeSource.setCurrentSchema(SCHEMA);
        upgradeJdbc = new JdbcTemplate(upgradeSource);
        legacyReceiptPublicId = insertLegacyReceipt(upgradeJdbc);
        flyway(upgradeSource, "334").migrate();
    }

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

    private static Graph seedGraph(
            JdbcTemplate jdbc,
            long tenantId,
            String key,
            boolean dispatched) {
        return seedGraph(jdbc, tenantId, key, dispatched, 1);
    }

    private static Graph seedGraph(
            JdbcTemplate jdbc,
            long tenantId,
            String key,
            boolean dispatched,
            int sourceCount) {
        UUID definitionId = uuid(key + "-definition");
        UUID connectorId = uuid(key + "-connector");
        UUID definitionVersionId = uuid(key + "-definition-version");
        UUID mappingId = uuid(key + "-mapping");
        UUID commandReceiptId = uuid(key + "-command-receipt");
        UUID executionId = uuid(key + "-execution");
        UUID executionPublicId = uuid(key + "-execution-public");
        UUID itemId = uuid(key + "-item");
        UUID itemPublicId = uuid(key + "-item-public");
        UUID secondItemId = sourceCount > 1 ? uuid(key + "-item-2") : null;
        UUID secondItemPublicId = sourceCount > 1
                ? uuid(key + "-item-public-2") : null;
        UUID attemptId = dispatched ? uuid(key + "-attempt") : null;
        long author = tenantId + 10;
        long executor = tenantId + 20;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);

        jdbc.update("""
                INSERT INTO sys_connector_definitions (
                    tenant_id, definition_id, public_id, connector_key,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenantId, definitionId, connectorId,
                "connector." + key, now, now);
        jdbc.update("""
                INSERT INTO sys_connector_definition_versions (
                    tenant_id, definition_version_id, definition_id,
                    version_number, connector_type, endpoint_uri,
                    endpoint_policy_key, endpoint_policy_revision, secret_ref,
                    secret_revision, definition_digest, author_actor_id,
                    author_scope_digest, created_at, updated_at)
                VALUES (?, ?, ?, 1, 'HTTPS', 'https://api.example.com/hris',
                        'egress.sys008', 1, 'secret_abcdefghijklmnop', 1,
                        repeat('a', 64), ?, repeat('b', 64), ?, ?)
                """, tenantId, definitionVersionId, definitionId,
                author, now, now);
        jdbc.update("""
                UPDATE sys_connector_definition_versions
                   SET lifecycle_state = 'VALIDATED',
                       validation_receipt_id = gen_random_uuid(),
                       validation_evidence_digest = repeat('c', 64),
                       validated_by_actor_id = ?, validated_at = ?,
                       object_revision = 2, updated_at = ?
                 WHERE tenant_id = ? AND definition_version_id = ?
                """, author, now, now, tenantId, definitionVersionId);
        jdbc.update("""
                UPDATE sys_connector_definition_versions
                   SET lifecycle_state = 'TESTED',
                       connection_test_receipt_id = gen_random_uuid(),
                       connection_test_result_ref = 'result/test/one',
                       connection_test_result_digest = repeat('d', 64),
                       connection_test_evidence_digest = repeat('e', 64),
                       tested_by_actor_id = ?, tested_at = ?,
                       object_revision = 3, updated_at = ?
                 WHERE tenant_id = ? AND definition_version_id = ?
                """, author, now, now, tenantId, definitionVersionId);
        jdbc.update("""
                UPDATE sys_connector_definition_versions
                   SET lifecycle_state = 'PENDING_APPROVAL',
                       submission_receipt_id = gen_random_uuid(),
                       submission_evidence_digest = repeat('f', 64),
                       submitted_by_actor_id = ?, submitted_at = ?,
                       object_revision = 4, updated_at = ?
                 WHERE tenant_id = ? AND definition_version_id = ?
                """, author, now, now, tenantId, definitionVersionId);
        jdbc.update("""
                UPDATE sys_connector_definition_versions
                   SET lifecycle_state = 'APPROVED',
                       approval_receipt_id = gen_random_uuid(),
                       approval_evidence_digest = repeat('1', 64),
                       approval_step_up_evidence_digest = repeat('2', 64),
                       approved_by_actor_id = ?, approved_at = ?,
                       object_revision = 5, updated_at = ?
                 WHERE tenant_id = ? AND definition_version_id = ?
                """, executor, now, now, tenantId, definitionVersionId);
        jdbc.update("""
                UPDATE sys_connector_definition_versions
                   SET lifecycle_state = 'ACTIVE',
                       activation_receipt_id = gen_random_uuid(),
                       activation_evidence_digest = repeat('3', 64),
                       activation_step_up_evidence_digest = repeat('4', 64),
                       activated_by_actor_id = ?, activated_at = ?,
                       object_revision = 6, updated_at = ?
                 WHERE tenant_id = ? AND definition_version_id = ?
                """, executor, now, now, tenantId, definitionVersionId);
        jdbc.update("""
                UPDATE sys_connector_definitions
                   SET lifecycle_state = 'ACTIVE', active_version_number = 1,
                       aggregate_revision = 2, updated_at = ?
                 WHERE tenant_id = ? AND definition_id = ?
                """, now, tenantId, definitionId);

        jdbc.update("""
                INSERT INTO sys_connector_mapping_versions (
                    tenant_id, mapping_version_id, definition_id, mapping_key,
                    version_number, direction, source_schema_version,
                    source_schema_digest, target_schema_version,
                    target_schema_digest, mapping_ref, mapping_digest,
                    compatibility_mode, effective_from, author_actor_id,
                    author_scope_digest, created_at, updated_at)
                VALUES (?, ?, ?, 'default', 1, 'OUTBOUND', 1, repeat('5', 64),
                        1, repeat('6', 64), 'ref_abcdefghijklmnop',
                        repeat('7', 64), 'FULL', ?, ?, repeat('8', 64), ?, ?)
                """, tenantId, mappingId, definitionId, now, author, now, now);
        jdbc.update("""
                UPDATE sys_connector_mapping_versions
                   SET lifecycle_state = 'VALIDATED',
                       validation_receipt_id = gen_random_uuid(),
                       validation_evidence_digest = repeat('9', 64),
                       validated_by_actor_id = ?, validated_at = ?,
                       object_revision = 2, updated_at = ?
                 WHERE tenant_id = ? AND mapping_version_id = ?
                """, author, now, now, tenantId, mappingId);
        jdbc.update("""
                UPDATE sys_connector_mapping_versions
                   SET dry_run_receipt_id = gen_random_uuid(),
                       dry_run_result_ref = 'result/dry-run/one',
                       dry_run_result_digest = repeat('a', 64),
                       dry_run_evidence_digest = repeat('b', 64),
                       dry_run_source_count = 1, dry_run_rejected_count = 0,
                       dry_run_by_actor_id = ?, dry_run_at = ?,
                       object_revision = 3, updated_at = ?
                 WHERE tenant_id = ? AND mapping_version_id = ?
                """, author, now, now, tenantId, mappingId);
        jdbc.update("""
                UPDATE sys_connector_mapping_versions
                   SET lifecycle_state = 'PENDING_APPROVAL',
                       submission_receipt_id = gen_random_uuid(),
                       submission_evidence_digest = repeat('c', 64),
                       submitted_by_actor_id = ?, submitted_at = ?,
                       object_revision = 4, updated_at = ?
                 WHERE tenant_id = ? AND mapping_version_id = ?
                """, author, now, now, tenantId, mappingId);
        jdbc.update("""
                UPDATE sys_connector_mapping_versions
                   SET lifecycle_state = 'APPROVED',
                       approval_receipt_id = gen_random_uuid(),
                       approval_evidence_digest = repeat('d', 64),
                       approval_step_up_evidence_digest = repeat('e', 64),
                       approved_by_actor_id = ?, approved_at = ?,
                       object_revision = 5, updated_at = ?
                 WHERE tenant_id = ? AND mapping_version_id = ?
                """, executor, now, now, tenantId, mappingId);
        jdbc.update("""
                UPDATE sys_connector_mapping_versions
                   SET lifecycle_state = 'ACTIVE',
                       activation_receipt_id = gen_random_uuid(),
                       activation_evidence_digest = repeat('f', 64),
                       activation_step_up_evidence_digest = repeat('0', 64),
                       activated_by_actor_id = ?, activated_at = ?,
                       object_revision = 6, updated_at = ?
                 WHERE tenant_id = ? AND mapping_version_id = ?
                """, executor, now, now, tenantId, mappingId);

        insertExecutionReceipt(jdbc, tenantId, commandReceiptId, key);
        jdbc.update("""
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
                    requested_by_actor_id, requested_scope_digest, requested_at,
                    updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, 1, 1, 1, 'EXPORT',
                        'payload/hris/source', repeat('c', 64), ?,
                        repeat('d', 64), ?, repeat('d', 64), 'workday', 'EXPORT',
                        ?, repeat('e', 64), 1, 'TENANT', NULL,
                        ?, ?, repeat('f', 64), ?, ?)
                """, tenantId, executionId, executionPublicId, definitionId,
                definitionVersionId, mappingId, sourceCount, sourceCount,
                "provider-" + key + "-00000001",
                commandReceiptId, executor, now, now);
        jdbc.update("""
                INSERT INTO sys_connector_execution_items (
                    tenant_id, execution_item_id, public_id, execution_id,
                    item_ordinal, source_key_digest, source_digest,
                    mapping_version_id, mapping_version_number,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, repeat('1', 64), repeat('2', 64),
                        ?, 1, ?, ?)
                """, tenantId, itemId, itemPublicId, executionId,
                mappingId, now, now);
        if (sourceCount > 1) {
            jdbc.update("""
                    INSERT INTO sys_connector_execution_items (
                        tenant_id, execution_item_id, public_id, execution_id,
                        item_ordinal, source_key_digest, source_digest,
                        mapping_version_id, mapping_version_number,
                        created_at, updated_at)
                    VALUES (?, ?, ?, ?, 2, repeat('3', 64), repeat('4', 64),
                            ?, 1, ?, ?)
                    """, tenantId, secondItemId, secondItemPublicId,
                    executionId, mappingId, now, now);
        }

        if (dispatched) {
            jdbc.update("""
                    UPDATE sys_connector_executions
                       SET lifecycle_state = 'TRANSFORMING', object_revision = 2,
                           updated_at = ?
                     WHERE tenant_id = ? AND execution_id = ?
                    """, now, tenantId, executionId);
            jdbc.update("""
                    UPDATE sys_connector_executions
                       SET lifecycle_state = 'DISPATCHED',
                           current_attempt_fence = 1,
                           unresolved_external_effect = TRUE,
                           object_revision = 3, updated_at = ?
                     WHERE tenant_id = ? AND execution_id = ?
                    """, now, tenantId, executionId);
            cleanTransactions.executeWithoutResult(ignored -> {
                jdbc.update("""
                        INSERT INTO sys_connector_execution_attempts (
                            tenant_id, execution_attempt_id, public_id, execution_id,
                            execution_item_id, attempt_number, attempt_fence,
                            execution_revision_snapshot, provider_idempotency_key,
                            request_digest, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 1, 1, 3, ?, repeat('4', 64), ?, ?)
                        """, tenantId, attemptId, uuid(key + "-attempt-public"),
                        executionId, itemId,
                        "attempt-" + key + "-00000001", now, now);
                jdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'TRANSFORMED',
                               transformed_digest = repeat('5', 64),
                               object_revision = 2, updated_at = ?
                         WHERE tenant_id = ? AND execution_item_id = ?
                        """, now, tenantId, itemId);
                jdbc.update("""
                        UPDATE sys_connector_execution_items
                           SET lifecycle_state = 'DISPATCHED',
                               provider_item_key = 'provider-item-initial-0001',
                               latest_attempt_number = 1,
                               latest_attempt_fence = 1,
                               object_revision = 3, updated_at = ?
                         WHERE tenant_id = ? AND execution_item_id = ?
                        """, now, tenantId, itemId);
            });
        }
        return new Graph(tenantId, definitionId, definitionVersionId,
                mappingId, executionId,
                executionPublicId, itemId, itemPublicId,
                secondItemId, secondItemPublicId, attemptId);
    }

    private static void prepareFailedRetryableGraph(
            JdbcTemplate jdbc,
            Graph graph) {
        jdbc.update("""
                UPDATE sys_connector_execution_items
                   SET lifecycle_state = 'REJECTED_RETRYABLE',
                       failure_class = 'PROVIDER_RETRYABLE',
                       retryable_snapshot = TRUE,
                       object_revision = 2,
                       updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_item_id = ?
                """, graph.tenantId(), graph.executionItemId());
        jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'TRANSFORMING', object_revision = 2,
                       updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, graph.tenantId(), graph.executionId());
        jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'FAILED', object_revision = 3,
                       updated_at = statement_timestamp()
                 WHERE tenant_id = ? AND execution_id = ?
                """, graph.tenantId(), graph.executionId());
    }

    private static void insertExecutionReceipt(
            JdbcTemplate jdbc, long tenantId, UUID publicId, String key) {
        jdbc.update("""
                INSERT INTO sys_hris_command_receipts (
                    public_id, tenant_id, command_type, originating_action,
                    subject_principal_public_id, population_scope_digest,
                    authority_population_scope_digest, field_policy_revision,
                    authority_field_policy_revision, purpose_code,
                    authorization_revision, authority_authorization_revision,
                    origin_route_contract_key, origin_context_key,
                    origin_context_scope_key, origin_decision_revision,
                    idempotency_key, request_digest, status, correlation_id)
                VALUES (?, ?, 'CREATE_EXECUTION', ?, ?, repeat('a', 64),
                        repeat('a', 64), 1, 1, 'HRIS_INTEGRATION_ADMIN', 1, 1,
                        'route.sys008.execution', 'ctx', 'scope',
                        'psr-' || repeat('a', 64), ?, repeat('b', 64),
                        'ACCEPTED', ?)
                """, publicId, tenantId, CREATE_EXECUTION_ACTION,
                uuid(key + "-subject"), "idempotency-" + key + "-0001",
                uuid(key + "-correlation"));
    }

    private static UUID insertLegacyReceipt(JdbcTemplate jdbc) {
        UUID publicId = uuid("v333-preserved-sys008-predecessor-receipt");
        jdbc.update("""
                INSERT INTO sys_hris_command_receipts (
                    public_id, tenant_id, command_type, originating_action,
                    subject_principal_public_id, population_scope_digest,
                    authority_population_scope_digest, field_policy_revision,
                    authority_field_policy_revision, purpose_code,
                    authorization_revision, authority_authorization_revision,
                    origin_route_contract_key, origin_context_key,
                    origin_context_scope_key, origin_decision_revision,
                    idempotency_key, request_digest, status, correlation_id)
                VALUES (?, 82001, 'PRE_V334_EVIDENCE',
                        'hris.action.preexisting.producer', ?, repeat('a', 64),
                        repeat('a', 64), 1, 1, 'HR_OPERATIONS', 1, 1,
                        'route.pre.v334', 'ctx', 'scope', 'decision:v333',
                        'v333-preserved-key-0001', repeat('b', 64), 'ACCEPTED', ?)
                """, publicId, uuid("v333-legacy-subject"),
                uuid("v333-legacy-correlation"));
        return publicId;
    }

    private static void provisionApplicationRole() {
        cleanJdbc.execute("""
                DO $role$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles WHERE rolname = 'sys008_evidence_app'
                    ) THEN
                        CREATE ROLE sys008_evidence_app LOGIN PASSWORD
                            'sys008-evidence-password' NOSUPERUSER NOINHERIT
                            NOBYPASSRLS;
                    END IF;
                END
                $role$
                """);
        cleanJdbc.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
        for (String table : TABLES) {
            cleanJdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE "
                    + table + " TO " + APP_ROLE);
        }
        cleanJdbc.execute("GRANT SELECT, UPDATE ON TABLE "
                + "sys_hris_command_receipts TO "
                + APP_ROLE);
    }

    private static void assertRuntimeRejected(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            long tenantId,
            String marker,
            Consumer<JdbcTemplate> write) {
        assertRuntimeRejected(jdbc, transactions, tenantId, null, marker, write);
    }

    private static void assertRuntimeRejected(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            long tenantId,
            UUID principalId,
            String marker,
            Consumer<JdbcTemplate> write) {
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            jdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', ?, true)",
                    String.class, Long.toString(tenantId));
            if (principalId != null) {
                jdbc.queryForObject(
                        "SELECT set_config('dwp.principal_public_id', ?, true)",
                        String.class, principalId.toString());
            }
            write.accept(jdbc);
        })).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(marker);
    }

    private static void assertRejected(String marker, Runnable write) {
        assertThatThrownBy(write::run)
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(marker);
    }

    private static String currentVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                 WHERE success AND version IS NOT NULL
                 ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
    }

    private static Flyway flyway(PGSimpleDataSource source, String target) {
        return Flyway.configure()
                .dataSource(source)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private static PGSimpleDataSource dataSource(
            String database, String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setServerNames(new String[]{POSTGRES.getHost()});
        source.setPortNumbers(new int[]{POSTGRES.getFirstMappedPort()});
        source.setDatabaseName(database);
        source.setUser(username);
        source.setPassword(password);
        return source;
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Graph(
            long tenantId,
            UUID definitionId,
            UUID definitionVersionId,
            UUID mappingVersionId,
            UUID executionId,
            UUID executionPublicId,
            UUID executionItemId,
            UUID executionItemPublicId,
            UUID secondExecutionItemId,
            UUID secondExecutionItemPublicId,
            UUID executionAttemptId) {}

    private static final class InjectedRollback extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
