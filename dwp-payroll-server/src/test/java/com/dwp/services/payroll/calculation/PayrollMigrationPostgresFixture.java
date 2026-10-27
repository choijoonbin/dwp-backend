package com.dwp.services.payroll.calculation;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import com.dwp.services.payroll.security.PayrollCalculationAuthority;
import com.dwp.services.payroll.security.PayrollHighRiskCommandGuard;
import com.dwp.services.payroll.security.PayrollStepUpEvidence;
import com.dwp.services.payroll.security.PayrollStepUpHeaders;
import com.dwp.services.payroll.preparation.PayrollPreparationPostgresFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Shared clean/upgrade databases and facts for Payroll migration evidence. */
abstract class PayrollMigrationPostgresFixture {

    static final String RUNTIME_USER = "pay011_runtime";

    static final String RUNTIME_PASSWORD = "pay011-runtime-password";

    static final String PUBLISHER_USER = "pay011_projection_publisher";

    static final String UPGRADE_DATABASE = "pay011_upgrade";

    static final String V51_UPGRADE_DATABASE = "pay013_v51_upgrade";

    static final List<String> V50_TABLES = List.of(
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

    static final List<String> V52_PREPARATION_TABLES = List.of(
            "pay_run_preparation_step_up_uses",
            "pay_run_preparations",
            "pay_run_preparation_sources",
            "pay_run_preparation_decisions",
            "pay_run_preparation_lines",
            "pay_run_preparation_formula_refs");

    static final List<String> V52_AUTHORITY_TABLES = List.of(
            "pay_population_source_revisions",
            "pay_population_source_members",
            "pay_semantic_source_revisions");

    static final List<String> V52_TABLES = java.util.stream.Stream.concat(
            V52_PREPARATION_TABLES.stream(), V52_AUTHORITY_TABLES.stream()).toList();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource adminDataSource;

    static DataSource runtimeDataSource;

    static DataSource publisherDataSource;

    static DataSource upgradeDataSource;

    static DataSource v51UpgradeDataSource;

    static JdbcTemplate admin;

    static JdbcTemplate upgrade;

    static JdbcTemplate v51Upgrade;

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
            statement.execute("CREATE DATABASE " + V51_UPGRADE_DATABASE);
        }
        migrate(adminDataSource, null);
        runtimeDataSource = dataSource(
                POSTGRES.getJdbcUrl(), RUNTIME_USER, RUNTIME_PASSWORD);
        publisherDataSource = dataSource(
                POSTGRES.getJdbcUrl(), PUBLISHER_USER,
                "publisher-test-password");
        admin = new JdbcTemplate(adminDataSource);

        upgradeDataSource = dataSource(
                jdbcUrl(UPGRADE_DATABASE), POSTGRES.getUsername(), POSTGRES.getPassword());
        migrate(upgradeDataSource, MigrationVersion.fromVersion("49"));
        seedV49(upgradeDataSource);
        migrate(upgradeDataSource, null);
        upgrade = new JdbcTemplate(upgradeDataSource);

        v51UpgradeDataSource = dataSource(
                jdbcUrl(V51_UPGRADE_DATABASE),
                POSTGRES.getUsername(), POSTGRES.getPassword());
        migrate(v51UpgradeDataSource, MigrationVersion.fromVersion("51"));
        seedV49(v51UpgradeDataSource);
        migrate(v51UpgradeDataSource, null);
        v51Upgrade = new JdbcTemplate(v51UpgradeDataSource);
    }

    @AfterAll
    static void releaseReferences() {
        upgrade = null;
        v51Upgrade = null;
        admin = null;
        upgradeDataSource = null;
        v51UpgradeDataSource = null;
        runtimeDataSource = null;
        publisherDataSource = null;
        adminDataSource = null;
    }

    @BeforeEach
    void resetV50Facts() {
        admin.execute("TRUNCATE TABLE " + String.join(", ", V50_TABLES)
                + ", pay_compensation_plan_event_inbox"
                + ", pay_formula_versions, pay_foundation_configurations"
                + ", pay_population_source_members"
                + ", pay_population_source_revisions"
                + ", pay_semantic_source_revisions"
                + ", pay_run_preparation_step_up_uses"
                + " RESTART IDENTITY CASCADE");
    }

    Object approvalRace(
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

    PayrollCalculationStore transactionalStore() {
        JdbcPayrollCalculationStore target = new JdbcPayrollCalculationStore(
                new NamedParameterJdbcTemplate(runtimeDataSource),
                new PayrollCalculationCanonical(
                        JsonMapper.builder().findAndAddModules().build()));
        return transactionalProxy(
                target, new DataSourceTransactionManager(runtimeDataSource));
    }

    PayrollCalculationService transactionalService(
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

    PayrollCalculationWorker jdbcWorker() {
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

    UUID calculateValidatedRun(
            PayrollCalculationService service,
            PayrollCalculationWorker worker,
            String snapshotDigit,
            String payloadDigit) {
        UUID snapshotId = UUID.randomUUID();
        String snapshotDigest = snapshotDigit.repeat(64);
        seedV49Line(
                snapshotId, 9, snapshotDigest, UUID.randomUUID(),
                PayrollCalculationTestSupport.PAY_PERIOD, "BASE",
                payloadDigit.repeat(64));
        UUID runId = preparedRun(snapshotId, 9, snapshotDigest);
        service.calculate(
                calculator(), runId, "calculate-" + runId, 2, UUID.randomUUID(),
                new PayrollCalculationModels.ActionCommand("CALCULATE", null, null));
        assertThat(worker.execute(1, runId, 1, "worker-" + runId)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT state FROM pay_payroll_runs
                 WHERE tenant_id = 1 AND run_id = ?
                 ORDER BY run_revision DESC LIMIT 1
                """, String.class, runId)).isEqualTo("VALIDATED");
        return runId;
    }

    UUID calculateReadyRun(
            PayrollCalculationService service,
            String snapshotDigit,
            String payloadDigit) {
        UUID snapshotId = UUID.randomUUID();
        String snapshotDigest = snapshotDigit.repeat(64);
        seedV49Line(
                snapshotId, 9, snapshotDigest, UUID.randomUUID(),
                PayrollCalculationTestSupport.PAY_PERIOD, "BASE",
                payloadDigit.repeat(64));
        UUID runId = preparedRun(snapshotId, 9, snapshotDigest);
        service.calculate(
                calculator(), runId, "calculate-" + runId, 2, UUID.randomUUID(),
                new PayrollCalculationModels.ActionCommand("CALCULATE", null, null));
        return runId;
    }

    UUID preparedRun(
            UUID snapshotId,
            long snapshotRevision,
            String snapshotDigest) {
        return PayrollPreparationPostgresFixture.prepareReadyRun(
                runtimeDataSource, publisherDataSource,
                snapshotId, snapshotRevision, snapshotDigest,
                PayrollCalculationTestSupport.PAY_PERIOD,
                PayrollCalculationTestSupport.LEGAL_ENTITY,
                PayrollCalculationTestSupport.PAY_GROUP,
                PayrollCalculationTestSupport.SUBJECT, 101,
                PayrollCalculationTestSupport.TARGET_DIGEST);
    }

    long currentRunVersion(UUID runId) {
        Long value = admin.queryForObject(
                "SELECT MAX(run_revision) FROM pay_payroll_runs "
                        + "WHERE tenant_id = 1 AND run_id = ?",
                Long.class, runId);
        return value == null ? -1 : value;
    }

    long countRunRows(UUID runId) {
        Long value = admin.queryForObject(
                "SELECT COUNT(*) FROM pay_payroll_runs "
                        + "WHERE tenant_id = 1 AND run_id = ?",
                Long.class, runId);
        return value == null ? -1 : value;
    }

    @SuppressWarnings("unchecked")
    <T> T transactionalProxy(T target, PlatformTransactionManager manager) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(
                new AnnotationTransactionAttributeSource(false));
        proxy.addAdvice(interceptor);
        return (T) proxy.getProxy();
    }

    PayrollHighRiskCommandGuard fixedStepUpGuard(String suffix) {
        PayrollHighRiskCommandGuard guard = mock(PayrollHighRiskCommandGuard.class);
        when(guard.require(any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(stepUp(suffix));
        return guard;
    }

    PayrollStepUpEvidence stepUp(String suffix) {
        return new PayrollStepUpEvidence(
                "challenge-" + suffix, "nonce-" + suffix, "issuer",
                Instant.parse("2026-10-14T02:05:00Z"),
                "e".repeat(64), "decision-41");
    }

    PayrollCalculationAuthority.Actor approver(long actorId) {
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

    PayrollCalculationAuthority.Actor finalizer(long actorId) {
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

    PayrollCalculationAuthority.Actor calculator() {
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

    void insertRun(
            JdbcTemplate jdbc,
            UUID runId,
            String state,
            int latestAttemptNo,
            String resultDigest) {
        insertRun(jdbc, runId, state, latestAttemptNo, resultDigest,
                UUID.fromString("00000000-0000-0000-0000-000000001106"),
                9, PayrollCalculationTestSupport.DIGEST);
    }

    void insertRun(
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

    void insertReadyRevision(
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

    PayrollCalculationModels.RunRevision preparedRun(
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

    void seedFoundationAndFormula(
            UUID legalEntityId,
            UUID payrollGroupId,
            UUID formulaId,
            String astDigest) {
        UUID configurationId = UUID.randomUUID();
        admin.update("""
                INSERT INTO pay_foundation_configurations (
                    tenant_id, configuration_id, current_version, lifecycle_state,
                    legal_entity_id, payroll_group_id, effective_from, effective_to,
                    author_id, publisher_id, simulation_report, last_command_id,
                    created_at, updated_at)
                VALUES (1, ?, 1, 'PUBLISHED', ?, ?, DATE '2026-01-01', NULL,
                    101, 202, '{}'::jsonb, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, configurationId, legalEntityId, payrollGroupId,
                UUID.randomUUID());
        admin.update("""
                INSERT INTO pay_foundation_versions (
                    tenant_id, configuration_id, version, definition,
                    definition_digest, dependency_digest, lifecycle_state,
                    simulation_report, authored_by, published_by,
                    command_id, recorded_at)
                VALUES (1, ?, 1, '{}'::jsonb, ?, ?, 'PUBLISHED',
                    '{}'::jsonb, 101, 202, ?, CURRENT_TIMESTAMP)
                """, configurationId, "d".repeat(64), "e".repeat(64),
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

    void seedV49Line(
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

    void seedV49Line(
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

    static void migrate(DataSource dataSource, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "payrollRuntimeRole", RUNTIME_USER,
                        "payrollProjectionPublisherRole", PUBLISHER_USER));
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    static void seedV49(DataSource dataSource) {
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

    void insertSnapshot(
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

    void insertTenantPreparingRun(
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

    void insertCalculateReceipt(
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

    long runtimeSnapshotCount(long tenantId) {
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

    void insertReceipt(
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

    void insertHighRiskReceipt(
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

    void insertFinalizedRevision(
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

    void insertInvalidInitialCalculateReceipt(UUID runId) {
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

    void insertOutboxWithExtraData(UUID runId) {
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

    void insertIllegalAttemptTransition(UUID runId) {
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

    void insertIllegalRunTransition(UUID runId) {
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

    Set<String> fieldNames(JsonNode node) {
        Set<String> result = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    static String sqlNames(List<String> names) {
        return names.stream()
                .map(name -> "'" + name + "'")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }

    static DataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    static String jdbcUrl(String database) {
        String url = POSTGRES.getJdbcUrl();
        int query = url.indexOf('?');
        String suffix = query < 0 ? "" : url.substring(query);
        String base = query < 0 ? url : url.substring(0, query);
        return base.substring(0, base.lastIndexOf('/') + 1) + database + suffix;
    }
}
