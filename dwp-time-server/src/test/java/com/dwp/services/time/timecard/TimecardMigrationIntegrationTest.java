package com.dwp.services.time.timecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.dwp.services.time.timecard.TimecardModels.CommandKind;
import com.dwp.services.time.timecard.TimecardModels.PopulationKind;
import com.dwp.services.time.timecard.TimecardModels.StoredTimecardReceipt;
import com.dwp.services.time.timecard.TimecardModels.TimecardAction;
import com.dwp.services.time.timecard.TimecardModels.TimecardCommand;
import com.dwp.services.time.timecard.TimecardModels.TimecardCorrectionCommandV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardCorrectionEntryV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardDecision;
import com.dwp.services.time.timecard.TimecardModels.TimecardDecisionCommandV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardMaterialization;
import com.dwp.services.time.timecard.TimecardModels.TimecardState;
import com.dwp.services.time.timecard.TimecardModels.TimecardSubmitCommandV1;
import com.dwp.services.time.timecard.TimecardModels.VerifiedTimecardRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimecardMigrationIntegrationTest {

    private static final String RUNTIME_ROLE = "tim_timecard_runtime";
    private static final String RUNTIME_PASSWORD = "tim-timecard-runtime-test";
    private static final String PUBLISHER_ROLE = "tim_timecard_publisher";
    private static final String PUBLISHER_PASSWORD = "tim-timecard-publisher-test";
    private static final String MIGRATION_ROLE = "tim_timecard_migrator";
    private static final String MIGRATION_PASSWORD = "tim-timecard-migrator-test";
    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");

    private PostgreSQLContainer<?> postgres;
    private DataSource adminDataSource;
    private JdbcTemplate admin;
    private DataSource runtimeDataSource;
    private JdbcTemplate runtime;
    private JdbcTimecardRepository repository;
    private TimecardService service;

    @BeforeAll
    void migrateCleanV11() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker is required for the TIM-017 clean and V10-upgrade gate")
                .isTrue();
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        adminDataSource = dataSource(postgres.getDatabaseName(),
                postgres.getUsername(), postgres.getPassword());
        admin = new JdbcTemplate(adminDataSource);
        admin.execute("CREATE ROLE " + RUNTIME_ROLE + " LOGIN PASSWORD '"
                + RUNTIME_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        admin.execute("CREATE ROLE " + PUBLISHER_ROLE + " LOGIN PASSWORD '"
                + PUBLISHER_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        admin.execute("CREATE ROLE " + MIGRATION_ROLE + " LOGIN PASSWORD '"
                + MIGRATION_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        admin.execute("ALTER DATABASE " + postgres.getDatabaseName()
                + " OWNER TO " + MIGRATION_ROLE);
        admin.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
        DataSource migrationDataSource = dataSource(
                postgres.getDatabaseName(), MIGRATION_ROLE, MIGRATION_PASSWORD);
        assertThat(flyway(migrationDataSource, null).migrate().migrationsExecuted)
                .isGreaterThanOrEqualTo(11);
        runtimeDataSource = dataSource(postgres.getDatabaseName(),
                RUNTIME_ROLE, RUNTIME_PASSWORD);
        runtime = new JdbcTemplate(runtimeDataSource);
        repository = repository(runtimeDataSource);
        service = new TimecardService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterAll
    void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void cleanV11HasValidatedProfilesAndExactPublisherAcl() {
        assertThat(admin.queryForMap("""
                SELECT rolsuper, rolbypassrls
                  FROM pg_roles WHERE rolname = ?
                """, MIGRATION_ROLE)).containsEntry("rolsuper", false)
                .containsEntry("rolbypassrls", false);
        assertThat(admin.queryForObject("""
                SELECT pg_get_userbyid(relowner)
                  FROM pg_class WHERE oid = 'public.tme_time_ledger_entries'::regclass
                """, String.class)).isEqualTo(MIGRATION_ROLE);
        assertThat(admin.queryForObject("""
                SELECT relforcerowsecurity
                  FROM pg_class WHERE oid = 'public.tme_time_ledger_entries'::regclass
                """, Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conname IN (
                    'ck_tme_time_ledger_source_profile',
                    'ck_tme_time_ledger_reversal_attempt')
                   AND convalidated
                """, Integer.class)).isEqualTo(2);
        assertThat(admin.queryForList("""
                SELECT privilege_type
                  FROM information_schema.role_table_grants
                 WHERE grantee = ? AND table_schema = 'public'
                   AND table_name = 'tme_timecard_outbox_events'
                 ORDER BY privilege_type
                """, String.class, PUBLISHER_ROLE)).containsExactly("SELECT");
        assertThat(admin.queryForList("""
                SELECT column_name || ':' || privilege_type
                  FROM information_schema.role_column_grants
                 WHERE grantee = ? AND table_schema = 'public'
                   AND table_name = 'tme_timecard_outbox_events'
                   AND privilege_type = 'UPDATE'
                 ORDER BY column_name
                """, String.class, PUBLISHER_ROLE)).containsExactly(
                        "publish_attempts:UPDATE", "published_at:UPDATE");
    }

    @Test
    void goldenLifecycleIsAtomicIdempotentCasSafeAndEmitsOneMinimizedV1Event() {
        long tenant = 17_101L;
        UUID card = uuid("17101000-0000-4000-8000-000000000001");
        UUID worker = uuid("17101000-0000-4000-8000-000000000002");
        UUID workerSubject = uuid("17101000-0000-4000-8000-000000000003");
        UUID managerSubject = uuid("17101000-0000-4000-8000-000000000004");
        UUID managerWorker = uuid("17101000-0000-4000-8000-000000000005");
        materialize(repository, tenant, card, worker, 480, 11);

        VerifiedTimecardRequest correct = authority(
                tenant, 71, workerSubject, worker, TimecardAction.CORRECT,
                PopulationKind.SELF, "TIME_TIMECARD",
                Set.of("DATA.HR_TIME:CREATE"), worker, "a".repeat(64));
        TimecardCorrectionCommandV1 correction = new TimecardCorrectionCommandV1(
                "MISSED_CLOCK", List.of(new TimecardCorrectionEntryV1(
                        LocalDate.parse("2026-10-14"), "Regular / A|B", 60)));
        var corrected = service.correct(correct, card, "tim017-correct-0001", 0, correction);
        var replay = service.correct(correct, card, "tim017-correct-0001", 0, correction);
        assertThat(replay).isEqualTo(corrected);
        assertThat(service.get(authority(
                tenant, 71, workerSubject, worker, TimecardAction.VIEW,
                PopulationKind.SELF, "TIME_TIMECARD",
                Set.of("DATA.HR_TIME:VIEW"), worker, "a".repeat(64)), card))
                .satisfies(value -> {
                    assertThat(value.totalMinutes()).isEqualTo(540);
                    assertThat(value.ledgerRevision()).isEqualTo(12);
                    assertThat(value.version()).isEqualTo(1);
                    assertThat(value.state()).isEqualTo(TimecardState.OPEN);
                });

        int receiptsAfterCorrection = count(tenant, "tme_timecard_command_receipts");
        int versionsAfterCorrection = count(tenant, "tme_timecard_versions");
        int ledgerAfterCorrection = count(tenant, "tme_time_ledger_entries");
        assertThatThrownBy(() -> service.correct(
                correct, card, "tim017-correct-0001", 0,
                new TimecardCorrectionCommandV1("DIFFERENT", correction.entries())))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.correct(
                correct, card, "tim017-correct-0002", 0, correction))
                .isInstanceOf(BaseException.class);
        assertThat(count(tenant, "tme_timecard_command_receipts"))
                .isEqualTo(receiptsAfterCorrection);
        assertThat(count(tenant, "tme_timecard_versions"))
                .isEqualTo(versionsAfterCorrection);
        assertThat(count(tenant, "tme_time_ledger_entries"))
                .isEqualTo(ledgerAfterCorrection);

        VerifiedTimecardRequest submit = authority(
                tenant, 71, workerSubject, worker, TimecardAction.SUBMIT,
                PopulationKind.SELF, "TIME_TIMECARD",
                Set.of("DATA.HR_TIME:CREATE"), worker, "a".repeat(64));
        service.submit(submit, card, "tim017-submit--0001", 1,
                new TimecardSubmitCommandV1(null));
        VerifiedTimecardRequest decide = authority(
                tenant, 72, managerSubject, managerWorker, TimecardAction.DECIDE,
                PopulationKind.TARGET_POPULATION, "TIME_TIMECARD",
                Set.of("DATA.HR_TIME:APPROVE"), worker, "b".repeat(64));
        TimecardDecisionCommandV1 decision = new TimecardDecisionCommandV1(
                TimecardDecision.APPROVE, "VERIFIED", "restricted comment");
        var decided = service.decide(
                decide, card, "tim017-decide--0001", 2, decision);
        assertThat(service.decide(
                decide, card, "tim017-decide--0001", 2, decision)).isEqualTo(decided);

        assertThat(count(tenant, "tme_timecard_decisions")).isOne();
        assertThat(count(tenant, "tme_timecard_outbox_events")).isOne();
        Map<String, Object> outbox = tenantQuery(tenant, () -> admin.queryForMap("""
                SELECT schema_name, aggregate_type, aggregate_sequence,
                       partition_key, payload::text AS payload
                  FROM tme_timecard_outbox_events WHERE tenant_id = ?
                """, tenant));
        assertThat(outbox.get("schema_name")).isEqualTo("TimecardDecisionRecorded.v1");
        assertThat(outbox.get("aggregate_type")).isEqualTo("TIMECARD");
        assertThat(outbox.get("aggregate_sequence")).isEqualTo(3L);
        assertThat(outbox.get("partition_key")).isEqualTo(tenant + ":" + card);
        assertThat((String) outbox.get("payload"))
                .contains("timecardId", "workerId", "status", "ledgerRevision", "decisionAt")
                .doesNotContain("comment", "VERIFIED", "actor");
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM tme_timecard_outbox_events
                 WHERE schema_name = 'TimecardDecisionRecorded'
                """, Integer.class)).isZero();

        assertMalformedDecisionPayloadsRejected(
                tenant, card, worker, managerSubject, decided.receiptId());

        StoredTimecardReceipt stored = repository.findReceipt(tenant, corrected.receiptId())
                .orElseThrow();
        assertThat(stored.originatingPopulationKind()).isEqualTo(PopulationKind.SELF);
        assertThat(stored.originatingRequiredPermission())
                .isEqualTo("DATA.HR_TIME:CREATE");
        VerifiedTimecardRequest recovery = receiptAuthority(
                tenant, workerSubject, worker, worker,
                stored.populationScopeDigest(), 23, "DATA.HR_TIME:CREATE");
        assertThat(service.receipt(recovery, corrected.receiptId())).isEqualTo(corrected);
        int evidenceBeforeOpaqueFailures = count(
                tenant, "tme_timecard_command_receipts")
                + count(tenant, "tme_timecard_versions")
                + count(tenant, "tme_timecard_outbox_events");
        assertThatThrownBy(() -> service.receipt(receiptAuthority(
                tenant, managerSubject, managerWorker, worker,
                stored.populationScopeDigest(), 23, "DATA.HR_TIME:CREATE"),
                corrected.receiptId()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.receipt(receiptAuthority(
                tenant, workerSubject, worker, worker,
                "9".repeat(64), 23, "DATA.HR_TIME:CREATE"),
                corrected.receiptId()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.receipt(receiptAuthority(
                tenant, workerSubject, worker, worker,
                stored.populationScopeDigest(), 24, "DATA.HR_TIME:CREATE"),
                corrected.receiptId()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.receipt(receiptAuthority(
                tenant, workerSubject, worker, worker,
                stored.populationScopeDigest(), 23, "DATA.HR_TIME:UPDATE"),
                corrected.receiptId()))
                .isInstanceOf(BaseException.class);
        assertThat(count(tenant, "tme_timecard_command_receipts")
                + count(tenant, "tme_timecard_versions")
                + count(tenant, "tme_timecard_outbox_events"))
                .isEqualTo(evidenceBeforeOpaqueFailures);
        assertThat(repository.find(tenant + 1, card)).isEmpty();
    }

    @Test
    void makerCheckerAndClosedPeriodFailBeforeAnyEvidenceIsAppended() {
        long tenant = 17_102L;
        UUID card = uuid("17102000-0000-4000-8000-000000000001");
        UUID worker = uuid("17102000-0000-4000-8000-000000000002");
        UUID workerSubject = uuid("17102000-0000-4000-8000-000000000003");
        UUID makerSubject = uuid("17102000-0000-4000-8000-000000000004");
        UUID makerWorker = uuid("17102000-0000-4000-8000-000000000005");
        materialize(repository, tenant, card, worker, 480, 21);
        var targetCorrection = service.correct(authority(
                        tenant, 81, makerSubject, makerWorker, TimecardAction.CORRECT,
                        PopulationKind.TARGET_POPULATION, "TIME_TIMECARD",
                        Set.of("DATA.HR_TIME:UPDATE"), worker, "c".repeat(64)),
                card, "tim017-maker---001", 0,
                new TimecardCorrectionCommandV1("FIX", List.of(
                        new TimecardCorrectionEntryV1(
                                LocalDate.parse("2026-10-14"), "REGULAR", 1))));
        StoredTimecardReceipt targetStored = repository.findReceipt(
                tenant, targetCorrection.receiptId()).orElseThrow();
        assertThat(targetStored.originatingPopulationKind())
                .isEqualTo(PopulationKind.TARGET_POPULATION);
        assertThat(service.receipt(receiptAuthority(
                tenant, makerSubject, makerWorker, worker,
                targetStored.populationScopeDigest(), 23, "DATA.HR_TIME:UPDATE"),
                targetCorrection.receiptId())).isEqualTo(targetCorrection);
        assertThatThrownBy(() -> service.receipt(receiptAuthority(
                tenant, makerSubject, makerWorker, worker,
                targetStored.populationScopeDigest(), 23, "DATA.HR_TIME:CREATE"),
                targetCorrection.receiptId())).isInstanceOf(BaseException.class);
        service.submit(authority(
                        tenant, 82, workerSubject, worker, TimecardAction.SUBMIT,
                        PopulationKind.SELF, "TIME_TIMECARD",
                        Set.of("DATA.HR_TIME:CREATE"), worker, "d".repeat(64)),
                card, "tim017-worker--001", 1, new TimecardSubmitCommandV1(null));
        int decisions = count(tenant, "tme_timecard_decisions");
        assertThatThrownBy(() -> service.decide(authority(
                        tenant, 81, makerSubject, makerWorker, TimecardAction.DECIDE,
                        PopulationKind.TARGET_POPULATION, "TIME_TIMECARD",
                        Set.of("DATA.HR_TIME:APPROVE"), worker, "c".repeat(64)),
                card, "tim017-maker---002", 2,
                new TimecardDecisionCommandV1(
                        TimecardDecision.APPROVE, "OK", null)))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.decide(authority(
                        tenant, 82, workerSubject, worker, TimecardAction.DECIDE,
                        PopulationKind.TARGET_POPULATION, "TIME_TIMECARD",
                        Set.of("DATA.HR_TIME:APPROVE"), worker, "d".repeat(64)),
                card, "tim017-worker--002", 2,
                new TimecardDecisionCommandV1(
                        TimecardDecision.APPROVE, "OK", null)))
                .isInstanceOf(BaseException.class);
        assertThat(count(tenant, "tme_timecard_decisions")).isEqualTo(decisions);
        assertThat(count(tenant, "tme_timecard_outbox_events")).isZero();

        UUID closedCard = uuid("17102000-0000-4000-8000-000000000011");
        UUID closedWorker = uuid("17102000-0000-4000-8000-000000000012");
        materialize(repository, tenant, closedCard, closedWorker, 480, 23);
        assertThatThrownBy(() -> tenantUpdate(tenant, """
                UPDATE tme_timecards
                   SET row_version = 1, updated_at = ?, updated_by = 99
                 WHERE tenant_id = ? AND public_id = ?
                """, Timestamp.from(NOW), tenant, closedCard))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tenantUpdate(tenant, """
                UPDATE tme_timecards
                   SET state = 'SUBMITTED', total_minutes = 481,
                       submitted_by_subject_public_id = ?, row_version = 1,
                       updated_at = ?, updated_by = 99
                 WHERE tenant_id = ? AND public_id = ?
                """, workerSubject, Timestamp.from(NOW), tenant, closedCard))
                .isInstanceOf(RuntimeException.class);
        closeCard(tenant, closedCard, workerSubject, makerSubject);
        int before = count(tenant, "tme_timecard_command_receipts");
        assertThatThrownBy(() -> service.correct(authority(
                        tenant, 82, workerSubject, closedWorker, TimecardAction.CORRECT,
                        PopulationKind.SELF, "TIME_TIMECARD",
                        Set.of("DATA.HR_TIME:CREATE"), closedWorker, "d".repeat(64)),
                closedCard, "tim017-closed--001", 4,
                new TimecardCorrectionCommandV1("FIX", List.of(
                        new TimecardCorrectionEntryV1(
                                LocalDate.parse("2026-10-14"), "REGULAR", 1)))))
                .isInstanceOf(BaseException.class);
        assertThat(count(tenant, "tme_timecard_command_receipts")).isEqualTo(before);

        UUID successor = uuid("17102000-0000-4000-8000-000000000013");
        tenantQuery(tenant, () -> {
            runtime.update("""
                    UPDATE tme_timecards
                       SET state = 'SUPERSEDED', successor_timecard_public_id = ?,
                           row_version = 5, updated_at = ?, updated_by = 99
                     WHERE tenant_id = ? AND public_id = ?
                    """, successor, Timestamp.from(NOW), tenant, closedCard);
            runtime.update("""
                    INSERT INTO tme_timecards (
                        public_id, tenant_id, worker_public_id, period_start, period_end,
                        state, total_minutes, ledger_revision, period_closed,
                        row_version, created_at, updated_at, updated_by)
                    VALUES (?, ?, ?, '2026-10-13', '2026-10-19', 'OPEN', 480, 23,
                            FALSE, 0, ?, ?, 99)
                    """, successor, tenant, closedWorker,
                    Timestamp.from(NOW), Timestamp.from(NOW));
            return 1;
        });
        assertThat(repository.find(tenant, closedCard).orElseThrow().projection().state())
                .isEqualTo(TimecardState.SUPERSEDED);
        assertThat(repository.find(tenant, successor).orElseThrow().projection().state())
                .isEqualTo(TimecardState.OPEN);
    }

    @Test
    void seededV10UpgradePreservesHistoryCanonicalizesLegacyWriterAndSharesCounter()
            throws Exception {
        String database = "tim017_upgrade";
        admin.execute("CREATE DATABASE " + database + " OWNER " + MIGRATION_ROLE);
        DataSource upgradeAdminDataSource = dataSource(
                database, postgres.getUsername(), postgres.getPassword());
        JdbcTemplate upgradeAdmin = new JdbcTemplate(upgradeAdminDataSource);
        upgradeAdmin.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
        DataSource upgradeMigrationDataSource = dataSource(
                database, MIGRATION_ROLE, MIGRATION_PASSWORD);
        Flyway v10 = flyway(
                upgradeMigrationDataSource, MigrationVersion.fromVersion("10"));
        assertThat(v10.migrate().migrationsExecuted).isGreaterThanOrEqualTo(10);
        assertThat(upgradeAdmin.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                 WHERE success AND version = '10'
                   AND script = 'V10__tim_base_tfr_tim_010.sql'
                """, Integer.class)).isOne();
        long tenant = 17_103L;
        UUID worker = uuid("17103000-0000-4000-8000-000000000001");
        LegacyFixture legacy = seedLegacyV10(
                upgradeMigrationDataSource, tenant, worker);

        assertThat(flyway(upgradeMigrationDataSource, null).migrate().migrationsExecuted)
                .isOne();
        assertThat(upgradeAdmin.queryForObject("""
                SELECT relforcerowsecurity
                  FROM pg_class WHERE oid = 'public.tme_time_ledger_entries'::regclass
                """, Boolean.class)).isTrue();
        assertThat(upgradeAdmin.queryForObject("""
                SELECT source_public_id FROM tme_time_ledger_entries
                 WHERE tenant_id = ? AND public_id = ?
                """, UUID.class, tenant, legacy.firstLedgerId())).isEqualTo(legacy.runId());
        assertThat(upgradeAdmin.queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conname IN (
                    'ck_tme_time_ledger_source_profile',
                    'ck_tme_time_ledger_reversal_attempt') AND NOT convalidated
                """, Integer.class)).isEqualTo(2);
        assertThat(upgradeAdmin.queryForObject("""
                SELECT current_revision FROM tme_time_ledger_revision_counters
                 WHERE tenant_id = ? AND worker_public_id = ?
                """, Long.class, tenant, worker)).isOne();

        DataSource upgradeRuntimeDataSource = dataSource(
                database, RUNTIME_ROLE, RUNTIME_PASSWORD);
        JdbcTemplate upgradeRuntime = new JdbcTemplate(upgradeRuntimeDataSource);
        JdbcTimecardRepository upgradeRepository = repository(upgradeRuntimeDataSource);
        UUID card = uuid("17103000-0000-4000-8000-000000000010");
        materialize(upgradeRepository, tenant, card, worker, 480, 1);
        TimecardCommand correction = new TimecardCommand(
                CommandKind.CORRECT,
                authority(tenant, 91,
                        uuid("17103000-0000-4000-8000-000000000011"),
                        uuid("17103000-0000-4000-8000-000000000012"),
                        TimecardAction.CORRECT, PopulationKind.TARGET_POPULATION,
                        "TIME_TIMECARD", Set.of("DATA.HR_TIME:UPDATE"),
                        worker, "e".repeat(64)),
                card, "tim017-upgrade-0001", 0, "f".repeat(64), "FIX",
                List.of(new TimecardCorrectionEntryV1(
                        LocalDate.parse("2026-10-14"), "REGULAR_MINUTES", 1)),
                null, null, NOW);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var legacyInsert = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                appendAsLegacyV10Writer(
                        upgradeRuntimeDataSource, upgradeRuntime, tenant, worker, legacy);
                return true;
            });
            var correctionInsert = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return upgradeRepository.execute(correction);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(legacyInsert.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(correctionInsert.get(15, TimeUnit.SECONDS).status())
                    .isEqualTo("SUCCEEDED");
        }

        List<Long> revisions = tenantQuery(upgradeRuntimeDataSource, tenant,
                () -> upgradeRuntime.queryForList("""
                        SELECT ledger_revision FROM tme_time_ledger_entries
                         WHERE tenant_id = ? AND worker_public_id = ?
                         ORDER BY ledger_revision
                        """, Long.class, tenant, worker));
        assertThat(revisions).containsExactly(1L, 2L, 3L);
        assertThat(tenantQuery(upgradeRuntimeDataSource, tenant,
                () -> upgradeRuntime.queryForObject("""
                        SELECT current_revision FROM tme_time_ledger_revision_counters
                         WHERE tenant_id = ? AND worker_public_id = ?
                        """, Long.class, tenant, worker))).isEqualTo(3L);
        assertThat(tenantQuery(upgradeRuntimeDataSource, tenant,
                () -> upgradeRuntime.queryForObject("""
                        SELECT source_public_id FROM tme_time_ledger_entries
                         WHERE tenant_id = ? AND public_id = ?
                        """, UUID.class, tenant, legacy.secondLedgerId())))
                .isEqualTo(legacy.attemptId());
    }

    @Test
    void v10CanonicalZeroQuantityNullReasonReversalIsPreservedButRejectedForNewRows() {
        String database = "tim017_zero_reversal_upgrade";
        admin.execute("CREATE DATABASE " + database + " OWNER " + MIGRATION_ROLE);
        DataSource upgradeAdminDataSource = dataSource(
                database, postgres.getUsername(), postgres.getPassword());
        JdbcTemplate upgradeAdmin = new JdbcTemplate(upgradeAdminDataSource);
        upgradeAdmin.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
        DataSource migrationDataSource = dataSource(
                database, MIGRATION_ROLE, MIGRATION_PASSWORD);
        assertThat(flyway(migrationDataSource, MigrationVersion.fromVersion("10"))
                .migrate().migrationsExecuted).isGreaterThanOrEqualTo(10);

        long tenant = 17_104L;
        UUID worker = uuid("17104000-0000-4000-8000-000000000001");
        LegacyFixture legacy = seedLegacyV10(
                migrationDataSource, tenant, worker, false, true);
        assertThat(upgradeAdmin.queryForObject("""
                SELECT count(*) FROM tme_time_ledger_entries
                 WHERE tenant_id = ?
                   AND source_type IN ('INTERPRETATION', 'REVERSAL')
                   AND source_public_id IS DISTINCT FROM interpretation_attempt_public_id
                """, Integer.class, tenant)).isZero();

        assertThat(flyway(migrationDataSource, null).migrate().migrationsExecuted)
                .isOne();
        assertThat(upgradeAdmin.queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conname IN (
                    'ck_tme_time_ledger_source_profile',
                    'ck_tme_time_ledger_reversal_attempt') AND NOT convalidated
                """, Integer.class)).isEqualTo(2);
        assertThat(upgradeAdmin.queryForObject("""
                SELECT quantity FROM tme_time_ledger_entries
                 WHERE tenant_id = ? AND public_id = ?
                """, BigDecimal.class, tenant, legacy.zeroReversalId()))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(upgradeAdmin.queryForObject("""
                SELECT reason_code IS NULL FROM tme_time_ledger_entries
                 WHERE tenant_id = ? AND public_id = ?
                """, Boolean.class, tenant, legacy.zeroReversalId())).isTrue();

        DataSource runtimeDataSource = dataSource(
                database, RUNTIME_ROLE, RUNTIME_PASSWORD);
        JdbcTemplate runtimeJdbc = new JdbcTemplate(runtimeDataSource);
        UUID rejectedReversal = uuid("17104000-0000-4000-8000-000000000022");
        assertThatThrownBy(() -> tenantQuery(runtimeDataSource, tenant, () ->
                runtimeJdbc.update("""
                        INSERT INTO tme_time_ledger_entries (
                            public_id, tenant_id, worker_public_id, local_work_date,
                            pay_code, unit, quantity, source_type, source_public_id,
                            interpretation_attempt_public_id, reverses_entry_public_id,
                            reason_code, ledger_revision, input_digest, rule_digest,
                            posted_at, posted_by, correlation_id)
                        VALUES (?, ?, ?, '2026-10-14', 'OVERTIME_MINUTES', 'MINUTE', -60,
                                'REVERSAL', ?, ?, ?, NULL, 4, ?, ?, ?, 91, ?)
                        """, rejectedReversal, tenant, worker, legacy.attemptId(),
                        legacy.attemptId(), legacy.negativeBaseLedgerId(),
                        "c".repeat(64), "a".repeat(64),
                        Timestamp.from(NOW.plusSeconds(3)),
                        uuid("17103000-0000-4000-8000-000000000009"))))
                .isInstanceOf(RuntimeException.class);
        assertThat(upgradeAdmin.queryForObject("""
                SELECT count(*) FROM tme_time_ledger_entries
                 WHERE tenant_id = ? AND public_id = ?
                """, Integer.class, tenant, rejectedReversal)).isZero();
        assertThat(upgradeAdmin.queryForObject("""
                SELECT current_revision FROM tme_time_ledger_revision_counters
                 WHERE tenant_id = ? AND worker_public_id = ?
                """, Long.class, tenant, worker)).isEqualTo(3L);
        assertThat(upgradeAdmin.queryForObject("""
                SELECT relforcerowsecurity
                  FROM pg_class WHERE oid = 'public.tme_time_ledger_entries'::regclass
                """, Boolean.class)).isTrue();
    }

    private LegacyFixture seedLegacyV10(DataSource source, long tenant, UUID worker) {
        return seedLegacyV10(source, tenant, worker, true, false);
    }

    private LegacyFixture seedLegacyV10(
            DataSource source,
            long tenant,
            UUID worker,
            boolean useLegacyRunSource,
            boolean includeZeroReversal) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)",
                    String.class, Long.toString(tenant));
            UUID rule = uuid("17103000-0000-4000-8000-000000000002");
            UUID run = uuid("17103000-0000-4000-8000-000000000003");
            UUID attempt = uuid("17103000-0000-4000-8000-000000000004");
            UUID ledgerOne = uuid("17103000-0000-4000-8000-000000000005");
            UUID ledgerTwo = uuid("17103000-0000-4000-8000-000000000006");
            UUID zeroReversal = uuid("17103000-0000-4000-8000-000000000020");
            UUID negativeBase = uuid("17103000-0000-4000-8000-000000000021");
            jdbc.update("""
                    INSERT INTO tme_interpretation_rule_versions (
                        public_id, tenant_id, revision, effective_from, effective_to,
                        lifecycle_state, registry_id, registry_version, rule_payload,
                        rule_digest, published_at)
                    VALUES (?, ?, 1, '2026-01-01', '2027-01-01', 'PUBLISHED',
                            'TIM_RULE_AST_V1', 1, CAST('[{}]' AS jsonb), ?, ?)
                    """, rule, tenant, "a".repeat(64), Timestamp.from(NOW));
            Long runInternal = jdbc.queryForObject("""
                    INSERT INTO tme_interpretation_runs (
                        public_id, tenant_id, subject_principal_public_id,
                        period_start, period_end, as_of, run_scope, worker_scope_digest,
                        status, rule_version_public_id, rule_version_revision,
                        input_snapshot_digest, rule_snapshot_digest, output_digest,
                        requested_idempotency_key, attempt_no, ledger_delta_applied,
                        correlation_id, created_at, created_by)
                    VALUES (?, ?, ?, '2026-10-13', '2026-10-19', ?, 'SELF', ?,
                            'SUCCEEDED', ?, 1, ?, ?, ?, ?, 1, TRUE, ?, ?, 91)
                    RETURNING interpretation_run_id
                    """, Long.class, run, tenant,
                    uuid("17103000-0000-4000-8000-000000000007"), Timestamp.from(NOW),
                    "b".repeat(64), rule, "c".repeat(64), "a".repeat(64),
                    "d".repeat(64), uuid("17103000-0000-4000-8000-000000000008"),
                    uuid("17103000-0000-4000-8000-000000000009"), Timestamp.from(NOW));
            jdbc.update("""
                    INSERT INTO tme_interpretation_attempts (
                        public_id, tenant_id, interpretation_run_id, attempt_no,
                        status, output_digest, started_at, finished_at)
                    VALUES (?, ?, ?, 1, 'SUCCEEDED', ?, ?, ?)
                    """, attempt, tenant, runInternal, "d".repeat(64),
                    Timestamp.from(NOW), Timestamp.from(NOW));
            jdbc.update("""
                    INSERT INTO tme_time_ledger_entries (
                        public_id, tenant_id, worker_public_id, local_work_date,
                        pay_code, unit, quantity, source_type, source_public_id,
                        interpretation_attempt_public_id, ledger_revision,
                        input_digest, rule_digest, posted_at, posted_by, correlation_id)
                    VALUES (?, ?, ?, '2026-10-14', 'REGULAR_MINUTES', 'MINUTE', 480,
                            'INTERPRETATION', ?, ?, 1, ?, ?, ?, 91, ?)
                    """, ledgerOne, tenant, worker,
                    useLegacyRunSource ? run : attempt, attempt,
                    "c".repeat(64), "a".repeat(64), Timestamp.from(NOW),
                    uuid("17103000-0000-4000-8000-000000000009"));
            if (includeZeroReversal) {
                jdbc.update("""
                        INSERT INTO tme_time_ledger_entries (
                            public_id, tenant_id, worker_public_id, local_work_date,
                            pay_code, unit, quantity, source_type, source_public_id,
                            interpretation_attempt_public_id, reverses_entry_public_id,
                            reason_code, ledger_revision, input_digest, rule_digest,
                            posted_at, posted_by, correlation_id)
                        VALUES (?, ?, ?, '2026-10-14', 'REGULAR_MINUTES', 'MINUTE', 0,
                                'REVERSAL', ?, ?, ?, NULL, 2,
                                ?, ?, ?, 91, ?)
                        """, zeroReversal, tenant, worker, attempt, attempt, ledgerOne,
                        "c".repeat(64), "a".repeat(64),
                        Timestamp.from(NOW.plusSeconds(1)),
                        uuid("17103000-0000-4000-8000-000000000009"));
                jdbc.update("""
                        INSERT INTO tme_time_ledger_entries (
                            public_id, tenant_id, worker_public_id, local_work_date,
                            pay_code, unit, quantity, source_type, source_public_id,
                            interpretation_attempt_public_id, ledger_revision,
                            input_digest, rule_digest, posted_at, posted_by, correlation_id)
                        VALUES (?, ?, ?, '2026-10-14', 'OVERTIME_MINUTES', 'MINUTE', 60,
                                'INTERPRETATION', ?, ?, 3, ?, ?, ?, 91, ?)
                        """, negativeBase, tenant, worker, attempt, attempt,
                        "c".repeat(64), "a".repeat(64),
                        Timestamp.from(NOW.plusSeconds(2)),
                        uuid("17103000-0000-4000-8000-000000000009"));
            }
            return new LegacyFixture(
                    run, attempt, ledgerOne, ledgerTwo,
                    includeZeroReversal ? zeroReversal : null,
                    includeZeroReversal ? negativeBase : null);
        });
    }

    private void appendAsLegacyV10Writer(
            DataSource source,
            JdbcTemplate jdbc,
            long tenant,
            UUID worker,
            LegacyFixture legacy) {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)",
                    String.class, Long.toString(tenant));
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    ignored -> {}, "tim-ledger:" + tenant + ":" + worker);
            Long revision = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(ledger_revision), 0) + 1
                      FROM tme_time_ledger_entries
                     WHERE tenant_id = ? AND worker_public_id = ?
                    """, Long.class, tenant, worker);
            jdbc.update("""
                    INSERT INTO tme_time_ledger_entries (
                        public_id, tenant_id, worker_public_id, local_work_date,
                        pay_code, unit, quantity, source_type, source_public_id,
                        interpretation_attempt_public_id, ledger_revision,
                        input_digest, rule_digest, posted_at, posted_by, correlation_id)
                    VALUES (?, ?, ?, '2026-10-14', 'OVERTIME_MINUTES', 'MINUTE', 1,
                            'INTERPRETATION', ?, ?, ?, ?, ?, ?, 91, ?)
                    """, legacy.secondLedgerId(), tenant, worker, legacy.runId(),
                    legacy.attemptId(), revision, "c".repeat(64), "a".repeat(64),
                    Timestamp.from(NOW.plusSeconds(1)),
                    uuid("17103000-0000-4000-8000-000000000009"));
        });
    }

    private JdbcTimecardRepository repository(DataSource source) {
        return new JdbcTimecardRepository(
                new JdbcTemplate(source), new ObjectMapper().findAndRegisterModules(),
                new DataSourceTransactionManager(source));
    }

    private void materialize(
            JdbcTimecardRepository target,
            long tenant,
            UUID card,
            UUID worker,
            int total,
            long revision) {
        target.materialize(new TimecardMaterialization(
                tenant, card, worker, LocalDate.parse("2026-10-13"),
                LocalDate.parse("2026-10-19"), total, revision, null, 99,
                uuid("17000000-0000-4000-8000-000000000099"),
                uuid("17000000-0000-4000-8000-000000000098"), NOW));
    }

    private VerifiedTimecardRequest authority(
            long tenant,
            long actor,
            UUID subject,
            UUID actorWorker,
            TimecardAction action,
            PopulationKind population,
            String purpose,
            Set<String> permissions,
            UUID authorizedWorker,
            String populationDigest) {
        return new VerifiedTimecardRequest(
                tenant, actor, subject, actorWorker, permissions, Set.of(action), population,
                uuid("17000000-0000-4000-8000-000000000097"), 9, 11,
                populationDigest, 17, purpose, 23, "psr-" + "b".repeat(64),
                NOW.plusSeconds(300), uuid("17000000-0000-4000-8000-000000000096"),
                Set.of(authorizedWorker), Set.of(), Set.of());
    }

    private VerifiedTimecardRequest receiptAuthority(
            long tenant,
            UUID subject,
            UUID actorWorker,
            UUID authorizedWorker,
            String populationDigest,
            long authorizationRevision,
            String originatingPermission) {
        return new VerifiedTimecardRequest(
                tenant, 71, subject, actorWorker,
                Set.of("DATA.HRIS_COMMAND_RECEIPT:VIEW", originatingPermission),
                Set.of(TimecardAction.RECEIPT_VIEW), PopulationKind.TARGET_POPULATION,
                uuid("17000000-0000-4000-8000-000000000097"), 9, 11,
                populationDigest, 17, "COMMAND_STATUS", authorizationRevision,
                "psr-" + "b".repeat(64), NOW.plusSeconds(300),
                uuid("17000000-0000-4000-8000-000000000096"),
                Set.of(authorizedWorker), Set.of(), Set.of());
    }

    private void closeCard(long tenant, UUID card, UUID submitter, UUID decider) {
        tenantUpdate(tenant, """
                UPDATE tme_timecards
                   SET state = 'SUBMITTED', submitted_by_subject_public_id = ?,
                       row_version = 1, updated_at = ?, updated_by = 99
                 WHERE tenant_id = ? AND public_id = ?
                """, submitter, Timestamp.from(NOW), tenant, card);
        tenantUpdate(tenant, """
                UPDATE tme_timecards
                   SET state = 'APPROVED', decided_by_subject_public_id = ?,
                       decided_at = ?, row_version = 2, updated_at = ?, updated_by = 99
                 WHERE tenant_id = ? AND public_id = ?
                """, decider, Timestamp.from(NOW), Timestamp.from(NOW), tenant, card);
        tenantUpdate(tenant, """
                UPDATE tme_timecards
                   SET state = 'LOCKED', row_version = 3,
                       updated_at = ?, updated_by = 99
                 WHERE tenant_id = ? AND public_id = ?
                """, Timestamp.from(NOW), tenant, card);
        tenantUpdate(tenant, """
                UPDATE tme_timecards
                   SET state = 'CLOSED', period_closed = TRUE, row_version = 4,
                       updated_at = ?, updated_by = 99
                 WHERE tenant_id = ? AND public_id = ?
                """, Timestamp.from(NOW), tenant, card);
    }

    private void assertMalformedDecisionPayloadsRejected(
            long tenant,
            UUID card,
            UUID worker,
            UUID subject,
            UUID receipt) {
        UUID version = uuid("17101000-0000-4000-8000-000000000020");
        UUID decision = uuid("17101000-0000-4000-8000-000000000021");
        tenantQuery(tenant, () -> {
            runtime.update("""
                    INSERT INTO tme_timecard_versions (
                        public_id, tenant_id, timecard_public_id, version, state,
                        total_minutes, ledger_revision, transition_type, reason_code,
                        command_receipt_public_id, actor_id, actor_subject_public_id,
                        occurred_at)
                    VALUES (?, ?, ?, 4, 'APPROVED', 540, 12, 'DECIDE', 'PAYLOAD_TEST',
                            ?, 72, ?, ?)
                    """, version, tenant, card, receipt, subject, Timestamp.from(NOW));
            runtime.update("""
                    INSERT INTO tme_timecard_decisions (
                        public_id, tenant_id, timecard_public_id, timecard_version,
                        decision, resulting_state, reason_code, decided_at, decided_by,
                        decided_by_subject_public_id, command_receipt_public_id)
                    VALUES (?, ?, ?, 4, 'APPROVE', 'APPROVED', 'PAYLOAD_TEST', ?, 72, ?, ?)
                    """, decision, tenant, card, Timestamp.from(NOW), subject, receipt);
            return 1;
        });

        String valid = """
                {"timecardId":"%s","workerId":"%s","status":"APPROVED",\
                "ledgerRevision":12,"decisionAt":"%s"}
                """.formatted(card, worker, NOW).strip();
        List<String> malformed = List.of(
                valid.replace("\"timecardId\":\"" + card + "\"", "\"timecardId\":null"),
                valid.replace(card.toString(), "not-a-uuid"),
                valid.replace("\"workerId\":\"" + worker + "\"", "\"workerId\":17"),
                valid.replace(worker.toString(), "also-not-a-uuid"),
                valid.replace("\"status\":\"APPROVED\"", "\"status\":null"),
                valid.replace("\"ledgerRevision\":12", "\"ledgerRevision\":null"),
                valid.replace("\"ledgerRevision\":12", "\"ledgerRevision\":\"12\""),
                valid.replace("\"ledgerRevision\":12", "\"ledgerRevision\":0"),
                valid.replace("\"ledgerRevision\":12", "\"ledgerRevision\":1.5"),
                valid.replace("\"decisionAt\":\"" + NOW + "\"", "\"decisionAt\":null"),
                valid.replace(NOW.toString(), "2026-99-99T99:99:99Z"));
        for (String payload : malformed) {
            assertThatThrownBy(() -> insertMalformedOutbox(
                    tenant, decision, card, payload))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(tenantQuery(tenant, () -> runtime.queryForObject("""
                SELECT count(*) FROM tme_timecard_outbox_events
                 WHERE tenant_id = ? AND decision_public_id = ?
                """, Integer.class, tenant, decision))).isZero();
    }

    private void insertMalformedOutbox(
            long tenant, UUID decision, UUID card, String payload) {
        tenantQuery(tenant, () -> runtime.update("""
                INSERT INTO tme_timecard_outbox_events (
                    tenant_id, event_id, decision_public_id, aggregate_type,
                    aggregate_public_id, aggregate_sequence, schema_name,
                    schema_version, partition_key, payload, payload_digest,
                    correlation_id, causation_id, occurred_at)
                VALUES (?, ?, ?, 'TIMECARD', ?, 4, 'TimecardDecisionRecorded.v1',
                        1, ?, CAST(? AS JSONB), ?, ?, ?, ?)
                """, tenant, uuid("17101000-0000-4000-8000-000000000022"),
                decision, card, tenant + ":" + card, payload, "a".repeat(64),
                uuid("17101000-0000-4000-8000-000000000023"),
                uuid("17101000-0000-4000-8000-000000000024"),
                Timestamp.from(NOW)));
    }

    private int count(long tenant, String table) {
        if (!Set.of(
                "tme_timecard_command_receipts", "tme_timecard_versions",
                "tme_time_ledger_entries", "tme_timecard_decisions",
                "tme_timecard_outbox_events").contains(table)) {
            throw new IllegalArgumentException("unapproved table");
        }
        return tenantQuery(tenant, () -> runtime.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id = ?",
                Integer.class, tenant));
    }

    private void tenantUpdate(long tenant, String sql, Object... arguments) {
        tenantQuery(tenant, () -> runtime.update(sql, arguments));
    }

    private <T> T tenantQuery(long tenant, java.util.function.Supplier<T> work) {
        return tenantQuery(runtimeDataSource, tenant, work);
    }

    private <T> T tenantQuery(
            DataSource source, long tenant, java.util.function.Supplier<T> work) {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        return tx.execute(status -> {
            new JdbcTemplate(source).queryForObject(
                    "SELECT set_config('dwp.tenant_id', ?, true)",
                    String.class, Long.toString(tenant));
            return work.get();
        });
    }

    private Flyway flyway(DataSource source, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .validateOnMigrate(true)
                .placeholderReplacement(true)
                .placeholders(Map.of(
                        "timeRuntimeRole", RUNTIME_ROLE,
                        "timeProjectionPublisherRole", PUBLISHER_ROLE));
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private DataSource dataSource(String database, String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl().replace(
                "/" + postgres.getDatabaseName(), "/" + database));
        source.setUser(username);
        source.setPassword(password);
        return source;
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private record LegacyFixture(
            UUID runId,
            UUID attemptId,
            UUID firstLedgerId,
            UUID secondLedgerId,
            UUID zeroReversalId,
            UUID negativeBaseLedgerId) {}
}
