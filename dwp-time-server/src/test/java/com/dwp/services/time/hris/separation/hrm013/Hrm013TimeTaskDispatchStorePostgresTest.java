package com.dwp.services.time.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Hrm013TimeTaskDispatchStorePostgresTest {
    private static final String ROLE = "tim_hrm013_runtime";
    private static final String PASSWORD = "tim-hrm013-runtime-test";
    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
    private PostgreSQLContainer<?> postgres;
    private Hrm013TimeTaskDispatchStore store;
    private JdbcTemplate admin;
    private NamedParameterJdbcTemplate runtime;
    private TransactionTemplate runtimeTransactions;

    @BeforeAll
    void createV15Schema() throws IOException {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker is required for the HRM-013 Time PostgreSQL gate").isTrue();
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        DataSource adminDataSource = dataSource(
                postgres.getUsername(), postgres.getPassword());
        admin = new JdbcTemplate(adminDataSource);
        admin.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        admin.execute(migration().replace("${timeRuntimeRole}", ROLE));

        DataSource runtimeDataSource = dataSource(ROLE, PASSWORD);
        runtime = new NamedParameterJdbcTemplate(runtimeDataSource);
        runtimeTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeDataSource));
        store = new Hrm013TimeTaskDispatchStore(
                runtime,
                new DataSourceTransactionManager(runtimeDataSource),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterAll
    void stop() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void proofPairAndDispatchReceiptAreAtomicIdempotentAndConflictQuarantined() {
        long tenant = 17;
        var reservation = new Hrm013WorkloadProofV1.ReplayReservation(
                tenant, "SVC-PEP-TIM-004", "dwp-people-server",
                "a".repeat(64), "b".repeat(64), Instant.now().plusSeconds(60));
        assertThat(store.reserveProof(reservation)).isTrue();
        assertThat(store.reserveProof(reservation)).isFalse();
        assertThatThrownBy(() -> inRuntimeTenant(tenant, () -> runtime.update("""
                DELETE FROM hris_hrm013_time_proof_replay
                 WHERE tenant_id=:tenant
                """, Map.of("tenant", tenant))))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM hris_hrm013_time_proof_replay
                 WHERE tenant_id=17
                """, Long.class)).isEqualTo(2L);

        UUID task = UUID.randomUUID();
        var first = candidate(tenant, task, "VOLUNTARY", "c".repeat(64));
        var admitted = store.admit(first);
        var replay = store.admit(candidate(tenant, task, "VOLUNTARY", "d".repeat(64)));
        var conflict = store.admit(candidate(tenant, task, "INVOLUNTARY", "e".repeat(64)));

        assertThat(admitted.kind()).isEqualTo(
                Hrm013TimeTaskDispatchStore.AdmissionKind.ADMITTED);
        assertThat(replay.kind()).isEqualTo(
                Hrm013TimeTaskDispatchStore.AdmissionKind.EXACT_REPLAY);
        assertThat(replay.exactReceiptBody()).isEqualTo(admitted.exactReceiptBody());
        assertThat(replay.receiptProof()).isEqualTo(admitted.receiptProof());
        assertThat(conflict.kind()).isEqualTo(
                Hrm013TimeTaskDispatchStore.AdmissionKind.DIGEST_CONFLICT);

        UUID externalReceipt = UUID.randomUUID();
        var terminal = new Hrm013TimeTaskDispatchStore.TerminalCommand(
                tenant, first.dispatch().dispatchEventId(), "COMPLETED",
                externalReceipt, NOW.plusSeconds(10), first.dispatch().correlationId());
        store.enqueueTerminal(terminal);
        store.enqueueTerminal(terminal);
        assertThatThrownBy(() -> store.enqueueTerminal(
                new Hrm013TimeTaskDispatchStore.TerminalCommand(
                        tenant, first.dispatch().dispatchEventId(), "COMPLETED",
                        UUID.randomUUID(), NOW.plusSeconds(10),
                        first.dispatch().correlationId())))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.DIGEST_CONFLICT);
        var callbacks = store.claimCallbacks(tenant, 10,
                new Hrm013TimeTaskDispatchStore.DurationLease("shared-worker", 1));
        assertThat(callbacks).singleElement().satisfies(callback -> {
            assertThat(callback.ownerRevision()).isEqualTo(2);
            assertThat(callback.status()).isEqualTo("COMPLETED");
            assertThat(callback.externalReceiptPublicId()).isEqualTo(externalReceipt);
            assertThat(callback.terminalParentCreatedAt()).isEqualTo(NOW);
            var persisted = Hrm013SeparationBridgeV1.decodeTaskReceipt(
                    callback.exactCallbackBody());
            assertThat(persisted.payloadSha256())
                    .isEqualTo(callback.callbackPayloadSha256());
        });
        try {
            Thread.sleep(1_100);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
        var reclaimed = store.claimCallbacks(tenant, 10,
                new Hrm013TimeTaskDispatchStore.DurationLease("shared-worker", 30));
        assertThat(reclaimed).singleElement().satisfies(callback -> {
            assertThat(callback.outboxId()).isEqualTo(callbacks.getFirst().outboxId());
            assertThat(callback.attemptCount()).isEqualTo(2);
            assertThat(callback.exactCallbackBody())
                    .isEqualTo(callbacks.getFirst().exactCallbackBody());
        });
        assertThatThrownBy(() -> store.markDelivered(
                tenant, callbacks.getFirst().outboxId(), "shared-worker",
                callbacks.getFirst().attemptCount()))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED);
        store.markDelivered(tenant, reclaimed.getFirst().outboxId(), "shared-worker",
                reclaimed.getFirst().attemptCount());

        assertThat(admin.queryForObject("""
                SELECT count(*) FROM hris_hrm013_time_task_dispatch_quarantine
                 WHERE tenant_id=17
                """, Long.class)).isEqualTo(1L);
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM hris_hrm013_time_callback_quarantine
                 WHERE tenant_id=17
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void v15RejectsUnsafeRuntimeRole() throws Exception {
        String unsafe = "tim_hrm013_unsafe_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8);
        admin.execute("CREATE ROLE " + unsafe + " LOGIN SUPERUSER");
        try {
            String sql = migration();
            int start = sql.indexOf("DO $runtime_access$");
            int end = sql.indexOf("$runtime_access$;", start)
                    + "$runtime_access$;".length();
            String runtimeGrant = sql.substring(start, end)
                    .replace("${timeRuntimeRole}", unsafe);
            assertThatThrownBy(() -> admin.execute(runtimeGrant))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class)
                    .rootCause().hasMessageContaining(
                            "Time runtime role is missing or unsafe");
        } finally {
            admin.execute("DROP ROLE " + unsafe);
        }
    }

    @Test
    void v15SealsInboxAndOutboxSnapshotsAndInitialState() {
        long tenant = 19;
        var admission = candidate(tenant, UUID.randomUUID(), "VOLUNTARY", "1".repeat(64));
        store.admit(admission);
        assertThatThrownBy(() -> insertForgedPendingOutbox(admission))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause().hasMessageContaining(
                        "HRM-013 callback requires its exact terminal dispatch");
        assertThatThrownBy(() -> admin.update("""
                UPDATE hris_hrm013_time_task_dispatch_inbox
                   SET payload_sha256=?
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, "9".repeat(64), tenant, admission.dispatch().dispatchEventId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause().hasMessageContaining("HRM-013 inbox snapshot is immutable");
        assertThatThrownBy(() -> cloneAsTerminalInbox(admission))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause().hasMessageContaining("HRM-013 inbox must start admitted");

        store.enqueueTerminal(new Hrm013TimeTaskDispatchStore.TerminalCommand(
                tenant, admission.dispatch().dispatchEventId(), "COMPLETED",
                UUID.randomUUID(), NOW.plusSeconds(3),
                admission.dispatch().correlationId()));
        assertThatThrownBy(() -> admin.update("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET correlation_id=?
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, UUID.randomUUID(), tenant, admission.dispatch().dispatchEventId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause().hasMessageContaining("HRM-013 outbox snapshot is immutable");
        assertThatThrownBy(() -> cloneAsDeliveredOutbox(
                tenant, admission.dispatch().dispatchEventId()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause().hasMessageContaining("HRM-013 outbox must start pending");
    }

    @Test
    void tenthAttemptPersistsResultUnknownAndClearsLease() {
        long tenant = 23;
        var admission = candidate(tenant, UUID.randomUUID(), "VOLUNTARY", "2".repeat(64));
        store.admit(admission);
        store.enqueueTerminal(new Hrm013TimeTaskDispatchStore.TerminalCommand(
                tenant, admission.dispatch().dispatchEventId(), "FAILED",
                UUID.randomUUID(), NOW.plusSeconds(7),
                admission.dispatch().correlationId()));
        var claim = store.claimCallbacks(tenant, 1,
                new Hrm013TimeTaskDispatchStore.DurationLease("terminal-worker", 30))
                .getFirst();

        admin.execute("""
                ALTER TABLE hris_hrm013_time_task_receipt_outbox
                DISABLE TRIGGER hris_hrm013_time_outbox_guard
                """);
        try {
            admin.update("""
                    UPDATE hris_hrm013_time_task_receipt_outbox
                       SET attempt_count=10,
                           locked_by='terminal-worker',
                           locked_until=CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                           delivery_status='SENDING'
                     WHERE tenant_id=? AND outbox_id=?
                    """, tenant, claim.outboxId());
        } finally {
            admin.execute("""
                    ALTER TABLE hris_hrm013_time_task_receipt_outbox
                    ENABLE TRIGGER hris_hrm013_time_outbox_guard
                    """);
        }

        store.markRetry(tenant, claim.outboxId(), "terminal-worker", 10,
                "HRM008_DELIVERY_UNAVAILABLE", 17);
        Map<String, Object> terminal = admin.queryForMap("""
                SELECT delivery_status,last_error_code,locked_by,locked_until,attempt_count
                  FROM hris_hrm013_time_task_receipt_outbox
                 WHERE tenant_id=? AND outbox_id=?
                """, tenant, claim.outboxId());
        assertThat(terminal)
                .containsEntry("delivery_status", "DEAD")
                .containsEntry("last_error_code", "RESULT_UNKNOWN")
                .containsEntry("attempt_count", 10);
        assertThat(terminal.get("locked_by")).isNull();
        assertThat(terminal.get("locked_until")).isNull();
    }

    @Test
    void v15OutboxLeaseStateMachineRejectsDirectSqlBypasses() {
        UUID futurePending = pendingOutbox(31, "3".repeat(64));
        withoutOutboxGuard(() -> admin.update("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET available_at=CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                 WHERE tenant_id=? AND outbox_id=?
                """, 31L, futurePending));
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='SENDING',attempt_count=attempt_count + 1,
                       locked_by='direct-sql-worker',
                       locked_until=CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                       updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 31L, futurePending);

        UUID exhaustedPending = pendingOutbox(32, "4".repeat(64));
        withoutOutboxGuard(() -> admin.update("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET attempt_count=10
                 WHERE tenant_id=? AND outbox_id=?
                """, 32L, exhaustedPending));
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='SENDING',attempt_count=10,
                       locked_by='direct-sql-worker',
                       locked_until=CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                       updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 32L, exhaustedPending);

        UUID oversizedLease = pendingOutbox(33, "5".repeat(64));
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='SENDING',attempt_count=attempt_count + 1,
                       locked_by='direct-sql-worker',
                       locked_until=CURRENT_TIMESTAMP + INTERVAL '301 seconds',
                       updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 33L, oversizedLease);

        var active = claimedOutbox(34, "6".repeat(64), "direct-sql-worker");
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET attempt_count=attempt_count + 1,
                       locked_until=CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                       updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 34L, active.outboxId());
        assertThatThrownBy(() -> store.markRetry(
                34L, active.outboxId(), "direct-sql-worker", active.attemptCount(),
                "RESULT_UNKNOWN", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='RETRY',available_at=CURRENT_TIMESTAMP,
                       locked_by=NULL,locked_until=NULL,
                       last_error_code='RESULT_UNKNOWN',updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 34L, active.outboxId());
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='RETRY',
                       available_at=CURRENT_TIMESTAMP - INTERVAL '1 second',
                       locked_by=NULL,locked_until=NULL,
                       last_error_code='DELIVERY_UNAVAILABLE',updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 34L, active.outboxId());
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='RETRY',
                       available_at=CURRENT_TIMESTAMP + INTERVAL '301 seconds',
                       locked_by=NULL,locked_until=NULL,
                       last_error_code='DELIVERY_UNAVAILABLE',updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 34L, active.outboxId());
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='DEAD',locked_by=NULL,locked_until=NULL,
                       last_error_code='RESULT_UNKNOWN',updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 34L, active.outboxId());

        withoutOutboxGuard(() -> admin.update("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET locked_until=CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE tenant_id=? AND outbox_id=?
                """, 34L, active.outboxId()));
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='DELIVERED',delivered_at=CURRENT_TIMESTAMP,
                       locked_by=NULL,locked_until=NULL,last_error_code=NULL,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 34L, active.outboxId());
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='RETRY',available_at=CURRENT_TIMESTAMP,
                       locked_by=NULL,locked_until=NULL,
                       last_error_code='DELIVERY_UNAVAILABLE',updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 34L, active.outboxId());

        var finalAttempt = claimedOutbox(35, "7".repeat(64), "terminal-worker");
        withoutOutboxGuard(() -> admin.update("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET attempt_count=10,
                       locked_until=CURRENT_TIMESTAMP + INTERVAL '300 seconds'
                 WHERE tenant_id=? AND outbox_id=?
                """, 35L, finalAttempt.outboxId()));
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='RETRY',available_at=CURRENT_TIMESTAMP,
                       locked_by=NULL,locked_until=NULL,
                       last_error_code='DELIVERY_UNAVAILABLE',updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 35L, finalAttempt.outboxId());
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='DEAD',locked_by=NULL,locked_until=NULL,
                       last_error_code='DELIVERY_UNAVAILABLE',updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 35L, finalAttempt.outboxId());
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='DEAD',
                       available_at=available_at + INTERVAL '1 second',
                       locked_by=NULL,locked_until=NULL,
                       last_error_code='RESULT_UNKNOWN',updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 35L, finalAttempt.outboxId());
        withoutOutboxGuard(() -> admin.update("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET locked_until=CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE tenant_id=? AND outbox_id=?
                """, 35L, finalAttempt.outboxId()));
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='DEAD',locked_by=NULL,locked_until=NULL,
                       last_error_code='DELIVERY_UNAVAILABLE',updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 35L, finalAttempt.outboxId());
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='DEAD',
                       available_at=available_at + INTERVAL '1 second',
                       locked_by=NULL,locked_until=NULL,
                       last_error_code='RESULT_UNKNOWN',updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 35L, finalAttempt.outboxId());
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='DEAD',locked_by=NULL,locked_until=NULL,
                       last_error_code='RESULT_UNKNOWN',
                       updated_at=CURRENT_TIMESTAMP + INTERVAL '1 second'
                 WHERE tenant_id=? AND outbox_id=?
                """, 35L, finalAttempt.outboxId());

        Object availableBeforeRecovery = admin.queryForObject("""
                SELECT available_at
                  FROM hris_hrm013_time_task_receipt_outbox
                 WHERE tenant_id=? AND outbox_id=?
                """, Object.class, 35L, finalAttempt.outboxId());
        assertThat(store.claimCallbacks(35L, 1,
                new Hrm013TimeTaskDispatchStore.DurationLease("recovery-worker", 30)))
                .isEmpty();
        Map<String, Object> recovered = admin.queryForMap("""
                SELECT delivery_status,last_error_code,locked_by,locked_until,
                       attempt_count,available_at,(updated_at <= CURRENT_TIMESTAMP) AS db_timed
                  FROM hris_hrm013_time_task_receipt_outbox
                 WHERE tenant_id=? AND outbox_id=?
                """, 35L, finalAttempt.outboxId());
        assertThat(recovered)
                .containsEntry("delivery_status", "DEAD")
                .containsEntry("last_error_code", "RESULT_UNKNOWN")
                .containsEntry("attempt_count", 10)
                .containsEntry("available_at", availableBeforeRecovery)
                .containsEntry("db_timed", true);
        assertThat(recovered.get("locked_by")).isNull();
        assertThat(recovered.get("locked_until")).isNull();
    }

    @Test
    void v15OutboxTransitionTimestampsAreBoundToDatabaseTime() {
        UUID insertion = pendingOutbox(41, "d".repeat(64));
        withoutOutboxGuard(() -> admin.update("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET available_at=CURRENT_TIMESTAMP + INTERVAL '1 second',
                       created_at=CURRENT_TIMESTAMP + INTERVAL '1 second',
                       updated_at=CURRENT_TIMESTAMP + INTERVAL '1 second'
                 WHERE tenant_id=? AND outbox_id=?
                """, 41L, insertion));
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO hris_hrm013_time_task_receipt_outbox
                SELECT * FROM hris_hrm013_time_task_receipt_outbox
                 WHERE tenant_id=? AND outbox_id=?
                """, 41L, insertion))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause().hasMessageContaining("HRM-013 outbox must start pending");

        UUID pending = pendingOutbox(36, "8".repeat(64));
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='SENDING',attempt_count=attempt_count + 1,
                       locked_by='future-clock-worker',
                       locked_until=CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                       updated_at=CURRENT_TIMESTAMP + INTERVAL '1 second'
                 WHERE tenant_id=? AND outbox_id=?
                """, 36L, pending);

        var expired = claimedOutbox(37, "9".repeat(64), "expired-worker");
        withoutOutboxGuard(() -> admin.update("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET locked_until=CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE tenant_id=? AND outbox_id=?
                """, 37L, expired.outboxId()));
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET attempt_count=attempt_count + 1,locked_by='reclaim-worker',
                       locked_until=CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                       updated_at=CURRENT_TIMESTAMP + INTERVAL '1 second'
                 WHERE tenant_id=? AND outbox_id=?
                """, 37L, expired.outboxId());

        var delivered = claimedOutbox(38, "a".repeat(64), "delivery-worker");
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='DELIVERED',
                       delivered_at=CURRENT_TIMESTAMP + INTERVAL '1 second',
                       locked_by=NULL,locked_until=NULL,last_error_code=NULL,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND outbox_id=?
                """, 38L, delivered.outboxId());
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='DELIVERED',delivered_at=CURRENT_TIMESTAMP,
                       locked_by=NULL,locked_until=NULL,last_error_code=NULL,
                       updated_at=CURRENT_TIMESTAMP + INTERVAL '1 second'
                 WHERE tenant_id=? AND outbox_id=?
                """, 38L, delivered.outboxId());

        var retry = claimedOutbox(39, "b".repeat(64), "retry-worker");
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='RETRY',
                       available_at=CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                       locked_by=NULL,locked_until=NULL,
                       last_error_code='DELIVERY_UNAVAILABLE',
                       updated_at=CURRENT_TIMESTAMP + INTERVAL '1 second'
                 WHERE tenant_id=? AND outbox_id=?
                """, 39L, retry.outboxId());

        var dead = claimedOutbox(40, "c".repeat(64), "dead-worker");
        withoutOutboxGuard(() -> admin.update("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET attempt_count=10
                 WHERE tenant_id=? AND outbox_id=?
                """, 40L, dead.outboxId()));
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='DEAD',locked_by=NULL,locked_until=NULL,
                       last_error_code='RESULT_UNKNOWN',
                       updated_at=CURRENT_TIMESTAMP + INTERVAL '1 second'
                 WHERE tenant_id=? AND outbox_id=?
                """, 40L, dead.outboxId());
        withoutOutboxGuard(() -> admin.update("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET locked_until=CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE tenant_id=? AND outbox_id=?
                """, 40L, dead.outboxId()));
        assertIllegalOutboxTransition("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='DEAD',locked_by=NULL,locked_until=NULL,
                       last_error_code='RESULT_UNKNOWN',
                       updated_at=CURRENT_TIMESTAMP + INTERVAL '1 second'
                 WHERE tenant_id=? AND outbox_id=?
                """, 40L, dead.outboxId());
    }

    private UUID pendingOutbox(long tenantId, String proofDigest) {
        var admission = candidate(
                tenantId, UUID.randomUUID(), "VOLUNTARY", proofDigest);
        store.admit(admission);
        store.enqueueTerminal(new Hrm013TimeTaskDispatchStore.TerminalCommand(
                tenantId, admission.dispatch().dispatchEventId(), "COMPLETED",
                UUID.randomUUID(), NOW.plusSeconds(11),
                admission.dispatch().correlationId()));
        return admin.queryForObject("""
                SELECT outbox_id
                  FROM hris_hrm013_time_task_receipt_outbox
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, UUID.class, tenantId, admission.dispatch().dispatchEventId());
    }

    private Hrm013TimeTaskDispatchStore.CallbackRecord claimedOutbox(
            long tenantId, String proofDigest, String workerId) {
        pendingOutbox(tenantId, proofDigest);
        return store.claimCallbacks(tenantId, 1,
                new Hrm013TimeTaskDispatchStore.DurationLease(workerId, 300)).getFirst();
    }

    private void withoutOutboxGuard(Runnable fixtureMutation) {
        admin.execute("""
                ALTER TABLE hris_hrm013_time_task_receipt_outbox
                DISABLE TRIGGER hris_hrm013_time_outbox_guard
                """);
        try {
            fixtureMutation.run();
        } finally {
            admin.execute("""
                    ALTER TABLE hris_hrm013_time_task_receipt_outbox
                    ENABLE TRIGGER hris_hrm013_time_outbox_guard
                    """);
        }
    }

    private void assertIllegalOutboxTransition(String sql, Object... arguments) {
        assertThatThrownBy(() -> admin.update(sql, arguments))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause().hasMessageContaining("Illegal HRM-013 outbox transition");
    }

    private void cloneAsTerminalInbox(
            Hrm013TimeTaskDispatchStore.AdmissionCandidate admission) {
        admin.update("""
                INSERT INTO hris_hrm013_time_task_dispatch_inbox(
                    tenant_id,dispatch_event_id,case_public_id,task_public_id,task_type,
                    task_owner_revision,task_version,worker_public_id,relationship_public_id,
                    separation_type,effective_date,last_working_date,dispatched_at,
                    payload_sha256,request_body_sha256,proof_sha256,exact_request_body,
                    exact_receipt_body,receipt_proof,tenant_grant_revision,
                    authorization_revision,grant_use_mode,correlation_id,status,
                    drain_claims_json,admitted_at,terminal_at,created_at,updated_at)
                SELECT tenant_id,?,case_public_id,?,task_type,task_owner_revision,task_version,
                    worker_public_id,relationship_public_id,separation_type,effective_date,
                    last_working_date,dispatched_at,payload_sha256,?, ?,exact_request_body,
                    exact_receipt_body,receipt_proof,tenant_grant_revision,
                    authorization_revision,grant_use_mode,correlation_id,'COMPLETED',
                    drain_claims_json,admitted_at,CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                  FROM hris_hrm013_time_task_dispatch_inbox
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, UUID.randomUUID(), UUID.randomUUID(), "7".repeat(64),
                "8".repeat(64), admission.tenantId(),
                admission.dispatch().dispatchEventId());
    }

    private void insertForgedPendingOutbox(
            Hrm013TimeTaskDispatchStore.AdmissionCandidate admission) {
        inRuntimeTenant(admission.tenantId(), () -> runtime.update("""
                INSERT INTO hris_hrm013_time_task_receipt_outbox(
                    tenant_id,outbox_id,dispatch_event_id,callback_event_id,
                    case_public_id,task_public_id,owner_revision,status,
                    external_receipt_public_id,originating_payload_sha256,
                    terminal_parent_created_at,occurred_at,correlation_id,
                    exact_callback_body,callback_payload_sha256,grant_use_mode,
                    drain_claims_json,available_at)
                SELECT tenant_id,:outbox,dispatch_event_id,:callback,
                    case_public_id,task_public_id,task_owner_revision + 1,'COMPLETED',
                    :externalReceipt,payload_sha256,dispatched_at,CURRENT_TIMESTAMP,
                    correlation_id,convert_to('{}','UTF8'),:callbackDigest,
                    grant_use_mode,drain_claims_json,CURRENT_TIMESTAMP
                  FROM hris_hrm013_time_task_dispatch_inbox
                 WHERE tenant_id=:tenant AND dispatch_event_id=:dispatch
                """, Map.of(
                        "outbox", UUID.randomUUID(),
                        "callback", UUID.randomUUID(),
                        "externalReceipt", UUID.randomUUID(),
                        "callbackDigest", "6".repeat(64),
                        "tenant", admission.tenantId(),
                        "dispatch", admission.dispatch().dispatchEventId())));
    }

    private void inRuntimeTenant(long tenantId, Runnable action) {
        runtimeTransactions.executeWithoutResult(status -> {
            String configured = runtime.queryForObject(
                    "SELECT set_config('dwp.tenant_id', :tenant, true)",
                    Map.of("tenant", Long.toString(tenantId)), String.class);
            assertThat(configured).isEqualTo(Long.toString(tenantId));
            action.run();
        });
    }

    private void cloneAsDeliveredOutbox(long tenantId, UUID dispatchEventId) {
        admin.update("""
                INSERT INTO hris_hrm013_time_task_receipt_outbox
                SELECT tenant_id,outbox_id,dispatch_event_id,callback_event_id,
                    case_public_id,task_public_id,owner_revision,status,
                    external_receipt_public_id,originating_payload_sha256,
                    terminal_parent_created_at,occurred_at,correlation_id,
                    exact_callback_body,callback_payload_sha256,grant_use_mode,
                    drain_claims_json,'DELIVERED',attempt_count,available_at,
                    NULL,NULL,CURRENT_TIMESTAMP,last_error_code,created_at,updated_at
                  FROM hris_hrm013_time_task_receipt_outbox
                 WHERE tenant_id=? AND dispatch_event_id=?
                """, tenantId, dispatchEventId);
    }

    private static Hrm013TimeTaskDispatchStore.AdmissionCandidate candidate(
            long tenant, UUID task, String separationType, String proofDigest) {
        UUID event = Hrm013TaskDispatchV1.dispatchEventId(
                tenant, task, 1, Hrm013TaskDispatchV1.TIME.bindingId());
        UUID caseId = UUID.nameUUIDFromBytes(("case-" + task).getBytes(StandardCharsets.UTF_8));
        UUID correlation = UUID.nameUUIDFromBytes(
                ("correlation-" + task).getBytes(StandardCharsets.UTF_8));
        UUID worker = UUID.nameUUIDFromBytes(("worker-" + task).getBytes(StandardCharsets.UTF_8));
        UUID relationship = UUID.nameUUIDFromBytes(
                ("relationship-" + task).getBytes(StandardCharsets.UTF_8));
        var body = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch(
                        Hrm013TaskDispatchV1.SCHEMA_NAME,
                        Hrm013TaskDispatchV1.SCHEMA_VERSION, event, caseId,
                        task, "TIME_FINALIZATION", 1, 1, NOW,
                        "0".repeat(64), correlation, worker,
                        relationship, separationType,
                        LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 30)));
        byte[] exact = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.TIME, tenant, body);
        var receipt = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                        event, task, body.payloadSha256(),
                        Hrm013TaskDispatchV1.requestBodySha256(exact), NOW,
                        "0".repeat(64), body.correlationId()));
        return new Hrm013TimeTaskDispatchStore.AdmissionCandidate(
                tenant, body, exact, Hrm013TaskDispatchV1.requestBodySha256(exact),
                proofDigest, 1, 1, "NEW_WORK", null,
                Hrm013TaskDispatchV1.encode(receipt), "receipt-proof-" + proofDigest,
                NOW, Instant.now().plusSeconds(60));
    }

    private DataSource dataSource(String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(username); source.setPassword(password);
        return source;
    }

    private static String migration() throws IOException {
        String resource = "db/migration/V15__hrm013_time_task_dispatch_inbox.sql";
        try (var stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("missing " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
