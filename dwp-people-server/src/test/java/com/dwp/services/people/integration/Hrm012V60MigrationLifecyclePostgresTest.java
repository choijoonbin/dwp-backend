package com.dwp.services.people.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.dwp.services.people.integration.Hrm012V60MigrationFixture.*;

/** Atomic HRM-012 terminalization and cursor/outbox rollback migration scenarios. */
@Testcontainers
class Hrm012V60MigrationLifecyclePostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private Flyway migrated;

    @AfterEach
    void clean() {
        if (migrated != null) migrated.clean();
    }

    @Test
    void failedTerminalizationRollsBackCursorOutboxAndRunThenExactRetryWins() {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        RunGraph apply = insertRunning(
                jdbc, TENANT_ONE, "rollback", "APPLY");
        insertItem(jdbc, apply, 1, "APPLIED");
        insertCursor(jdbc, apply, 0L, "cursor-before");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(source));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            assertThat(advanceCursor(jdbc, apply, "cursor-after")).isOne();
            insertAudit(jdbc, apply, 2, SHA_E);
            insertExactOutbox(jdbc, apply, 2, 1, 0, 0, 0, 1);
            completeApply(jdbc, apply, 2, 1, 0, 0, 0, 1,
                    digest("cursor-after"));
            completeReceipt(jdbc, apply, SHA_E);
        })).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "run counts must equal immutable item evidence");

        assertThat(jdbc.queryForObject("""
                SELECT version FROM int_connector_cursors
                 WHERE tenant_id=? AND connector_instance_id=?
                """, Long.class, apply.tenantId(), apply.connectorId()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_outbox_events
                 WHERE tenant_id=? AND aggregate_id=?
                """, Integer.class, apply.tenantId(), apply.runPublicId().toString()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_audit_events
                 WHERE tenant_id=? AND target_id=?
                """, Integer.class, apply.tenantId(), apply.runPublicId().toString()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_receipt_id=?
                """, String.class, apply.tenantId(), apply.receiptId()))
                .isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_workforce_ingestion_runs
                 WHERE workforce_ingestion_run_id=?
                """, String.class, apply.runId())).isEqualTo("RUNNING");

        reconcileApply(jdbc, apply, 1, 1, 0, 0, 0, 1, "cursor-after");
        assertThat(jdbc.queryForObject("""
                SELECT version FROM int_connector_cursors
                 WHERE tenant_id=? AND connector_instance_id=?
                """, Long.class, apply.tenantId(), apply.connectorId()))
                .isOne();
    }

    @Test
    void terminalApplyRejectsMissingStaleAndDigestMismatchedCursorState() {
        JdbcTemplate jdbc = new JdbcTemplate(migratedSource());

        RunGraph missing = insertRunning(jdbc, TENANT_ONE, "cursor-missing", "APPLY");
        insertItem(jdbc, missing, 1, "APPLIED");
        assertThatThrownBy(() -> finishApplyWithoutCursorMutation(
                jdbc, missing, digest("cursor-after")))
                .isInstanceOfAny(DataAccessException.class, TransactionSystemException.class)
                .rootCause().hasMessageContaining("exact same-transaction cursor CAS");
        assertRunningAndReceiptOpen(jdbc, missing);

        RunGraph stale = insertRunning(jdbc, TENANT_ONE, "cursor-stale", "APPLY");
        insertItem(jdbc, stale, 1, "APPLIED");
        insertCursor(jdbc, stale, 0L, "cursor-before");
        assertThat(advanceCursor(jdbc, stale, "already-advanced")).isOne();
        assertThatThrownBy(() -> finishApplyWithoutCursorMutation(
                jdbc, stale, digest("already-advanced")))
                .isInstanceOfAny(DataAccessException.class, TransactionSystemException.class)
                .rootCause().hasMessageContaining("exact same-transaction cursor CAS");
        assertRunningAndReceiptOpen(jdbc, stale);

        RunGraph missingEvent = insertRunning(
                jdbc, TENANT_ONE, "outbox-missing", "APPLY");
        insertItem(jdbc, missingEvent, 1, "APPLIED");
        insertCursor(jdbc, missingEvent, 0L, "cursor-before");
        assertThatThrownBy(() -> transaction(jdbc).executeWithoutResult(status -> {
            assertThat(advanceCursor(jdbc, missingEvent, "cursor-after")).isOne();
            insertAudit(jdbc, missingEvent, 1, SHA_E);
            completeApply(jdbc, missingEvent, 1, 1, 0, 0, 0, 1,
                    digest("cursor-after"));
            completeReceipt(jdbc, missingEvent, SHA_E);
        })).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("requires one exact reconciled event");
        assertRunningAndReceiptOpen(jdbc, missingEvent);

        RunGraph wrongDigest = insertRunning(
                jdbc, TENANT_ONE, "cursor-wrong-digest", "APPLY");
        insertItem(jdbc, wrongDigest, 1, "APPLIED");
        insertCursor(jdbc, wrongDigest, 0L, "cursor-before");
        assertThatThrownBy(() -> transaction(jdbc).executeWithoutResult(status -> {
            assertThat(advanceCursor(jdbc, wrongDigest, "cursor-after")).isOne();
            insertAudit(jdbc, wrongDigest, 1, SHA_E);
            insertExactOutbox(jdbc, wrongDigest, 1, 1, 0, 0, 0, 1);
            completeApply(jdbc, wrongDigest, 1, 1, 0, 0, 0, 1, SHA_F);
            completeReceipt(jdbc, wrongDigest, SHA_E);
        })).isInstanceOfAny(DataAccessException.class, TransactionSystemException.class)
                .rootCause().hasMessageContaining(
                        "cursor mutation requires same-transaction successful terminal run");
        assertRunningAndReceiptOpen(jdbc, wrongDigest);
        assertThat(jdbc.queryForObject("""
                SELECT version FROM int_connector_cursors
                 WHERE tenant_id=? AND connector_instance_id=?
                """, Long.class, wrongDigest.tenantId(), wrongDigest.connectorId()))
                .isZero();
    }

    @Test
    void unrelatedSharedCursorWriteIgnoresPendingHrmRunFromPriorTransaction() {
        JdbcTemplate jdbc = new JdbcTemplate(migratedSource());
        RunGraph pending = insertRunning(
                jdbc, TENANT_ONE, "shared-cursor-unrelated", "APPLY");
        insertCursor(jdbc, pending, 0L, "cursor-before");

        assertThat(advanceCursor(
                jdbc, pending, "cursor-owned-by-another-workflow")).isOne();

        assertThat(jdbc.queryForObject("""
                SELECT version FROM int_connector_cursors
                 WHERE tenant_id=? AND connector_instance_id=?
                   AND cursor_type='OPAQUE_TOKEN'
                """, Long.class, pending.tenantId(), pending.connectorId()))
                .isOne();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_workforce_ingestion_runs
                 WHERE tenant_id=? AND workforce_ingestion_run_id=?
                """, String.class, pending.tenantId(), pending.runId()))
                .isEqualTo("RUNNING");
    }

    @Test
    void separatedSuccessEvidenceFailsAndOnlyOneAtomicTransactionCommits() {
        JdbcTemplate jdbc = new JdbcTemplate(migratedSource());
        RunGraph apply = insertRunning(
                jdbc, TENANT_ONE, "separated-evidence", "APPLY");
        insertItem(jdbc, apply, 1, "APPLIED");
        insertCursor(jdbc, apply, 0L, "cursor-before");
        UUID eventId = deterministicId(
                "WorkforceIngestionReconciled.v1", apply, SHA_E);

        assertThatThrownBy(() -> insertTerminalReceipt(
                jdbc, apply.tenantId(), "direct-terminal-insert"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "successful receipt requires same-transaction terminal run");
        assertThatThrownBy(() -> insertExactOutbox(
                jdbc, apply, 1, 1, 0, 0, 0, 1, UUID.randomUUID(), SHA_E))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("event identity is not canonical");
        assertThatThrownBy(() -> insertAudit(jdbc, apply, 1, SHA_E))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "audit event requires same-transaction terminal run");
        assertThatThrownBy(() -> insertExactOutbox(
                jdbc, apply, 1, 1, 0, 0, 0, 1, eventId, SHA_E))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "reconciled event requires same-transaction terminal run");
        assertThatThrownBy(() -> completeReceipt(jdbc, apply, SHA_E))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "successful receipt requires same-transaction terminal run");

        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_workforce_ingestion_runs
                 WHERE workforce_ingestion_run_id=?
                """, String.class, apply.runId())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM ppl_command_receipts
                 WHERE tenant_id=? AND command_receipt_id=?
                """, String.class, apply.tenantId(), apply.receiptId()))
                .isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_outbox_events
                 WHERE tenant_id=? AND aggregate_id=?
                """, Integer.class, apply.tenantId(), apply.runPublicId().toString()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_audit_events
                 WHERE tenant_id=? AND target_id=?
                """, Integer.class, apply.tenantId(), apply.runPublicId().toString()))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT version FROM int_connector_cursors
                 WHERE tenant_id=? AND connector_instance_id=?
                """, Long.class, apply.tenantId(), apply.connectorId())).isZero();

        assertThatThrownBy(() -> transaction(jdbc).executeWithoutResult(status -> {
            assertThat(advanceCursor(jdbc, apply, "cursor-after")).isOne();
            insertAudit(jdbc, apply, 1, SHA_E);
            insertExactOutbox(
                    jdbc, apply, 1, 1, 0, 0, 0, 1, eventId, SHA_E);
            insertExactOutbox(
                    jdbc, apply, 1, 1, 0, 0, 0, 1, eventId, SHA_F);
        }))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("duplicate key");

        reconcileApply(jdbc, apply, 1, 1, 0, 0, 0, 1, "cursor-after");
        assertThat(jdbc.queryForObject("""
                SELECT event_id FROM sys_people_outbox_events
                 WHERE tenant_id=? AND aggregate_type='WorkforceIngestionRun'
                   AND aggregate_id=? AND event_type='WorkforceIngestionReconciled.v1'
                """, UUID.class, apply.tenantId(), apply.runPublicId().toString()))
                .isEqualTo(eventId);
    }

    @Test
    void itemInsertLocksParentAgainstTerminalizationAndDeferredRecountIsExact()
            throws Exception {
        DataSource source = migratedSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        RunGraph validation = insertRunning(
                jdbc, TENANT_ONE, "item-terminal-race", "VALIDATE_ONLY");

        try (Connection itemConnection = source.getConnection();
             Connection terminalConnection = source.getConnection()) {
            itemConnection.setAutoCommit(false);
            terminalConnection.setAutoCommit(false);
            JdbcTemplate itemJdbc = new JdbcTemplate(
                    new SingleConnectionDataSource(itemConnection, true));
            JdbcTemplate terminalJdbc = new JdbcTemplate(
                    new SingleConnectionDataSource(terminalConnection, true));
            insertItem(itemJdbc, validation, 1, "VALIDATED");
            terminalJdbc.execute("SET LOCAL lock_timeout='250ms'");

            assertThatThrownBy(() -> completeValidation(
                    terminalJdbc, validation, 0, 0, 0, 0))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("lock timeout");
            terminalConnection.rollback();
            itemConnection.commit();
        }

        reconcileValidation(jdbc, validation, 1, 0, 0, 0);
        assertThat(jdbc.queryForObject("""
                SELECT source_count FROM ppl_workforce_ingestion_runs
                 WHERE workforce_ingestion_run_id=?
                """, Long.class, validation.runId())).isOne();
    }

    private DataSource migratedSource() {
        DataSource source = dataSource();
        migrated = flyway(source, null);
        migrated.clean();
        migrated.migrate();
        return source;
    }

    private DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
