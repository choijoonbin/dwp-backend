package com.dwp.services.platform.hrisconfiguration.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shared setup and evidence builders for IntegrationRequiredDatabaseEvidenceTest. */
abstract class IntegrationDatabaseEvidenceFixture {
    protected static final String CLEAN_DATABASE = "sys008_clean_evidence";
    protected static final String UPGRADE_DATABASE = "sys008_upgrade_evidence";
    protected static final String SCHEMA = "public";
    protected static final String APP_ROLE = "sys008_evidence_app";
    protected static final String APP_PASSWORD = "sys008-evidence-password";
    protected static final String CREATE_EXECUTION_ACTION =
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.executions";
    protected static final List<String> TABLES = List.of(
            "sys_connector_definitions",
            "sys_connector_definition_versions",
            "sys_connector_mapping_versions",
            "sys_connector_executions",
            "sys_connector_execution_items",
            "sys_connector_execution_attempts");

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    protected static PGSimpleDataSource cleanSource;
    protected static JdbcTemplate cleanJdbc;
    protected static JdbcTemplate upgradeJdbc;
    protected static TransactionTemplate cleanTransactions;
    protected static UUID legacyReceiptPublicId;

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

    protected static Graph seedGraph(
            JdbcTemplate jdbc,
            long tenantId,
            String key,
            boolean dispatched) {
        return seedGraph(jdbc, tenantId, key, dispatched, 1);
    }

    protected static Graph seedGraph(
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

    protected static void prepareFailedRetryableGraph(
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

    protected static void insertExecutionReceipt(
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

    protected static UUID insertLegacyReceipt(JdbcTemplate jdbc) {
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

    protected static void provisionApplicationRole() {
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

    protected static void assertRuntimeRejected(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            long tenantId,
            String marker,
            Consumer<JdbcTemplate> write) {
        assertRuntimeRejected(jdbc, transactions, tenantId, null, marker, write);
    }

    protected static void assertRuntimeRejected(
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

    protected static void assertRejected(String marker, Runnable write) {
        assertThatThrownBy(write::run)
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(marker);
    }

    protected static String currentVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                 WHERE success AND version IS NOT NULL
                 ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
    }

    protected static Flyway flyway(PGSimpleDataSource source, String target) {
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

    protected static PGSimpleDataSource dataSource(
            String database, String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setServerNames(new String[]{POSTGRES.getHost()});
        source.setPortNumbers(new int[]{POSTGRES.getFirstMappedPort()});
        source.setDatabaseName(database);
        source.setUser(username);
        source.setPassword(password);
        return source;
    }

    protected static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    protected record Graph(
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

    protected static final class InjectedRollback extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
