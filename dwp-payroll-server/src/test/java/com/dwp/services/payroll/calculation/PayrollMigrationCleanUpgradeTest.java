package com.dwp.services.payroll.calculation;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.database.OwnerTriggerExecutionBoundaryGuard;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollCalculationAuthority;
import com.dwp.services.payroll.security.PayrollHighRiskCommandGuard;
import com.dwp.services.payroll.security.PayrollStepUpEvidence;
import com.dwp.services.payroll.security.PayrollStepUpHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class PayrollMigrationCleanUpgradeTest {

    private static final String RUNTIME_USER = "pay011_runtime";
    private static final String RUNTIME_PASSWORD = "pay011-runtime-password";
    private static final String PUBLISHER_USER = "pay011_projection_publisher";
    private static final String UPGRADE_DATABASE = "pay011_upgrade";
    private static final List<String> V50_TABLES = List.of(
            "pay_input_snapshots",
            "pay_input_snapshot_compensation_refs",
            "pay_payroll_runs",
            "pay_payroll_run_workers",
            "pay_payroll_run_attempts",
            "pay_worker_results",
            "pay_result_lines",
            "pay_calculation_trace_nodes",
            "pay_command_receipts",
            "pay_outbox_events");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource adminDataSource;
    private static DataSource runtimeDataSource;
    private static DataSource upgradeDataSource;
    private static JdbcTemplate admin;
    private static JdbcTemplate upgrade;

    @BeforeAll
    static void migrateCleanAndUpgradeDatabases() throws Exception {
        adminDataSource = dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME_USER
                    + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("CREATE ROLE " + PUBLISHER_USER
                    + " LOGIN NOINHERIT PASSWORD 'publisher-test-password'");
            statement.execute("CREATE DATABASE " + UPGRADE_DATABASE);
        }
        migrate(adminDataSource, null);
        runtimeDataSource = dataSource(
                POSTGRES.getJdbcUrl(), RUNTIME_USER, RUNTIME_PASSWORD);
        admin = new JdbcTemplate(adminDataSource);

        upgradeDataSource = dataSource(
                jdbcUrl(UPGRADE_DATABASE), POSTGRES.getUsername(), POSTGRES.getPassword());
        migrate(upgradeDataSource, MigrationVersion.fromVersion("49"));
        seedV49(upgradeDataSource);
        migrate(upgradeDataSource, null);
        upgrade = new JdbcTemplate(upgradeDataSource);
    }

    @AfterAll
    static void releaseReferences() {
        upgrade = null;
        admin = null;
        upgradeDataSource = null;
        runtimeDataSource = null;
        adminDataSource = null;
    }

    @BeforeEach
    void resetV50Facts() {
        admin.execute("TRUNCATE TABLE " + String.join(", ", V50_TABLES)
                + ", pay_compensation_plan_event_inbox"
                + ", pay_formula_versions, pay_foundation_configurations"
                + " RESTART IDENTITY CASCADE");
    }

    @Test
    void cleanV1ThroughV50CreatesOnlyLeasedTablesWithForcedRls() {
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version IN ('1', '49', '50') AND success
                """, Long.class)).isEqualTo(3L);
        assertThat(admin.queryForList("""
                SELECT relname FROM pg_class
                 WHERE relkind = 'r' AND relname IN (%s)
                 ORDER BY relname
                """.formatted(V50_TABLES.stream()
                        .map(name -> "'" + name + "'")
                        .reduce((left, right) -> left + "," + right).orElseThrow()),
                String.class)).containsExactlyElementsOf(V50_TABLES.stream().sorted().toList());
        assertThat(admin.queryForObject("""
                SELECT bool_and(relrowsecurity AND relforcerowsecurity)
                  FROM pg_class WHERE relname IN (%s)
                """.formatted(V50_TABLES.stream()
                        .map(name -> "'" + name + "'")
                        .reduce((left, right) -> left + "," + right).orElseThrow()),
                Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pg_policies
                 WHERE schemaname = current_schema()
                   AND tablename IN (%s)
                   AND qual LIKE '%%dwp.payroll_tenant_id%%'
                   AND with_check LIKE '%%dwp.payroll_tenant_id%%'
                """.formatted(V50_TABLES.stream()
                        .map(name -> "'" + name + "'")
                        .reduce((left, right) -> left + "," + right).orElseThrow()),
                Long.class)).isEqualTo(10L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger
                 WHERE NOT tgisinternal
                   AND tgname LIKE 'pay_%%_immutable'
                   AND tgrelid::regclass::text IN (%s)
                """.formatted(V50_TABLES.stream()
                        .map(name -> "'" + name + "'")
                        .reduce((left, right) -> left + "," + right).orElseThrow()),
                Long.class)).isEqualTo(10L);
        assertThat(admin.queryForObject(
                "SELECT prosecdef FROM pg_proc WHERE proname = 'pay_v50_claim_attempts'",
                Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1
                      FROM pg_proc function
                      CROSS JOIN LATERAL aclexplode(
                          COALESCE(function.proacl,
                              acldefault('f', function.proowner))) grant_entry
                     WHERE function.oid =
                           'pay_v50_claim_attempts(bigint,text,integer,bigint)'::regprocedure
                       AND grant_entry.grantee = 0
                       AND grant_entry.privilege_type = 'EXECUTE')
                """, Boolean.class)).isTrue();
        assertThat(admin.queryForObject(
                "SELECT has_function_privilege(?, "
                        + "'pay_v50_claim_attempts(bigint,text,integer,bigint)', 'EXECUTE')",
                Boolean.class, RUNTIME_USER)).isTrue();
    }

    @Test
    void v51HardensEveryV49AndV50OwnerTriggerFunction() {
        OwnerTriggerExecutionBoundaryGuard.verify(
                "Payroll", runtimeDataSource, List.of("public"), POSTGRES.getUsername());
        assertThat(admin.queryForObject("""
                SELECT COUNT(DISTINCT routine.oid)
                  FROM pg_trigger trigger_object
                  JOIN pg_class target ON target.oid=trigger_object.tgrelid
                  JOIN pg_namespace target_namespace
                    ON target_namespace.oid=target.relnamespace
                  JOIN pg_proc routine ON routine.oid=trigger_object.tgfoid
                 WHERE NOT trigger_object.tgisinternal
                   AND target_namespace.nspname='public'
                   AND routine.proname IN (
                       'pay_compensation_plan_input_reject_mutation',
                       'pay_v50_reject_mutation',
                       'pay_v50_guard_run_revision',
                       'pay_v50_guard_attempt_transition',
                       'pay_v50_guard_snapshot_insert',
                       'pay_v50_guard_snapshot_ref_insert',
                       'pay_v50_guard_worker_record',
                       'pay_v50_guard_receipt_record',
                       'pay_v50_guard_calculation_fact_insert',
                       'pay_v50_guard_outbox_event')
                   AND routine.prosecdef
                   AND routine.proconfig =
                       ARRAY['search_path=pg_catalog, public, pg_temp']::text[]
                   AND NOT has_function_privilege(?, routine.oid, 'EXECUTE')
                """, Long.class, RUNTIME_USER)).isEqualTo(10L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version='51' AND success
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void postgresJsonbPreservesCanonicalQuantizationIncrementScale()
            throws Exception {
        PayrollCalculationModels.QuantizationBinding binding =
                new PayrollCalculationModels.QuantizationBinding(
                        1, null,
                        PayrollCalculationSemanticMappingPort.RoundingStage.ELEMENT,
                        null, 4, java.math.RoundingMode.HALF_EVEN,
                        new BigDecimal("10.0000"), "a".repeat(64));
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        String persisted = admin.queryForObject(
                "SELECT CAST(? AS jsonb)::text", String.class,
                mapper.writeValueAsString(binding));
        PayrollCalculationModels.QuantizationBinding restored = mapper.readValue(
                persisted, PayrollCalculationModels.QuantizationBinding.class);

        assertThat(restored).isEqualTo(binding);
        assertThat(restored.increment().scale()).isEqualTo(4);
        assertThat(restored.increment().toPlainString()).isEqualTo("10.0000");
    }

    @Test
    void v49ToV50PreservesCompensationRowsAndAddsFreezeBoundary() {
        assertThat(upgrade.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_inputs", Long.class))
                .isEqualTo(1L);
        assertThat(upgrade.queryForObject(
                "SELECT snapshot_revision FROM pay_compensation_plan_inputs",
                Long.class)).isEqualTo(9L);
        assertThat(upgrade.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version IN ('49', '50') AND success
                """, Long.class)).isEqualTo(2L);
        assertThat(upgrade.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = current_schema()
                   AND table_name = 'pay_input_snapshots'
                """, Long.class)).isEqualTo(1L);
        assertThat(upgrade.queryForObject("""
                SELECT bool_and(relrowsecurity AND relforcerowsecurity)
                  FROM pg_class WHERE relname IN (%s)
                """.formatted(V50_TABLES.stream()
                        .map(name -> "'" + name + "'")
                        .reduce((left, right) -> left + "," + right).orElseThrow()),
                Boolean.class)).isTrue();
        assertThat(upgrade.queryForObject(
                "SELECT has_table_privilege(?, 'pay_payroll_runs', 'SELECT,INSERT')",
                Boolean.class, RUNTIME_USER)).isTrue();
    }

    @Test
    void jdbcFreezeBindsExactPreparedRunSnapshotAndPopulationNotTenantLatest() {
        UUID exactSnapshot = UUID.randomUUID();
        UUID unrelatedLatestSnapshot = UUID.randomUUID();
        UUID exactPublicLine = UUID.randomUUID();
        UUID unrelatedPublicLine = UUID.randomUUID();
        UUID exactFormula = UUID.fromString(
                "00000000-0000-0000-0000-000000001108");
        UUID exactLegalEntity = UUID.randomUUID();
        UUID exactPayGroup = UUID.randomUUID();
        UUID unrelatedLegalEntity = UUID.randomUUID();
        UUID unrelatedPayGroup = UUID.randomUUID();
        seedFoundationAndFormula(
                exactLegalEntity, exactPayGroup, exactFormula, "b".repeat(64));
        seedFoundationAndFormula(
                unrelatedLegalEntity, unrelatedPayGroup, UUID.randomUUID(),
                "f".repeat(64));
        seedV49Line(
                exactSnapshot, 9, PayrollCalculationTestSupport.DIGEST,
                exactPublicLine, PayrollCalculationTestSupport.PAY_PERIOD,
                "BASE", "1".repeat(64));
        seedV49Line(
                unrelatedLatestSnapshot, 99, "e".repeat(64), unrelatedPublicLine,
                PayrollCalculationTestSupport.PAY_PERIOD,
                "UNRELATED", "2".repeat(64));
        PayrollCalculationCanonical canonical = new PayrollCalculationCanonical(
                JsonMapper.builder().findAndAddModules().build());
        JdbcPayrollCalculationStore store = new JdbcPayrollCalculationStore(
                new NamedParameterJdbcTemplate(runtimeDataSource), canonical);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeDataSource));
        UUID runId = UUID.randomUUID();
        PayrollCalculationModels.RunRevision prepared = preparedRun(
                runId, exactSnapshot, 9, PayrollCalculationTestSupport.DIGEST,
                exactLegalEntity, exactPayGroup);

        transaction.executeWithoutResult(status -> store.appendRunRevision(prepared));
        PayrollCalculationStore.Preparation preparation = transaction.execute(status ->
                store.prepare(
                        prepared, 1, PayrollCalculationTestSupport.TARGET_DIGEST,
                        Instant.parse("2026-10-14T02:00:00Z")));
        assertThat(preparation).isNotNull();
        assertThat(preparation.snapshot().sourceSnapshotId()).isEqualTo(exactSnapshot);
        assertThat(preparation.snapshot().inputs())
                .extracting(PayrollCalculationModels.SourceInput::sourcePublicId)
                .containsExactly(exactPublicLine)
                .doesNotContain(unrelatedPublicLine);
        assertThat(preparation.snapshot().formulas())
                .extracting(PayrollCalculationModels.FormulaBinding::formulaId)
                .containsExactly(exactFormula);

        PayrollCalculationStore.Preparation boundPreparation =
                new PayrollCalculationStore.Preparation(
                        preparation.payPeriodId(), preparation.legalEntityId(),
                        preparation.payrollGroupId(), preparation.currency(),
                        new PayrollCalculationSemanticBinder(
                                PayrollCalculationTestSupport.exactSemanticMappings(),
                                canonical).bind(preparation.snapshot()));
        transaction.executeWithoutResult(status -> store.freeze(boundPreparation));
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_input_snapshots snapshot
                  JOIN pay_input_snapshot_compensation_refs ref
                    ON ref.tenant_id = snapshot.tenant_id
                   AND ref.input_snapshot_internal_id = snapshot.input_snapshot_internal_id
                 WHERE snapshot.tenant_id = 1 AND snapshot.run_id = ?
                   AND snapshot.source_snapshot_id = ?
                """, Long.class, runId, exactSnapshot)).isEqualTo(1L);
    }

    @Test
    void jdbcFreezeRejectsMissingOrMismatchedAppliedInputReceipt() {
        UUID legalEntityId = UUID.randomUUID();
        UUID payrollGroupId = UUID.randomUUID();
        seedFoundationAndFormula(
                legalEntityId, payrollGroupId, UUID.randomUUID(), "b".repeat(64));
        JdbcPayrollCalculationStore store = new JdbcPayrollCalculationStore(
                new NamedParameterJdbcTemplate(runtimeDataSource),
                new PayrollCalculationCanonical(
                        JsonMapper.builder().findAndAddModules().build()));
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeDataSource));

        UUID missingSnapshot = UUID.randomUUID();
        String missingDigest = "9".repeat(64);
        seedV49Line(
                missingSnapshot, 9, missingDigest, UUID.randomUUID(),
                PayrollCalculationTestSupport.PAY_PERIOD, "BASE",
                "a".repeat(64), null);
        assertThatThrownBy(() -> transaction.execute(status -> store.prepare(
                preparedRun(
                        UUID.randomUUID(), missingSnapshot, 9, missingDigest,
                        legalEntityId, payrollGroupId),
                1, PayrollCalculationTestSupport.TARGET_DIGEST, Instant.now())))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        UUID mismatchedSnapshot = UUID.randomUUID();
        String mismatchedDigest = "8".repeat(64);
        seedV49Line(
                mismatchedSnapshot, 9, mismatchedDigest, UUID.randomUUID(),
                PayrollCalculationTestSupport.PAY_PERIOD, "BASE",
                "b".repeat(64), 2);
        assertThatThrownBy(() -> transaction.execute(status -> store.prepare(
                preparedRun(
                        UUID.randomUUID(), mismatchedSnapshot, 9,
                        mismatchedDigest, legalEntityId, payrollGroupId),
                1, PayrollCalculationTestSupport.TARGET_DIGEST, Instant.now())))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void runtimeRoleCannotBypassRlsOrMutateAppendOnlyFacts() {
        assertThat(admin.queryForMap("""
                SELECT rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
                  FROM pg_roles WHERE rolname = ?
                """, RUNTIME_USER)).containsOnly(
                org.assertj.core.data.MapEntry.entry("rolsuper", false),
                org.assertj.core.data.MapEntry.entry("rolcreaterole", false),
                org.assertj.core.data.MapEntry.entry("rolcreatedb", false),
                org.assertj.core.data.MapEntry.entry("rolreplication", false),
                org.assertj.core.data.MapEntry.entry("rolbypassrls", false));
        for (String table : V50_TABLES) {
            assertThat(admin.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'SELECT,INSERT')",
                    Boolean.class, RUNTIME_USER, table)).isTrue();
            assertThat(admin.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'UPDATE,DELETE,TRUNCATE')",
                    Boolean.class, RUNTIME_USER, table)).isFalse();
        }
        UUID tenantOneRun = UUID.randomUUID();
        UUID tenantTwoRun = UUID.randomUUID();
        UUID tenantOneSnapshot = UUID.randomUUID();
        UUID tenantTwoSnapshot = UUID.randomUUID();
        insertTenantPreparingRun(admin, 1, tenantOneRun, tenantOneSnapshot);
        insertTenantPreparingRun(admin, 2, tenantTwoRun, tenantTwoSnapshot);
        insertSnapshot(admin, 1, tenantOneRun, tenantOneSnapshot);
        insertSnapshot(admin, 2, tenantTwoRun, tenantTwoSnapshot);
        assertThat(runtimeSnapshotCount(1)).isEqualTo(1L);
        assertThat(runtimeSnapshotCount(2)).isEqualTo(1L);
        assertThat(runtimeSnapshotCount(3)).isZero();
        assertThatThrownBy(() -> admin.update(
                "UPDATE pay_input_snapshots SET source_line_count = 2"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> admin.update("DELETE FROM pay_input_snapshots"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void receiptSealAndOneTimeStepUpIndexesRejectReuse() {
        UUID runId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        UUID firstSubject = UUID.randomUUID();
        insertReceipt(receiptId, runId, firstSubject, "idem-1", "challenge-1");
        assertThatThrownBy(() -> insertReceipt(
                UUID.randomUUID(), runId, UUID.randomUUID(), "idem-2", "challenge-1"))
                .isInstanceOf(DataAccessException.class);
        insertReceipt(
                UUID.randomUUID(), runId, UUID.randomUUID(),
                "idem-1", "challenge-2");
        assertThatThrownBy(() -> admin.update("""
                UPDATE pay_command_receipts SET purpose_code = 'OTHER'
                 WHERE receipt_id = ?
                """, receiptId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_command_receipts
                 WHERE receipt_id = ? AND status_sequence = 1
                """, Long.class, receiptId)).isEqualTo(1L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_command_receipts
                 WHERE idempotency_key = 'idem-1' AND status_sequence = 1
                """, Long.class)).isEqualTo(2L);
    }

    @Test
    void securityDefinerClaimCommitsLeaseBeforeWorkerHandoff() {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        UUID runId = calculateReadyRun(transactionalService(
                mock(PayrollHighRiskCommandGuard.class)), "3", "2");
        UUID attemptId = admin.queryForObject("""
                SELECT attempt_id FROM pay_payroll_run_attempts
                 WHERE tenant_id = 1 AND run_id = ? AND attempt_no = 1
                   AND status_sequence = 1
                """, UUID.class, runId);

        PayrollCalculationStore store = transactionalStore();
        var claimed = store.claimable(
                1, 1, "dispatcher-proof", Instant.now(), Duration.ofSeconds(30));

        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().lease().attemptId()).isEqualTo(attemptId);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_payroll_run_workers
                 WHERE tenant_id = 1 AND run_id = ? AND attempt_no = 1
                   AND record_kind = 'LEASE' AND lease_token = ?
                """, Long.class, runId, claimed.getFirst().lease().leaseToken()))
                .isEqualTo(1L);
        assertThat(store.claimable(
                1, 1, "dispatcher-proof-2", Instant.now(), Duration.ofSeconds(30)))
                .isEmpty();
    }

    @Test
    void realPostgresCheckpointResumeAndResultUnknownForwardRecovery() {
        UUID formulaId = UUID.fromString("00000000-0000-0000-0000-000000001108");
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                formulaId, PayrollCalculationTestSupport.FORMULA_DIGEST);
        PayrollCalculationCanonical canonical = new PayrollCalculationCanonical(
                JsonMapper.builder().findAndAddModules().build());
        JdbcPayrollCalculationStore store = new JdbcPayrollCalculationStore(
                new NamedParameterJdbcTemplate(runtimeDataSource), canonical);
        Instant databaseNow = admin.queryForObject(
                "SELECT CURRENT_TIMESTAMP", java.time.OffsetDateTime.class).toInstant();
        PayrollCalculationTestSupport.MutableClock clock =
                new PayrollCalculationTestSupport.MutableClock(databaseNow);
        PayrollCalculationWorker worker = new PayrollCalculationWorker(
                store, new PayrollCalculationEngine(
                        canonical, PayrollCalculationTestSupport.exactSemanticMappings()),
                canonical, new DataSourceTransactionManager(runtimeDataSource),
                clock, Duration.ofSeconds(1), 1, 100);
        PayrollCalculationService service = transactionalService(
                mock(PayrollHighRiskCommandGuard.class));

        UUID resumedRun = UUID.randomUUID();
        UUID resumedSnapshot = UUID.randomUUID();
        String resumedDigest = "5".repeat(64);
        seedV49Line(
                resumedSnapshot, 9, resumedDigest, UUID.randomUUID(),
                PayrollCalculationTestSupport.PAY_PERIOD, "BASE", "6".repeat(64));
        insertRun(admin, resumedRun, "PREPARING", 0, null,
                resumedSnapshot, 9, resumedDigest);
        service.calculate(
                calculator(), resumedRun, "resume-command", 1, UUID.randomUUID(),
                new PayrollCalculationModels.ActionCommand("CALCULATE", null, null));

        assertThatThrownBy(() -> worker.execute(
                1, resumedRun, 1, "worker-a",
                PayrollCalculationWorker.FailurePoint.AFTER_FIRST_CHECKPOINT_COMMIT))
                .isInstanceOf(PayrollCalculationWorker.SimulatedWorkerCrash.class);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_payroll_run_workers
                 WHERE run_id = ? AND record_kind = 'CHECKPOINT'
                """, Long.class, resumedRun)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_outbox_events WHERE run_id = ?",
                Long.class, resumedRun)).isZero();
        clock.advance(Duration.ofSeconds(2));
        assertThat(worker.execute(1, resumedRun, 1, "worker-b")).isTrue();
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_outbox_events WHERE run_id = ? "
                        + "AND event_type = 'PayrollCalculated'",
                Long.class, resumedRun)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_result_lines WHERE run_id = ?",
                Long.class, resumedRun)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_calculation_trace_nodes WHERE run_id = ?",
                Long.class, resumedRun)).isEqualTo(2L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_worker_results WHERE run_id = ?",
                Long.class, resumedRun)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_command_receipts WHERE run_id = ?",
                Long.class, resumedRun)).isEqualTo(5L);

        UUID unknownRun = UUID.randomUUID();
        UUID unknownSnapshot = UUID.randomUUID();
        String unknownDigest = "7".repeat(64);
        seedV49Line(
                unknownSnapshot, 9, unknownDigest, UUID.randomUUID(),
                PayrollCalculationTestSupport.PAY_PERIOD, "BASE", "8".repeat(64));
        insertRun(admin, unknownRun, "PREPARING", 0, null,
                unknownSnapshot, 9, unknownDigest);
        service.calculate(
                calculator(), unknownRun, "unknown-command", 1, UUID.randomUUID(),
                new PayrollCalculationModels.ActionCommand("CALCULATE", null, null));
        assertThat(worker.execute(
                1, unknownRun, 1, "worker-unknown",
                PayrollCalculationWorker.FailurePoint.BEFORE_COMPLETION_UNCERTAIN))
                .isFalse();

        PayrollCalculationStore pollStore = transactionalStore();
        assertThat(pollStore.recoveryCandidates(1, 10))
                .extracting(PayrollCalculationStore.RecoveryCandidate::runId)
                .contains(unknownRun);
        assertThat(worker.reconcile(1, unknownRun)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT COUNT(DISTINCT attempt_no) FROM pay_payroll_run_attempts
                 WHERE run_id = ?
                """, Long.class, unknownRun)).isEqualTo(2L);
        assertThat(worker.execute(1, unknownRun, 2, "worker-forward")).isTrue();
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_outbox_events WHERE run_id = ? "
                        + "AND event_type = 'PayrollCalculated'",
                Long.class, unknownRun)).isEqualTo(1L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_payroll_run_attempts
                 WHERE run_id = ? AND attempt_no = 1
                   AND attempt_state = 'SUPERSEDED'
                """, Long.class, unknownRun)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_result_lines WHERE run_id = ?",
                Long.class, unknownRun)).isEqualTo(2L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_calculation_trace_nodes WHERE run_id = ?",
                Long.class, unknownRun)).isEqualTo(4L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_worker_results WHERE run_id = ?",
                Long.class, unknownRun)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_command_receipts WHERE run_id = ?",
                Long.class, unknownRun)).isEqualTo(10L);
        assertThat(admin.queryForList("""
                SELECT result_line_digest FROM pay_result_lines
                 WHERE run_id = ? ORDER BY attempt_no
                """, String.class, unknownRun)).hasSize(2)
                .allMatch(admin.queryForList("""
                        SELECT result_line_digest FROM pay_result_lines
                         WHERE run_id = ? ORDER BY attempt_no
                        """, String.class, unknownRun).getFirst()::equals);
    }

    @Test
    void replacementLeaseFencesOldCheckpointAndTerminalWritesInPostgres() {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        UUID runId = calculateReadyRun(transactionalService(
                mock(PayrollHighRiskCommandGuard.class)), "4", "2");
        JdbcPayrollCalculationStore store = new JdbcPayrollCalculationStore(
                new NamedParameterJdbcTemplate(runtimeDataSource),
                new PayrollCalculationCanonical(
                        JsonMapper.builder().findAndAddModules().build()));
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeDataSource));
        Instant databaseNow = admin.queryForObject(
                "SELECT CURRENT_TIMESTAMP", java.time.OffsetDateTime.class).toInstant();
        PayrollCalculationModels.Lease oldLease = tx.execute(status -> store.claim(
                1, runId, 1, "old-worker", databaseNow,
                Duration.ofSeconds(1)).orElseThrow());
        PayrollCalculationModels.Lease replacement = tx.execute(status -> store.claim(
                1, runId, 1, "replacement-worker", databaseNow.plusSeconds(2),
                Duration.ofSeconds(30)).orElseThrow());
        assertThat(replacement.claimNo()).isEqualTo(oldLease.claimNo() + 1);

        PayrollCalculationModels.FrozenSnapshot snapshot = tx.execute(status ->
                store.snapshot(1, runId, 1));
        PayrollCalculationCanonical canonical = new PayrollCalculationCanonical(
                JsonMapper.builder().findAndAddModules().build());
        PayrollCalculationModels.CalculationOutcome outcome =
                new PayrollCalculationEngine(
                        canonical, PayrollCalculationTestSupport.exactSemanticMappings())
                        .calculate(snapshot);
        PayrollCalculationModels.Checkpoint checkpoint =
                new PayrollCalculationModels.Checkpoint(
                        1, 2, 1, canonical.checkpointDigest(
                        runId, 1, 1, 2,
                        List.of(outcome.lines().getFirst().resultLineDigest())));
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                store.appendProgress(
                        oldLease, outcome, 1, 1, checkpoint, databaseNow.plusSeconds(2))))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        PayrollCalculationModels.RunRevision current = tx.execute(status ->
                store.currentRun(1, runId).orElseThrow());
        PayrollCalculationModels.RunRevision failed = new PayrollCalculationModels.RunRevision(
                current.tenantId(), current.runId(), current.revision() + 1,
                current.runType(), current.payPeriodId(), current.sourceSnapshotId(),
                current.sourceSnapshotRevision(), current.sourcePayloadDigest(),
                current.legalEntityId(), current.payrollGroupId(),
                current.populationKind(), current.populationScopeDigest(),
                PayrollCalculationModels.RunState.FAILED, current.makerActorId(),
                current.makerSubjectPublicId(), current.latestAttemptNo(),
                current.workerCount(), current.gross(), current.net(),
                current.currency(), current.resultDigest(), current.approvalCaseId(),
                current.decisionDigest(), current.approvedByActorId(),
                current.approvedAt(), current.auditBundleDigest(),
                current.commandReceiptId(), databaseNow.plusSeconds(2));
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                store.terminalFailure(
                        oldLease, current.commandReceiptId(),
                        PayrollCalculationModels.AttemptState.FAILED,
                        "STALE_WORKER", failed, "{}", databaseNow.plusSeconds(2))))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_result_lines WHERE run_id = ?",
                Long.class, runId)).isZero();
        assertThat(admin.queryForObject("""
                SELECT state FROM pay_payroll_runs
                 WHERE run_id = ? ORDER BY run_revision DESC LIMIT 1
                """, String.class, runId)).isEqualTo("READY");
    }

    @Test
    void postCommitAcknowledgementLossProbesCommittedOutcomeWithoutDuplicates() {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        UUID runId = calculateReadyRun(transactionalService(
                mock(PayrollHighRiskCommandGuard.class)), "2", "1");
        PayrollCalculationCanonical canonical = new PayrollCalculationCanonical(
                JsonMapper.builder().findAndAddModules().build());
        CommitThenLoseAcknowledgement manager = new CommitThenLoseAcknowledgement(
                new DataSourceTransactionManager(runtimeDataSource), admin, runId);
        PayrollCalculationWorker worker = new PayrollCalculationWorker(
                new JdbcPayrollCalculationStore(
                        new NamedParameterJdbcTemplate(runtimeDataSource), canonical),
                new PayrollCalculationEngine(
                        canonical, PayrollCalculationTestSupport.exactSemanticMappings()),
                canonical, manager, java.time.Clock.systemUTC(),
                Duration.ofSeconds(30), 1, 100);

        manager.arm();
        assertThat(worker.execute(1, runId, 1, "commit-unknown-worker")).isTrue();
        assertThat(manager.didLoseAcknowledgement()).isTrue();
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_result_lines WHERE run_id = ?",
                Long.class, runId)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_calculation_trace_nodes WHERE run_id = ?",
                Long.class, runId)).isEqualTo(2L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_worker_results WHERE run_id = ?",
                Long.class, runId)).isEqualTo(1L);
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_outbox_events WHERE run_id = ?",
                Long.class, runId)).isEqualTo(1L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_command_receipts
                 WHERE run_id = ? AND receipt_status = 'SUCCEEDED'
                """, Long.class, runId)).isEqualTo(2L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_payroll_runs
                 WHERE run_id = ? AND state = 'VALIDATED'
                """, Long.class, runId)).isEqualTo(1L);
    }

    @Test
    void transactionalApprovalRollsBackReceiptRunAuditAndOutboxTogether() {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        PayrollCalculationService service = transactionalService(
                fixedStepUpGuard("rollback"));
        PayrollCalculationWorker worker = jdbcWorker();
        UUID runId = calculateValidatedRun(service, worker, "9", "6");
        long expectedVersion = currentRunVersion(runId);
        long runRowsBefore = countRunRows(runId);
        admin.execute("""
                CREATE FUNCTION pay011_test_reject_outbox()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated outbox failure'; END
                $$
                """);
        admin.execute("""
                CREATE TRIGGER pay011_test_reject_outbox
                BEFORE INSERT ON pay_outbox_events
                FOR EACH ROW EXECUTE FUNCTION pay011_test_reject_outbox()
                """);
        try {
            assertThatThrownBy(() -> service.approve(
                    approver(202), runId, runId.toString(),
                    "rollback-command", expectedVersion,
                    UUID.randomUUID(),
                    new PayrollCalculationModels.ActionCommand(
                            "APPROVE_REGULAR", null, null),
                    new PayrollStepUpHeaders(
                            "signed", "rollback-command", "decision-41",
                            expectedVersion)))
                    .isInstanceOf(RuntimeException.class);

            assertThat(admin.queryForObject(
                    "SELECT COUNT(*) FROM pay_payroll_runs WHERE run_id = ?",
                    Long.class, runId)).isEqualTo(runRowsBefore);
            assertThat(admin.queryForObject("""
                    SELECT COUNT(*) FROM pay_command_receipts
                     WHERE run_id = ? AND command_type = 'RUN_APPROVE'
                    """, Long.class, runId)).isZero();
            assertThat(admin.queryForObject(
                    "SELECT COUNT(*) FROM pay_outbox_events WHERE run_id = ? "
                            + "AND event_type = 'PayrollRunApproved'",
                    Long.class, runId)).isZero();
        } finally {
            admin.execute("DROP TRIGGER IF EXISTS pay011_test_reject_outbox "
                    + "ON pay_outbox_events");
            admin.execute("DROP FUNCTION IF EXISTS pay011_test_reject_outbox()");
        }
    }

    @Test
    void concurrentCrossRunIdempotencyHasOneWinnerAndNoLoserEffects() throws Exception {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        AtomicInteger challenge = new AtomicInteger();
        CountDownLatch guardEntered = new CountDownLatch(2);
        CountDownLatch guardRelease = new CountDownLatch(1);
        PayrollHighRiskCommandGuard guard = mock(PayrollHighRiskCommandGuard.class);
        when(guard.require(any(), any(), anyLong(), any(), any(), any()))
                .thenAnswer(ignored -> {
                    guardEntered.countDown();
                    if (!guardRelease.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("guard race did not release");
                    }
                    return stepUp("race-" + challenge.incrementAndGet());
                });
        PayrollCalculationService service = transactionalService(guard);
        PayrollCalculationWorker worker = jdbcWorker();
        UUID firstRun = calculateValidatedRun(service, worker, "8", "5");
        UUID secondRun = calculateValidatedRun(service, worker, "7", "4");
        long firstVersion = currentRunVersion(firstRun);
        long secondVersion = currentRunVersion(secondRun);
        long totalRunRowsBefore = countRunRows(firstRun) + countRunRows(secondRun);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> approvalRace(
                    service, firstRun, firstVersion));
            var second = executor.submit(() -> approvalRace(
                    service, secondRun, secondVersion));
            assertThat(guardEntered.await(10, TimeUnit.SECONDS)).isTrue();
            guardRelease.countDown();
            List<Object> outcomes = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(outcomes.stream()
                    .filter(PayrollCalculationModels.PayrollCommandReceipt.class::isInstance))
                    .hasSize(1);
            assertThat(outcomes.stream().filter(BaseException.class::isInstance)
                    .map(BaseException.class::cast)
                    .map(BaseException::getErrorCode))
                    .containsExactly(ErrorCode.RESOURCE_CONFLICT);
            assertThat(admin.queryForObject(
                    "SELECT COUNT(DISTINCT receipt_id) FROM pay_command_receipts "
                            + "WHERE command_type = 'RUN_APPROVE'",
                    Long.class)).isEqualTo(1L);
            assertThat(admin.queryForObject(
                    "SELECT COUNT(*) FROM pay_payroll_runs WHERE run_id IN (?, ?)",
                    Long.class, firstRun, secondRun))
                    .isEqualTo(totalRunRowsBefore + 1);
            assertThat(admin.queryForObject(
                    "SELECT COUNT(DISTINCT run_id) FROM pay_payroll_run_attempts "
                            + "WHERE run_id IN (?, ?)",
                    Long.class, firstRun, secondRun)).isEqualTo(2L);
            assertThat(admin.queryForObject(
                    "SELECT COUNT(*) FROM pay_outbox_events "
                            + "WHERE event_type = 'PayrollRunApproved'",
                    Long.class)).isEqualTo(1L);
            assertThat(admin.queryForObject("""
                    SELECT COUNT(*) FROM pay_command_receipts
                     WHERE audit_type = 'PAYROLL_RUN_APPROVED'
                    """, Long.class)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void postgresGuardsSealFactsRejectMakerFinalizeAndPersistExactEvents()
            throws Exception {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        PayrollCalculationWorker worker = jdbcWorker();
        PayrollCalculationService calculate = transactionalService(
                mock(PayrollHighRiskCommandGuard.class));
        UUID runId = calculateValidatedRun(calculate, worker, "6", "3");

        PayrollCalculationService approve = transactionalService(
                fixedStepUpGuard("event-approve"));
        long validatedVersion = currentRunVersion(runId);
        approve.approve(
                approver(202), runId, runId.toString(), "event-approve",
                validatedVersion, UUID.randomUUID(),
                new PayrollCalculationModels.ActionCommand(
                        "APPROVE_REGULAR", null, null),
                new PayrollStepUpHeaders(
                        "signed", "event-approve", "decision-41",
                        validatedVersion));

        long approvedVersion = currentRunVersion(runId);
        UUID makerFinalizeReceipt = UUID.randomUUID();
        insertHighRiskReceipt(
                makerFinalizeReceipt, runId, "maker-finalize",
                "RUN_FINALIZE", "PAY_RUN_FINALIZE", 101,
                PayrollCalculationTestSupport.SUBJECT, approvedVersion + 1,
                "maker-finalize-proof");
        assertThatThrownBy(() -> insertFinalizedRevision(
                runId, makerFinalizeReceipt, approvedVersion + 1))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("maker cannot approve or finalize");
        assertThat(currentRunVersion(runId)).isEqualTo(approvedVersion);

        PayrollCalculationService finalize = transactionalService(
                fixedStepUpGuard("event-finalize"));
        finalize.finalizeRun(
                finalizer(202), runId, runId.toString(), "event-finalize",
                approvedVersion, UUID.randomUUID(),
                new PayrollCalculationModels.ActionCommand(
                        "FINALIZE_REGULAR", null, null),
                new PayrollStepUpHeaders(
                        "signed", "event-finalize", "decision-41",
                        approvedVersion));

        List<Map<String, Object>> events = admin.queryForList("""
                SELECT event_type, event_payload::text AS payload
                  FROM pay_outbox_events
                 WHERE tenant_id = 1 AND run_id = ?
                 ORDER BY run_revision
                """, runId);
        assertThat(events).extracting(row -> row.get("event_type"))
                .containsExactly(
                        "PayrollCalculated", "PayrollRunApproved", "PayrollFinalized");
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        Set<String> envelopeKeys = Set.of(
                "specVersion", "id", "source", "type", "schemaVersion", "time",
                "tenantId", "aggregateType", "aggregateId", "aggregateSequence",
                "correlationId", "data");
        Map<String, Set<String>> dataKeys = Map.of(
                "PayrollCalculated", Set.of(
                        "runId", "attemptNo", "workerCount", "resultDigest",
                        "controlTotals"),
                "PayrollRunApproved", Set.of(
                        "runId", "runRevision", "approvalCaseId", "decisionDigest"),
                "PayrollFinalized", Set.of(
                        "runId", "runRevision", "resultDigest", "auditBundleDigest"));
        for (Map<String, Object> row : events) {
            JsonNode payload = mapper.readTree((String) row.get("payload"));
            assertThat(fieldNames(payload)).isEqualTo(envelopeKeys);
            assertThat(fieldNames(payload.path("data")))
                    .isEqualTo(dataKeys.get(row.get("event_type")));
        }

        long workerFacts = admin.queryForObject(
                "SELECT COUNT(*) FROM pay_worker_results WHERE run_id = ?",
                Long.class, runId);
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO pay_worker_results
                SELECT * FROM pay_worker_results
                 WHERE tenant_id = 1 AND run_id = ? LIMIT 1
                """, runId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("sealed or unfenced");
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_worker_results WHERE run_id = ?",
                Long.class, runId)).isEqualTo(workerFacts);

        long traceFacts = admin.queryForObject(
                "SELECT COUNT(*) FROM pay_calculation_trace_nodes WHERE run_id = ?",
                Long.class, runId);
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO pay_calculation_trace_nodes
                SELECT * FROM pay_calculation_trace_nodes
                 WHERE tenant_id = 1 AND run_id = ? LIMIT 1
                """, runId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("sealed or unfenced");
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_calculation_trace_nodes WHERE run_id = ?",
                Long.class, runId)).isEqualTo(traceFacts);

        long resultLines = admin.queryForObject(
                "SELECT COUNT(*) FROM pay_result_lines WHERE run_id = ?",
                Long.class, runId);
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO pay_result_lines
                SELECT * FROM pay_result_lines
                 WHERE tenant_id = 1 AND run_id = ? LIMIT 1
                """, runId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("sealed or unfenced");
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_result_lines WHERE run_id = ?",
                Long.class, runId)).isEqualTo(resultLines);

        assertThatThrownBy(() -> admin.update("""
                INSERT INTO pay_payroll_run_workers
                SELECT * FROM pay_payroll_run_workers
                 WHERE tenant_id = 1 AND run_id = ?
                   AND record_kind = 'CHECKPOINT' LIMIT 1
                """, runId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("stale or out of sequence");
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO pay_input_snapshots
                SELECT * FROM pay_input_snapshots
                 WHERE tenant_id = 1 AND run_id = ? LIMIT 1
                """, runId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("outside the open freeze boundary");
        assertThatThrownBy(() -> admin.update("""
                INSERT INTO pay_input_snapshot_compensation_refs
                SELECT ref.* FROM pay_input_snapshot_compensation_refs ref
                  JOIN pay_input_snapshots snapshot
                    ON snapshot.tenant_id = ref.tenant_id
                   AND snapshot.input_snapshot_internal_id =
                       ref.input_snapshot_internal_id
                 WHERE snapshot.tenant_id = 1 AND snapshot.run_id = ? LIMIT 1
                """, runId)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("references are sealed");

        assertThatThrownBy(() -> insertIllegalAttemptTransition(runId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Illegal payroll attempt transition");
        assertThatThrownBy(() -> insertIllegalRunTransition(runId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Illegal payroll run state transition");

        assertThatThrownBy(() -> insertInvalidInitialCalculateReceipt(runId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("sequence one");
        assertThatThrownBy(() -> insertOutboxWithExtraData(runId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("payload is not exact");
        assertThat(admin.queryForObject(
                "SELECT COUNT(*) FROM pay_outbox_events WHERE run_id = ?",
                Long.class, runId)).isEqualTo(3L);
    }

    @Test
    void failedRunCanFreezeAnExactSecondAttempt() {
        seedFoundationAndFormula(
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                UUID.fromString("00000000-0000-0000-0000-000000001108"),
                PayrollCalculationTestSupport.FORMULA_DIGEST);
        PayrollCalculationService service = transactionalService(
                mock(PayrollHighRiskCommandGuard.class));
        UUID runId = calculateReadyRun(service, "5", "4");
        PayrollCalculationCanonical canonical = new PayrollCalculationCanonical(
                JsonMapper.builder().findAndAddModules().build());
        PayrollCalculationWorker failingWorker = new PayrollCalculationWorker(
                new JdbcPayrollCalculationStore(
                        new NamedParameterJdbcTemplate(runtimeDataSource), canonical),
                new PayrollCalculationEngine(
                        canonical, (snapshot, input) -> {
                            throw new IllegalStateException("typed evaluation unavailable");
                        }),
                canonical, new DataSourceTransactionManager(runtimeDataSource),
                java.time.Clock.systemUTC(), Duration.ofSeconds(30), 1, 100);
        assertThatThrownBy(() -> failingWorker.execute(
                1, runId, 1, "failed-attempt-worker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("typed evaluation unavailable");
        long failedVersion = currentRunVersion(runId);
        assertThat(admin.queryForObject("""
                SELECT state FROM pay_payroll_runs
                 WHERE run_id = ? ORDER BY run_revision DESC LIMIT 1
                """, String.class, runId)).isEqualTo("FAILED");

        service.calculate(
                calculator(), runId, "retry-after-failed", failedVersion,
                UUID.randomUUID(), new PayrollCalculationModels.ActionCommand(
                "RETRY_CALCULATION", null, null));
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_input_snapshots
                 WHERE tenant_id = 1 AND run_id = ?
                """, Long.class, runId)).isEqualTo(2L);
        assertThat(admin.queryForObject("""
                SELECT state FROM pay_payroll_runs
                 WHERE run_id = ? ORDER BY run_revision DESC LIMIT 1
                """, String.class, runId)).isEqualTo("READY");
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pay_payroll_run_attempts
                 WHERE run_id = ? AND attempt_no = 2 AND attempt_state = 'READY'
                """, Long.class, runId)).isEqualTo(1L);
    }

    private Object approvalRace(
            PayrollCalculationService service,
            UUID runId,
            long expectedVersion) {
        try {
            return service.approve(
                    approver(202), runId, runId.toString(),
                    "same-command-key", expectedVersion,
                    UUID.randomUUID(),
                    new PayrollCalculationModels.ActionCommand(
                            "APPROVE_REGULAR", null, null),
                    new PayrollStepUpHeaders(
                            "signed", "same-command-key", "decision-41",
                            expectedVersion));
        } catch (Throwable exception) {
            return exception;
        }
    }

    private PayrollCalculationStore transactionalStore() {
        JdbcPayrollCalculationStore target = new JdbcPayrollCalculationStore(
                new NamedParameterJdbcTemplate(runtimeDataSource),
                new PayrollCalculationCanonical(
                        JsonMapper.builder().findAndAddModules().build()));
        return transactionalProxy(
                target, new DataSourceTransactionManager(runtimeDataSource));
    }

    private PayrollCalculationService transactionalService(
            PayrollHighRiskCommandGuard guard) {
        PayrollCalculationCanonical canonical = new PayrollCalculationCanonical(
                JsonMapper.builder().findAndAddModules().build());
        PayrollCalculationService target = new PayrollCalculationService(
                new JdbcPayrollCalculationStore(
                        new NamedParameterJdbcTemplate(runtimeDataSource), canonical),
                canonical, guard,
                new PayrollCalculationSemanticBinder(
                        PayrollCalculationTestSupport.exactSemanticMappings(),
                        canonical),
                java.time.Clock.fixed(
                        Instant.parse("2026-10-14T02:00:00Z"),
                        java.time.ZoneOffset.UTC));
        return transactionalProxy(
                target, new DataSourceTransactionManager(runtimeDataSource));
    }

    private PayrollCalculationWorker jdbcWorker() {
        PayrollCalculationCanonical canonical = new PayrollCalculationCanonical(
                JsonMapper.builder().findAndAddModules().build());
        return new PayrollCalculationWorker(
                new JdbcPayrollCalculationStore(
                        new NamedParameterJdbcTemplate(runtimeDataSource), canonical),
                new PayrollCalculationEngine(
                        canonical, PayrollCalculationTestSupport.exactSemanticMappings()),
                canonical, new DataSourceTransactionManager(runtimeDataSource),
                java.time.Clock.systemUTC(), Duration.ofSeconds(30), 1, 100);
    }

    private UUID calculateValidatedRun(
            PayrollCalculationService service,
            PayrollCalculationWorker worker,
            String snapshotDigit,
            String payloadDigit) {
        UUID runId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        String snapshotDigest = snapshotDigit.repeat(64);
        seedV49Line(
                snapshotId, 9, snapshotDigest, UUID.randomUUID(),
                PayrollCalculationTestSupport.PAY_PERIOD, "BASE",
                payloadDigit.repeat(64));
        insertRun(admin, runId, "PREPARING", 0, null,
                snapshotId, 9, snapshotDigest);
        service.calculate(
                calculator(), runId, "calculate-" + runId, 1, UUID.randomUUID(),
                new PayrollCalculationModels.ActionCommand("CALCULATE", null, null));
        assertThat(worker.execute(1, runId, 1, "worker-" + runId)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT state FROM pay_payroll_runs
                 WHERE tenant_id = 1 AND run_id = ?
                 ORDER BY run_revision DESC LIMIT 1
                """, String.class, runId)).isEqualTo("VALIDATED");
        return runId;
    }

    private UUID calculateReadyRun(
            PayrollCalculationService service,
            String snapshotDigit,
            String payloadDigit) {
        UUID runId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        String snapshotDigest = snapshotDigit.repeat(64);
        seedV49Line(
                snapshotId, 9, snapshotDigest, UUID.randomUUID(),
                PayrollCalculationTestSupport.PAY_PERIOD, "BASE",
                payloadDigit.repeat(64));
        insertRun(admin, runId, "PREPARING", 0, null,
                snapshotId, 9, snapshotDigest);
        service.calculate(
                calculator(), runId, "calculate-" + runId, 1, UUID.randomUUID(),
                new PayrollCalculationModels.ActionCommand("CALCULATE", null, null));
        return runId;
    }

    private long currentRunVersion(UUID runId) {
        Long value = admin.queryForObject(
                "SELECT MAX(run_revision) FROM pay_payroll_runs "
                        + "WHERE tenant_id = 1 AND run_id = ?",
                Long.class, runId);
        return value == null ? -1 : value;
    }

    private long countRunRows(UUID runId) {
        Long value = admin.queryForObject(
                "SELECT COUNT(*) FROM pay_payroll_runs "
                        + "WHERE tenant_id = 1 AND run_id = ?",
                Long.class, runId);
        return value == null ? -1 : value;
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(T target, PlatformTransactionManager manager) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(
                new AnnotationTransactionAttributeSource(false));
        proxy.addAdvice(interceptor);
        return (T) proxy.getProxy();
    }

    private PayrollHighRiskCommandGuard fixedStepUpGuard(String suffix) {
        PayrollHighRiskCommandGuard guard = mock(PayrollHighRiskCommandGuard.class);
        when(guard.require(any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(stepUp(suffix));
        return guard;
    }

    private PayrollStepUpEvidence stepUp(String suffix) {
        return new PayrollStepUpEvidence(
                "challenge-" + suffix, "nonce-" + suffix, "issuer",
                Instant.parse("2026-10-14T02:05:00Z"),
                "e".repeat(64), "decision-41");
    }

    private PayrollCalculationAuthority.Actor approver(long actorId) {
        PayrollCalculationAuthority.Subject subject =
                new PayrollCalculationAuthority.Subject(
                        1, actorId,
                        UUID.fromString("00000000-0000-0000-0000-000000001105"),
                        PayrollCalculationAuthority.Action.APPROVE,
                        "route.hcm.operations.payroll-run-approve.action",
                        "hcm.operations.payroll.approve", "PAYROLL_PROCESSING",
                        "ctx-payroll", "les:root", "LEGAL_ENTITY", 7, 41,
                        "decision-41", Instant.parse("2026-10-14T03:00:00Z"),
                        "ELEVATED", Set.of(
                                "APP.HCM:VIEW",
                                "ACTION.HRIS_PAYROLL_APPROVE:APPROVE"));
        return new PayrollCalculationAuthority.Actor(
                subject, new PayrollCalculationAuthority.Scope(
                Set.of(new PayrollCalculationAuthority.PopulationTuple(
                        PayrollCalculationTestSupport.LEGAL_ENTITY,
                        PayrollCalculationTestSupport.PAY_GROUP,
                        "LEGAL_ENTITY", "les:root")),
                "projection-41", PayrollCalculationTestSupport.TARGET_DIGEST));
    }

    private PayrollCalculationAuthority.Actor finalizer(long actorId) {
        PayrollCalculationAuthority.Subject subject =
                new PayrollCalculationAuthority.Subject(
                        1, actorId,
                        UUID.fromString("00000000-0000-0000-0000-000000001105"),
                        PayrollCalculationAuthority.Action.FINALIZE,
                        "route.hcm.operations.payroll-run-finalize.action",
                        "hcm.operations.payroll.finalize", "PAYROLL_PROCESSING",
                        "ctx-payroll", "les:root", "LEGAL_ENTITY", 7, 41,
                        "decision-41", Instant.parse("2026-10-14T03:00:00Z"),
                        "ELEVATED", Set.of(
                        "APP.HCM:VIEW",
                        "ACTION.HRIS_PAYROLL_FINALIZE:FINALIZE"));
        return new PayrollCalculationAuthority.Actor(
                subject, new PayrollCalculationAuthority.Scope(
                Set.of(new PayrollCalculationAuthority.PopulationTuple(
                        PayrollCalculationTestSupport.LEGAL_ENTITY,
                        PayrollCalculationTestSupport.PAY_GROUP,
                        "LEGAL_ENTITY", "les:root")),
                "projection-41", PayrollCalculationTestSupport.TARGET_DIGEST));
    }

    private PayrollCalculationAuthority.Actor calculator() {
        PayrollCalculationAuthority.Subject subject =
                new PayrollCalculationAuthority.Subject(
                        1, 101, PayrollCalculationTestSupport.SUBJECT,
                        PayrollCalculationAuthority.Action.CALCULATE,
                        "route.hcm.operations.payroll-run-calculate.action",
                        "hcm.operations.payroll-run.execute", "PAYROLL_PROCESSING",
                        "ctx-payroll", "les:root", "LEGAL_ENTITY", 7, 41,
                        "decision-41", Instant.parse("2026-10-14T03:00:00Z"),
                        "NORMAL", Set.of(
                                "APP.HCM:VIEW", "ACTION.HRIS_PAYROLL_RUN:EXECUTE"));
        return new PayrollCalculationAuthority.Actor(
                subject, new PayrollCalculationAuthority.Scope(
                Set.of(new PayrollCalculationAuthority.PopulationTuple(
                        PayrollCalculationTestSupport.LEGAL_ENTITY,
                        PayrollCalculationTestSupport.PAY_GROUP,
                        "LEGAL_ENTITY", "les:root")),
                "projection-41", PayrollCalculationTestSupport.TARGET_DIGEST));
    }

    private void insertRun(
            JdbcTemplate jdbc,
            UUID runId,
            String state,
            int latestAttemptNo,
            String resultDigest) {
        insertRun(jdbc, runId, state, latestAttemptNo, resultDigest,
                UUID.fromString("00000000-0000-0000-0000-000000001106"),
                9, PayrollCalculationTestSupport.DIGEST);
    }

    private void insertRun(
            JdbcTemplate jdbc,
            UUID runId,
            String state,
            int latestAttemptNo,
            String resultDigest,
            UUID sourceSnapshotId,
            long sourceSnapshotRevision,
            String sourceSnapshotDigest) {
        if (!"PREPARING".equals(state) || latestAttemptNo != 0
                || resultDigest != null) {
            throw new IllegalArgumentException(
                    "Raw test run creation must start at PREPARING revision one");
        }
        jdbc.update("""
                INSERT INTO pay_payroll_runs (
                    tenant_id, run_id, run_revision, run_type, pay_period_id,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest, legal_entity_id, payroll_group_id,
                    population_kind, population_scope_digest, state,
                    maker_actor_id, maker_subject_public_id, latest_attempt_no,
                    worker_count, gross_amount, net_amount, currency,
                    result_digest, approval_case_id, decision_digest,
                    approved_by_actor_id, approved_at, audit_bundle_digest,
                    command_receipt_id, recorded_at)
                VALUES (1, ?, 1, 'REGULAR', ?, ?, ?, ?, ?, ?, 'LEGAL_ENTITY', ?, ?,
                    101, ?, ?, ?, ?, ?, 'TST', ?, NULL, NULL, NULL, NULL, NULL,
                    NULL, CURRENT_TIMESTAMP)
                """, runId, PayrollCalculationTestSupport.PAY_PERIOD,
                sourceSnapshotId, sourceSnapshotRevision, sourceSnapshotDigest,
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                PayrollCalculationTestSupport.TARGET_DIGEST, state,
                PayrollCalculationTestSupport.SUBJECT, 0, 0,
                BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4), null);
    }

    private void insertReadyRevision(
            JdbcTemplate jdbc, UUID runId, UUID receiptId) {
        jdbc.update("""
                INSERT INTO pay_payroll_runs (
                    tenant_id, run_id, run_revision, run_type, pay_period_id,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest, legal_entity_id, payroll_group_id,
                    population_kind, population_scope_digest, state,
                    maker_actor_id, maker_subject_public_id, latest_attempt_no,
                    worker_count, gross_amount, net_amount, currency,
                    result_digest, approval_case_id, decision_digest,
                    approved_by_actor_id, approved_at, audit_bundle_digest,
                    command_receipt_id, recorded_at)
                SELECT tenant_id, run_id, 2, run_type, pay_period_id,
                       source_snapshot_id, source_snapshot_revision,
                       source_payload_digest, legal_entity_id, payroll_group_id,
                       population_kind, population_scope_digest, 'READY',
                       maker_actor_id, maker_subject_public_id, 1,
                       0, 0.0000, 0.0000, currency,
                       NULL, NULL, NULL, NULL, NULL, NULL, ?, CURRENT_TIMESTAMP
                  FROM pay_payroll_runs
                 WHERE tenant_id = 1 AND run_id = ? AND run_revision = 1
                """, receiptId, runId);
    }

    private PayrollCalculationModels.RunRevision preparedRun(
            UUID runId,
            UUID snapshotId,
            long snapshotRevision,
            String snapshotDigest,
            UUID legalEntityId,
            UUID payrollGroupId) {
        return new PayrollCalculationModels.RunRevision(
                1, runId, 1, PayrollCalculationModels.RunType.REGULAR,
                PayrollCalculationTestSupport.PAY_PERIOD,
                snapshotId, snapshotRevision, snapshotDigest,
                legalEntityId, payrollGroupId, "LEGAL_ENTITY",
                PayrollCalculationTestSupport.TARGET_DIGEST,
                PayrollCalculationModels.RunState.PREPARING,
                101, PayrollCalculationTestSupport.SUBJECT,
                0, 0, BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(4),
                "TST", null, null, null, null, null, null, null,
                Instant.parse("2026-10-14T02:00:00Z"));
    }

    private void seedFoundationAndFormula(
            UUID legalEntityId,
            UUID payrollGroupId,
            UUID formulaId,
            String astDigest) {
        admin.update("""
                INSERT INTO pay_foundation_configurations (
                    tenant_id, configuration_id, current_version, lifecycle_state,
                    legal_entity_id, payroll_group_id, effective_from, effective_to,
                    author_id, publisher_id, simulation_report, last_command_id,
                    created_at, updated_at)
                VALUES (1, ?, 1, 'PUBLISHED', ?, ?, DATE '2026-01-01', NULL,
                    101, 202, '{}'::jsonb, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), legalEntityId, payrollGroupId,
                UUID.randomUUID());
        admin.update("""
                INSERT INTO pay_formula_versions (
                    tenant_id, formula_id, version, lifecycle_state,
                    legal_entity_id, payroll_group_id, formula_code, definition,
                    ast_digest, validation_digest, authored_by, published_by,
                    command_id, recorded_at)
                VALUES (1, ?, 1, 'PUBLISHED', ?, ?, 'PAY011_TEST', '{}'::jsonb,
                    ?, ?, 101, 202, ?, CURRENT_TIMESTAMP)
                """, formulaId, legalEntityId, payrollGroupId,
                astDigest, "c".repeat(64), UUID.randomUUID());
    }

    private void seedV49Line(
            UUID snapshotId,
            long snapshotRevision,
            String snapshotDigest,
            UUID publicId,
            UUID cycleId,
            String componentCode,
            String payloadDigest) {
        seedV49Line(
                snapshotId, snapshotRevision, snapshotDigest, publicId, cycleId,
                componentCode, payloadDigest, 1);
    }

    private void seedV49Line(
            UUID snapshotId,
            long snapshotRevision,
            String snapshotDigest,
            UUID publicId,
            UUID cycleId,
            String componentCode,
            String payloadDigest,
            Integer receiptLineCount) {
        UUID eventId = UUID.randomUUID();
        admin.update("""
                INSERT INTO pay_compensation_plan_event_inbox (
                    tenant_id, event_id, aggregate_id, aggregate_sequence,
                    event_type, schema_version, snapshot_id, snapshot_revision,
                    snapshot_payload_digest, correlation_id, envelope_digest,
                    event_envelope, received_at)
                VALUES (1, ?, ?, 1,
                    'dwp.hris.modern.approved-compensation-plan-snapshot-published.v2',
                    2, ?, ?, ?, ?, ?, '{}'::jsonb, CURRENT_TIMESTAMP)
                """, eventId, UUID.randomUUID(), snapshotId, snapshotRevision,
                snapshotDigest, UUID.randomUUID(), "4".repeat(64));
        admin.update("""
                INSERT INTO pay_compensation_plan_inputs (
                    tenant_id, public_id, source_event_id, snapshot_id,
                    snapshot_revision, snapshot_payload_digest, plan_id, cycle_id,
                    approval_receipt_id, approval_revision, source_version,
                    line_sequence, worker_id, assignment_id, component_code,
                    currency, approved_amount, effective_from, effective_to,
                    payload_digest, recorded_at)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, 1, ?, ?, ?,
                    'TST', 100.0000, DATE '2026-10-01', NULL, ?, CURRENT_TIMESTAMP)
                """, publicId, eventId, snapshotId, snapshotRevision, snapshotDigest,
                UUID.randomUUID(), cycleId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), componentCode, payloadDigest);
        if (receiptLineCount != null) {
            admin.update("""
                    INSERT INTO pay_compensation_plan_input_receipts (
                        tenant_id, receipt_id, source_event_id, aggregate_id,
                        aggregate_sequence, snapshot_id, snapshot_revision,
                        snapshot_payload_digest, line_count, outbox_event_id, applied_at)
                    VALUES (1, ?, ?, ?, 1, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), eventId, UUID.randomUUID(), snapshotId,
                    snapshotRevision, snapshotDigest, receiptLineCount,
                    UUID.randomUUID());
        }
    }

    private static void migrate(DataSource dataSource, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "payrollRuntimeRole", RUNTIME_USER,
                        "payrollProjectionPublisherRole", PUBLISHER_USER));
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    private static void seedV49(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID eventId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pay_compensation_plan_event_inbox (
                    tenant_id, event_id, aggregate_id, aggregate_sequence,
                    event_type, schema_version, snapshot_id, snapshot_revision,
                    snapshot_payload_digest, correlation_id, envelope_digest,
                    event_envelope, received_at)
                VALUES (1, ?, ?, 1,
                    'dwp.hris.modern.approved-compensation-plan-snapshot-published.v2',
                    2, ?, 9, ?, ?, ?, '{}'::jsonb, CURRENT_TIMESTAMP)
                """, eventId, UUID.randomUUID(), snapshotId, "a".repeat(64),
                UUID.randomUUID(), "b".repeat(64));
        jdbc.update("""
                INSERT INTO pay_compensation_plan_inputs (
                    tenant_id, public_id, source_event_id, snapshot_id,
                    snapshot_revision, snapshot_payload_digest, plan_id, cycle_id,
                    approval_receipt_id, approval_revision, source_version,
                    line_sequence, worker_id, assignment_id, component_code,
                    currency, approved_amount, effective_from, effective_to,
                    payload_digest, recorded_at)
                VALUES (1, ?, ?, ?, 9, ?, ?, ?, ?, 1, 1, 1, ?, ?, 'BASE',
                    'TST', 3000.0000, DATE '2026-10-01', NULL, ?, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), eventId, snapshotId, "a".repeat(64),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64));
        jdbc.update("""
                INSERT INTO pay_compensation_plan_input_receipts (
                    tenant_id, receipt_id, source_event_id, aggregate_id,
                    aggregate_sequence, snapshot_id, snapshot_revision,
                    snapshot_payload_digest, line_count, outbox_event_id, applied_at)
                VALUES (1, ?, ?, ?, 1, ?, 9, ?, 1, ?, CURRENT_TIMESTAMP)
                """, receiptId, eventId, UUID.randomUUID(), snapshotId,
                "a".repeat(64), UUID.randomUUID());
    }

    private void insertSnapshot(
            JdbcTemplate jdbc, long tenantId, UUID runId, UUID sourceSnapshotId) {
        jdbc.update("""
                INSERT INTO pay_input_snapshots (
                    tenant_id, input_snapshot_id, run_id, attempt_no,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest, formula_set_digest, formula_bindings,
                    engine_version, function_set_version,
                    semantic_binding_digest,
                    target_population_digest, compensation_input_digest,
                    source_line_count, pinned_at)
                VALUES (?, ?, ?, 1, ?, 1, ?, ?, '[]'::jsonb,
                    'PAY_CALCULATION_V1', 'PAY_RULE_AST_V1:1', ?, ?, ?, 1,
                    CURRENT_TIMESTAMP)
                """, tenantId, UUID.randomUUID(), runId, sourceSnapshotId,
                "a".repeat(64), "b".repeat(64), "e".repeat(64),
                "c".repeat(64), "d".repeat(64));
    }

    private void insertTenantPreparingRun(
            JdbcTemplate jdbc, long tenantId, UUID runId, UUID sourceSnapshotId) {
        jdbc.update("""
                INSERT INTO pay_payroll_runs (
                    tenant_id, run_id, run_revision, run_type, pay_period_id,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest, legal_entity_id, payroll_group_id,
                    population_kind, population_scope_digest, state,
                    maker_actor_id, maker_subject_public_id, latest_attempt_no,
                    worker_count, gross_amount, net_amount, currency,
                    result_digest, approval_case_id, decision_digest,
                    approved_by_actor_id, approved_at, audit_bundle_digest,
                    command_receipt_id, recorded_at)
                VALUES (?, ?, 1, 'REGULAR', ?, ?, 1, ?, ?, ?, 'LEGAL_ENTITY', ?,
                    'PREPARING', 101, ?, 0, 0, 0.0000, 0.0000, 'TST',
                    NULL, NULL, NULL, NULL, NULL, NULL, NULL, CURRENT_TIMESTAMP)
                """, tenantId, runId, PayrollCalculationTestSupport.PAY_PERIOD,
                sourceSnapshotId, "a".repeat(64),
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP, "c".repeat(64),
                PayrollCalculationTestSupport.SUBJECT);
    }

    private void insertCalculateReceipt(
            UUID receiptId, UUID runId, String idempotencyKey) {
        admin.update("""
                INSERT INTO pay_command_receipts (
                    tenant_id, receipt_id, status_sequence, receipt_status,
                    idempotency_key, command_type, originating_action,
                    subject_principal_public_id, actor_id, run_id,
                    population_kind, population_scope_digest, context_scope_key,
                    field_policy_revision, purpose_code, authorization_revision,
                    correlation_id, request_digest, aggregate_version, result_ref,
                    step_up_challenge_id, step_up_nonce, step_up_issuer,
                    step_up_expires_at, step_up_payload_sha256,
                    step_up_decision_revision, audit_type, audit_payload,
                    created_at, occurred_at)
                VALUES (1, ?, 1, 'ACCEPTED', ?,
                    'RUN_CALCULATE', 'PAY_RUN_CALCULATE', ?, 101, ?,
                    'LEGAL_ENTITY', ?, 'les:root', 7,
                    'PAYROLL_PROCESSING', 41, ?, ?, 2, ?,
                    NULL, NULL, NULL, NULL, NULL, NULL,
                    'COMMAND_ACCEPTED', '{}'::jsonb,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, receiptId, idempotencyKey,
                PayrollCalculationTestSupport.SUBJECT, runId,
                PayrollCalculationTestSupport.TARGET_DIGEST,
                UUID.randomUUID(), "f".repeat(64), "payroll-run:" + runId);
    }

    private long runtimeSnapshotCount(long tenantId) {
        JdbcTemplate runtime = new JdbcTemplate(runtimeDataSource);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeDataSource));
        Long count = transaction.execute(status -> {
            runtime.queryForObject(
                    "SELECT set_config('dwp.payroll_tenant_id', ?, true)",
                    String.class, Long.toString(tenantId));
            return runtime.queryForObject(
                    "SELECT COUNT(*) FROM pay_input_snapshots", Long.class);
        });
        return count == null ? -1 : count;
    }

    private void insertReceipt(
            UUID receiptId, UUID runId, UUID subjectId,
            String idempotencyKey, String challengeId) {
        admin.update("""
                INSERT INTO pay_command_receipts (
                    tenant_id, receipt_id, status_sequence, receipt_status,
                    idempotency_key, command_type, originating_action,
                    subject_principal_public_id, actor_id, run_id,
                    population_kind, population_scope_digest, context_scope_key,
                    field_policy_revision, purpose_code, authorization_revision,
                    correlation_id, request_digest, aggregate_version, result_ref,
                    step_up_challenge_id, step_up_nonce, step_up_issuer,
                    step_up_expires_at, step_up_payload_sha256,
                    step_up_decision_revision, audit_type, audit_payload,
                    created_at, occurred_at)
                VALUES (1, ?, 1, 'SUCCEEDED', ?, 'RUN_APPROVE', 'PAY_RUN_APPROVE',
                    ?, 202, ?, 'PAY_GROUP', ?, 'scope-key', 7,
                    'PAYROLL_PROCESSING', 41, ?, ?, 5, 'payroll-run:test',
                    ?, ?, 'issuer', CURRENT_TIMESTAMP + INTERVAL '5 minutes', ?,
                    'decision-41', 'COMMAND_ACCEPTED', '{}'::jsonb,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, receiptId, idempotencyKey, subjectId, runId, "a".repeat(64),
                UUID.randomUUID(), "b".repeat(64), challengeId,
                "nonce-" + challengeId, "c".repeat(64));
    }

    private void insertHighRiskReceipt(
            UUID receiptId,
            UUID runId,
            String idempotencyKey,
            String commandType,
            String originatingAction,
            long actorId,
            UUID subjectId,
            long aggregateVersion,
            String challengeId) {
        admin.update("""
                INSERT INTO pay_command_receipts (
                    tenant_id, receipt_id, status_sequence, receipt_status,
                    idempotency_key, command_type, originating_action,
                    subject_principal_public_id, actor_id, run_id,
                    population_kind, population_scope_digest, context_scope_key,
                    field_policy_revision, purpose_code, authorization_revision,
                    correlation_id, request_digest, aggregate_version, result_ref,
                    step_up_challenge_id, step_up_nonce, step_up_issuer,
                    step_up_expires_at, step_up_payload_sha256,
                    step_up_decision_revision, audit_type, audit_payload,
                    created_at, occurred_at)
                VALUES (1, ?, 1, 'SUCCEEDED', ?, ?, ?, ?, ?, ?,
                    'LEGAL_ENTITY', ?, 'les:root', 7, 'PAYROLL_PROCESSING', 41,
                    ?, ?, ?, ?, ?, ?, 'issuer',
                    CURRENT_TIMESTAMP + INTERVAL '5 minutes', ?, 'decision-41',
                    'COMMAND_ACCEPTED', '{}'::jsonb,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, receiptId, idempotencyKey, commandType, originatingAction,
                subjectId, actorId, runId, PayrollCalculationTestSupport.TARGET_DIGEST,
                UUID.randomUUID(), "b".repeat(64), aggregateVersion,
                "payroll-run:" + runId, challengeId, "nonce-" + challengeId,
                "c".repeat(64));
    }

    private void insertFinalizedRevision(
            UUID runId, UUID receiptId, long revision) {
        admin.update("""
                INSERT INTO pay_payroll_runs (
                    tenant_id, run_id, run_revision, run_type, pay_period_id,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest, legal_entity_id, payroll_group_id,
                    population_kind, population_scope_digest, state,
                    maker_actor_id, maker_subject_public_id, latest_attempt_no,
                    worker_count, gross_amount, net_amount, currency,
                    result_digest, approval_case_id, decision_digest,
                    approved_by_actor_id, approved_at, audit_bundle_digest,
                    command_receipt_id, recorded_at)
                SELECT tenant_id, run_id, ?, run_type, pay_period_id,
                       source_snapshot_id, source_snapshot_revision,
                       source_payload_digest, legal_entity_id, payroll_group_id,
                       population_kind, population_scope_digest, 'FINALIZED',
                       maker_actor_id, maker_subject_public_id, latest_attempt_no,
                       worker_count, gross_amount, net_amount, currency,
                       result_digest, approval_case_id, decision_digest,
                       approved_by_actor_id, approved_at, ?, ?, CURRENT_TIMESTAMP
                  FROM pay_payroll_runs
                 WHERE tenant_id = 1 AND run_id = ?
                 ORDER BY run_revision DESC LIMIT 1
                """, revision, "d".repeat(64), receiptId, runId);
    }

    private void insertInvalidInitialCalculateReceipt(UUID runId) {
        UUID receiptId = UUID.randomUUID();
        admin.update("""
                INSERT INTO pay_command_receipts (
                    tenant_id, receipt_id, status_sequence, receipt_status,
                    idempotency_key, command_type, originating_action,
                    subject_principal_public_id, actor_id, run_id,
                    population_kind, population_scope_digest, context_scope_key,
                    field_policy_revision, purpose_code, authorization_revision,
                    correlation_id, request_digest, aggregate_version, result_ref,
                    step_up_challenge_id, step_up_nonce, step_up_issuer,
                    step_up_expires_at, step_up_payload_sha256,
                    step_up_decision_revision, audit_type, audit_payload,
                    created_at, occurred_at)
                SELECT tenant_id, ?, 1, 'SUCCEEDED', ?, command_type,
                       originating_action, subject_principal_public_id, actor_id,
                       run_id, population_kind, population_scope_digest,
                       context_scope_key, field_policy_revision, purpose_code,
                       authorization_revision, ?, request_digest,
                       aggregate_version, result_ref, step_up_challenge_id,
                       step_up_nonce, step_up_issuer, step_up_expires_at,
                       step_up_payload_sha256, step_up_decision_revision,
                       audit_type, audit_payload, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                  FROM pay_command_receipts
                 WHERE tenant_id = 1 AND run_id = ?
                   AND command_type = 'RUN_CALCULATE' AND status_sequence = 1
                 ORDER BY command_receipt_record_internal_id LIMIT 1
                """, receiptId, "invalid-calculate-" + receiptId,
                UUID.randomUUID(), runId);
    }

    private void insertOutboxWithExtraData(UUID runId) {
        admin.update("""
                INSERT INTO pay_outbox_events (
                    tenant_id, event_id, run_id, attempt_no, run_revision,
                    receipt_id, event_type, schema_version, aggregate_sequence,
                    correlation_id, event_payload, occurred_at)
                SELECT tenant_id, event_id, run_id, attempt_no, run_revision,
                       receipt_id, event_type, schema_version, aggregate_sequence,
                       correlation_id,
                       jsonb_set(event_payload, '{data,secret}', '"leak"'::jsonb, true),
                       occurred_at
                  FROM pay_outbox_events
                 WHERE tenant_id = 1 AND run_id = ?
                   AND event_type = 'PayrollCalculated'
                """, runId);
    }

    private void insertIllegalAttemptTransition(UUID runId) {
        admin.update("""
                INSERT INTO pay_payroll_run_attempts (
                    tenant_id, run_id, attempt_no, attempt_id,
                    predecessor_attempt_no, status_sequence, attempt_state,
                    engine_version, command_receipt_id, failure_code, occurred_at)
                SELECT tenant_id, run_id, attempt_no, attempt_id,
                       predecessor_attempt_no, status_sequence + 1, 'CALCULATING',
                       engine_version, command_receipt_id, NULL, CURRENT_TIMESTAMP
                  FROM pay_payroll_run_attempts
                 WHERE tenant_id = 1 AND run_id = ?
                 ORDER BY status_sequence DESC LIMIT 1
                """, runId);
    }

    private void insertIllegalRunTransition(UUID runId) {
        admin.update("""
                INSERT INTO pay_payroll_runs (
                    tenant_id, run_id, run_revision, run_type, pay_period_id,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest, legal_entity_id, payroll_group_id,
                    population_kind, population_scope_digest, state,
                    maker_actor_id, maker_subject_public_id, latest_attempt_no,
                    worker_count, gross_amount, net_amount, currency,
                    result_digest, approval_case_id, decision_digest,
                    approved_by_actor_id, approved_at, audit_bundle_digest,
                    command_receipt_id, recorded_at)
                SELECT tenant_id, run_id, run_revision + 1, run_type, pay_period_id,
                       source_snapshot_id, source_snapshot_revision,
                       source_payload_digest, legal_entity_id, payroll_group_id,
                       population_kind, population_scope_digest, 'APPROVED',
                       maker_actor_id, maker_subject_public_id, latest_attempt_no,
                       worker_count, gross_amount, net_amount, currency,
                       result_digest, approval_case_id, decision_digest,
                       approved_by_actor_id, approved_at, audit_bundle_digest,
                       command_receipt_id, CURRENT_TIMESTAMP
                  FROM pay_payroll_runs
                 WHERE tenant_id = 1 AND run_id = ?
                 ORDER BY run_revision DESC LIMIT 1
                """, runId);
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> result = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static final class CommitThenLoseAcknowledgement
            implements PlatformTransactionManager {
        private final PlatformTransactionManager delegate;
        private final JdbcTemplate observer;
        private final UUID runId;
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicBoolean lost = new AtomicBoolean();

        private CommitThenLoseAcknowledgement(
                PlatformTransactionManager delegate,
                JdbcTemplate observer,
                UUID runId) {
            this.delegate = delegate;
            this.observer = observer;
            this.runId = runId;
        }

        void arm() {
            armed.set(true);
        }

        boolean didLoseAcknowledgement() {
            return lost.get();
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return delegate.getTransaction(definition);
        }

        @Override
        public void commit(TransactionStatus status) {
            delegate.commit(status);
            if (armed.get() && !lost.get() && observer.queryForObject("""
                    SELECT COUNT(*) FROM pay_outbox_events
                     WHERE tenant_id = 1 AND run_id = ?
                       AND event_type = 'PayrollCalculated'
                    """, Long.class, runId) == 1L
                    && lost.compareAndSet(false, true)) {
                armed.set(false);
                throw new TransactionSystemException(
                        "simulated acknowledgement loss after PostgreSQL commit");
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            delegate.rollback(status);
        }
    }

    private static DataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    private static String jdbcUrl(String database) {
        String url = POSTGRES.getJdbcUrl();
        int query = url.indexOf('?');
        String suffix = query < 0 ? "" : url.substring(query);
        String base = query < 0 ? url : url.substring(0, query);
        return base.substring(0, base.lastIndexOf('/') + 1) + database + suffix;
    }
}
