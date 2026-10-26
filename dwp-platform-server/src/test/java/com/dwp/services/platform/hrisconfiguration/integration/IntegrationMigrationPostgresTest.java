package com.dwp.services.platform.hrisconfiguration.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Isolated execution proof for the exact V334 migration and receipt guard. */
@Testcontainers(disabledWithoutDocker = true)
class IntegrationMigrationPostgresTest {

    private static final List<String> TABLES = List.of(
            "sys_connector_definitions",
            "sys_connector_definition_versions",
            "sys_connector_mapping_versions",
            "sys_connector_executions",
            "sys_connector_execution_items",
            "sys_connector_execution_attempts");
    private static final String SYS008_CREATE_EXECUTION =
            "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.executions";
    private static final String SYS006_CREATE_AUTOMATION =
            "hris.action.sys.post.platform.v1.admin.hris.automations";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateExactPredecessorsAndV334() throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        executeMigration("V327__create_hris_work_explorer.sql");
        executeMigration("V333__create_hris_automation_control_plane.sql");
        executeMigration("V334__create_hris_integration_control_plane.sql");
    }

    @Test
    void exactV334ExecutesWithSixForcedTenantTablesAndNoSeventhLedger() {
        assertThat(jdbc.queryForList("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name LIKE 'sys_connector_%'
                 ORDER BY table_name
                """, String.class)).containsExactlyInAnyOrderElementsOf(TABLES);
        assertThat(jdbc.queryForList("""
                SELECT relname
                  FROM pg_class
                 WHERE relnamespace = 'public'::regnamespace
                   AND relname LIKE 'sys_connector_%'
                   AND relkind = 'r'
                   AND relrowsecurity AND relforcerowsecurity
                 ORDER BY relname
                """, String.class))
                .containsExactlyInAnyOrderElementsOf(TABLES);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger
                 WHERE NOT tgisinternal AND tgname IN (
                    'trg_sys_connector_definition_version_guard',
                    'trg_sys_connector_mapping_version_guard',
                    'trg_sys_connector_execution_guard',
                    'trg_sys_connector_execution_item_guard',
                    'trg_sys_connector_execution_attempt_guard',
                    'trg_sys_integration_shared_receipt')
                """, Integer.class)).isEqualTo(6);
    }

    @Test
    void sys008ResultUnknownIsTerminalWhileSys006RecoveryStillComposes() {
        assertThatThrownBy(() -> insertReceipt(
                SYS008_CREATE_EXECUTION, "SUCCEEDED", null,
                UUID.randomUUID(), 1L))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "SYS008_COMMAND_RECEIPT_MUST_START_ACCEPTED");

        UUID nullRevisionReceipt = insertReceipt(
                SYS008_CREATE_EXECUTION, "ACCEPTED", null, null, null);
        jdbc.update("""
                UPDATE sys_hris_command_receipts SET status = 'RUNNING'
                 WHERE public_id = ?
                """, nullRevisionReceipt);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = 'SUCCEEDED', aggregate_public_id = ?,
                       aggregate_revision = NULL, result_ref = 'execution/result',
                       completed_at = statement_timestamp()
                 WHERE public_id = ?
                """, UUID.randomUUID(), nullRevisionReceipt))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_sys008_receipt_state_tuple");

        UUID sys008Receipt = insertReceipt(
                SYS008_CREATE_EXECUTION, "ACCEPTED", null, null, null);
        UUID aggregate = UUID.randomUUID();
        jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = 'RUNNING', aggregate_public_id = ?,
                       aggregate_revision = 1, result_ref = 'execution/result'
                 WHERE public_id = ?
                """, aggregate, sys008Receipt);
        jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = 'RESULT_UNKNOWN', error_code = 'PROVIDER_TIMEOUT',
                       completed_at = statement_timestamp()
                 WHERE public_id = ?
                """, sys008Receipt);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = 'RUNNING', error_code = NULL, completed_at = NULL
                 WHERE public_id = ?
                """, sys008Receipt))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "SYS008_TERMINAL_COMMAND_RECEIPT_IMMUTABLE");

        UUID sys006 = insertSys006ResultUnknownReceipt();
        assertThat(jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = 'RUNNING', error_code = NULL, completed_at = NULL
                 WHERE public_id = ?
                """, sys006)).isOne();
        assertThat(jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = 'SUCCEEDED', completed_at = statement_timestamp()
                 WHERE public_id = ?
                """, sys006)).isOne();
    }

    private static UUID insertReceipt(
            String action,
            String status,
            String errorCode,
            UUID aggregatePublicId,
            Long aggregateRevision) {
        UUID publicId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_hris_command_receipts (
                    public_id, tenant_id, command_type, originating_action,
                    subject_principal_public_id, population_scope_digest,
                    authority_population_scope_digest, field_policy_revision,
                    authority_field_policy_revision, purpose_code,
                    authorization_revision, authority_authorization_revision,
                    origin_route_contract_key, origin_context_key,
                    origin_context_scope_key, origin_decision_revision,
                    idempotency_key, request_digest, aggregate_public_id,
                    aggregate_revision, status, error_code, correlation_id,
                    completed_at)
                VALUES (?, 81001, 'MIGRATION_EVIDENCE', ?, ?, repeat('a', 64),
                        repeat('a', 64), 1, 1, 'HRIS_INTEGRATION_ADMIN', 1, 1,
                        'route.sys008.evidence', 'ctx', 'scope',
                        'psr-' || repeat('b', 64), ?, repeat('c', 64), ?, ?, ?,
                        ?, ?, ?)
                """, publicId, action, UUID.randomUUID(),
                "sys008-evidence-" + UUID.randomUUID(), aggregatePublicId,
                aggregateRevision, status, errorCode, UUID.randomUUID(),
                errorCode == null ? null
                        : OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1));
        return publicId;
    }

    private static UUID insertSys006ResultUnknownReceipt() {
        UUID publicId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_hris_command_receipts (
                    public_id, tenant_id, command_type, originating_action,
                    subject_principal_public_id, population_scope_digest,
                    authority_population_scope_digest, field_policy_revision,
                    authority_field_policy_revision, purpose_code,
                    authorization_revision, authority_authorization_revision,
                    origin_route_contract_key, origin_context_key,
                    origin_context_scope_key, origin_decision_revision,
                    idempotency_key, request_digest, aggregate_public_id,
                    aggregate_revision, result_ref, status, error_code,
                    correlation_id, completed_at)
                VALUES (?, 81002, 'SYS006_RECOVERY_EVIDENCE', ?, ?,
                        repeat('a', 64), repeat('a', 64), 1, 1,
                        'HRIS_AUTOMATION_ADMIN', 1, 1,
                        'route.sys006.recovery', 'ctx', 'scope',
                        'psr-' || repeat('b', 64), ?, repeat('c', 64), ?, 1,
                        'automation/result', 'RESULT_UNKNOWN',
                        'PROVIDER_RESULT_UNKNOWN', ?, NULL)
                """, publicId, SYS006_CREATE_AUTOMATION, UUID.randomUUID(),
                "sys006-recovery-" + UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
        return publicId;
    }

    private static void executeMigration(String filename) throws Exception {
        jdbc.execute(Files.readString(Path.of(
                "src/main/resources/db/migration", filename)));
    }
}
