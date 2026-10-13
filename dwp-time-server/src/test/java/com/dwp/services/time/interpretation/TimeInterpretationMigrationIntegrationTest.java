package com.dwp.services.time.interpretation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.services.time.interpretation.InterpretationModels.InterpretationAppend;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationComputation;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationQuery;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationRun;
import com.dwp.services.time.interpretation.InterpretationModels.RunStatus;
import com.dwp.services.time.interpretation.InterpretationModels.TraceStep;
import com.dwp.services.time.interpretation.InterpretationModels.VerifiedInterpretationRequest;
import com.dwp.services.time.interpretation.InterpretationModels.WorkerMinuteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimeInterpretationMigrationIntegrationTest {

    private static final String RUNTIME_ROLE = "tim_interpret_runtime";
    private static final String PUBLISHER_ROLE = "tim_interpret_publisher";
    private static final long TENANT = 49_010L;
    private static final long QUERY_TENANT = 49_011L;
    private static final UUID RULE_ID = uuid("49010000-0000-4000-8000-000000000001");
    private static final UUID QUERY_RULE_LOW =
            uuid("49011000-0000-4000-8000-000000000001");
    private static final UUID QUERY_RULE_HIGH =
            uuid("49011000-0000-4000-8000-000000000002");
    private static final UUID WORKER = uuid("49010000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");

    private PostgreSQLContainer<?> postgres;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcInterpretationRepository repository;

    @BeforeAll
    void migrateAndSeedRule() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker is required for the TIM interpretation migration gate")
                .isTrue();
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        dataSource = dataSource();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE ROLE " + RUNTIME_ROLE
                + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        jdbc.execute("CREATE ROLE " + PUBLISHER_ROLE
                + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .validateOnMigrate(true)
                .placeholderReplacement(true)
                .placeholders(Map.of(
                        "timeRuntimeRole", RUNTIME_ROLE,
                        "timeProjectionPublisherRole", PUBLISHER_ROLE))
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(10);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success "
                        + "AND version='10' AND script='V10__tim_base_tfr_tim_010.sql'",
                Integer.class)).isOne();
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(ignored -> {
            jdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', ?, true)", String.class,
                    Long.toString(TENANT));
            jdbc.update("""
                    INSERT INTO tme_interpretation_rule_versions (
                        public_id, tenant_id, revision, effective_from, effective_to,
                        lifecycle_state, registry_id, registry_version, rule_payload,
                        rule_digest, published_at)
                    VALUES (?, ?, 7, ?, ?, 'PUBLISHED', 'TIM_RULE_AST_V1', 1,
                            CAST('[{}]' AS jsonb), ?, ?)
                    """,
                    RULE_ID, TENANT, LocalDate.parse("2026-01-01"),
                    LocalDate.parse("2027-01-01"), "c".repeat(64), Timestamp.from(NOW));
            jdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', ?, true)", String.class,
                    Long.toString(QUERY_TENANT));
            insertQueryRule(
                    QUERY_RULE_LOW, 7,
                    LocalDate.parse("2025-01-01"), LocalDate.parse("2026-01-01"));
            insertQueryRule(
                    QUERY_RULE_HIGH, 8,
                    LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"));
        });
        repository = new JdbcInterpretationRepository(
                jdbc,
                new DataSourceTransactionManager(dataSource),
                new ObjectMapper().findAndRegisterModules());
    }

    @AfterAll
    void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void concurrentSemanticReplayPostsOneLedgerDeltaAndKeepsBothResultSegments()
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> appendAfterBarrier(
                    uuid("49010000-0000-4000-8000-000000000011"),
                    uuid("49010000-0000-4000-8000-000000000021"), ready, start));
            var second = executor.submit(() -> appendAfterBarrier(
                    uuid("49010000-0000-4000-8000-000000000012"),
                    uuid("49010000-0000-4000-8000-000000000022"), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS).outputDigest())
                    .isEqualTo(second.get(15, TimeUnit.SECONDS).outputDigest());
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tme_interpretation_runs WHERE tenant_id = ?",
                Integer.class, TENANT)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tme_interpretation_runs "
                        + "WHERE tenant_id = ? AND ledger_delta_applied",
                Integer.class, TENANT)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tme_time_ledger_entries WHERE tenant_id = ?",
                Integer.class, TENANT)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tme_interpreted_segments WHERE tenant_id = ?",
                Integer.class, TENANT)).isEqualTo(6);
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT worker_scope_digest) FROM tme_interpretation_runs "
                        + "WHERE tenant_id = ?",
                Integer.class, TENANT)).isOne();
        UUID runId = jdbc.queryForObject(
                "SELECT public_id FROM tme_interpretation_runs WHERE tenant_id = ? LIMIT 1",
                UUID.class, TENANT);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE tme_interpretation_runs SET status='FAILED' "
                        + "WHERE tenant_id = ? AND public_id = ?",
                TENANT, runId)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                 WHERE schemaname='public'
                   AND indexname IN (
                       'uk_tme_time_ledger_single_reversal',
                       'uk_tme_interpretation_semantic_ledger')
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void queryFiltersAndOrdersBeforePagingAndProjectsActualTraceCounts() {
        UUID lowOne = uuid("49011000-0000-4000-8000-000000000011");
        UUID lowTwo = uuid("49011000-0000-4000-8000-000000000012");
        UUID high = uuid("49011000-0000-4000-8000-000000000013");
        appendQueryRun(lowOne, QUERY_RULE_LOW, 7, 1, NOW.minusSeconds(1));
        appendQueryRun(lowTwo, QUERY_RULE_LOW, 7, 2, NOW.minusSeconds(2));
        appendQueryRun(high, QUERY_RULE_HIGH, 8, 3, NOW.minusSeconds(30));

        var onlyHighRule = new InterpretationIaQueryModels.InterpretationFilter(
                null,
                new InterpretationIaQueryModels.PolicyFilter(
                        List.of("tim.rule." + QUERY_RULE_HIGH), 8L),
                null,
                InterpretationTestFixtures.WORK_DATE);
        var filtered = repository.query(new InterpretationQuery(
                QUERY_TENANT,
                Set.of(WORKER),
                NOW.plusSeconds(1),
                2,
                0,
                onlyHighRule,
                List.of("itemPublicId:ASC")));
        assertThat(filtered).singleElement().satisfies(row -> {
            assertThat(row.runPublicId()).isEqualTo(high);
            assertThat(row.traceItemCount()).isEqualTo(3);
        });

        var traceDescending = repository.query(new InterpretationQuery(
                QUERY_TENANT,
                Set.of(WORKER),
                NOW.plusSeconds(1),
                3,
                0,
                InterpretationIaQueryModels.InterpretationFilter.empty(),
                List.of("traceSummary:DESC")));
        assertThat(traceDescending)
                .extracting(row -> itemId(row.runPublicId(), row.workerPublicId()))
                .containsExactly(
                        itemId(high, WORKER),
                        itemId(lowTwo, WORKER),
                        itemId(lowOne, WORKER));
        assertThat(traceDescending)
                .extracting(InterpretationModels.InterpretationSummary::traceItemCount)
                .containsExactly(3, 2, 1);

        assertTraceCountPages(high, lowTwo);
    }

    private void assertTraceCountPages(UUID high, UUID lowTwo) {
        var authority = queryViewAuthority();
        var projections = new InterpretationIaProjectionService(
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                "tim-query-integration-cursor-secret");
        var traceFilter = new InterpretationIaQueryModels.InterpretationFilter(
                null,
                null,
                new InterpretationIaQueryModels.AggregateFilter(2L, 3L),
                null);
        var firstRequest = new InterpretationIaQueryModels.InterpretationQueryRequest(
                1,
                traceFilter,
                List.of("traceSummary:DESC"),
                "en-US",
                "UTC",
                authority.correlationId(),
                NOW.plusSeconds(1),
                null);
        var firstPrepared = projections.prepare(authority, firstRequest);
        var firstRows = repository.query(query(authority, firstPrepared));
        var first = projections.projectWindow(authority, firstRequest, firstRows);
        assertThat(first.items()).singleElement().satisfies(item -> {
            assertThat(item.itemPublicId()).isEqualTo(itemId(high, WORKER));
            assertThat(item.fieldGroups().TRACE_SUMMARY().value().itemCount()).isEqualTo(3);
        });
        assertThat(first.queryState().nextCursor()).isNotBlank();

        var secondRequest = new InterpretationIaQueryModels.InterpretationQueryRequest(
                1,
                traceFilter,
                List.of("traceSummary:DESC"),
                "en-US",
                "UTC",
                authority.correlationId(),
                NOW.plusSeconds(1),
                first.queryState().nextCursor());
        var secondPrepared = projections.prepare(authority, secondRequest);
        var secondRows = repository.query(query(authority, secondPrepared));
        var second = projections.projectWindow(authority, secondRequest, secondRows);
        assertThat(second.items()).singleElement().satisfies(item -> {
            assertThat(item.itemPublicId()).isEqualTo(itemId(lowTwo, WORKER));
            assertThat(item.fieldGroups().TRACE_SUMMARY().value().itemCount()).isEqualTo(2);
        });
        assertThat(second.queryState().nextCursor()).isNull();
    }

    private static InterpretationQuery query(
            VerifiedInterpretationRequest authority,
            InterpretationIaProjectionService.PreparedQuery prepared) {
        return new InterpretationQuery(
                authority.tenantId(),
                authority.authorizedWorkerPublicIds(),
                prepared.asOf(),
                prepared.limit() + 1,
                prepared.offset(),
                prepared.filter(),
                prepared.sort());
    }

    private static VerifiedInterpretationRequest queryViewAuthority() {
        return new VerifiedInterpretationRequest(
                QUERY_TENANT,
                72,
                InterpretationTestFixtures.SUBJECT,
                Set.of("hcm.operations.time.view", "hcm.operations.time.update"),
                Set.of(
                        InterpretationModels.InterpretationAction.VIEW,
                        InterpretationModels.InterpretationAction.RUN,
                        InterpretationModels.InterpretationAction.TRACE),
                InterpretationModels.PopulationKind.TIME_GROUP,
                InterpretationTestFixtures.POPULATION,
                9,
                11,
                InterpretationTestFixtures.DIGEST,
                13,
                InterpretationIaProjectionService.VIEW_PURPOSE,
                37,
                "psr-" + "b".repeat(64),
                NOW.plusSeconds(300),
                InterpretationTestFixtures.CORRELATION,
                Set.of(WORKER),
                Set.of(),
                Set.of());
    }

    private void appendQueryRun(
            UUID runId,
            UUID ruleId,
            long ruleRevision,
            int traceCount,
            Instant createdAt) {
        List<TraceStep> trace = java.util.stream.IntStream.rangeClosed(1, traceCount)
                .mapToObj(sequence -> new TraceStep(
                        sequence,
                        "TRACE_STEP_" + sequence,
                        480 - sequence,
                        480 - sequence))
                .toList();
        List<WorkerMinuteResult> results = List.of(
                new WorkerMinuteResult(WORKER, 480, 480, 0, 60));
        InterpretationComputation computation = new InterpretationComputation(
                InterpretationDigests.sha256("query-input", runId),
                InterpretationDigests.sha256("query-rule", ruleId, ruleRevision),
                InterpretationDigests.sha256("query-output", runId),
                results,
                trace);
        InterpretationLedgerPlanner.Plan plan = InterpretationLedgerPlanner.plan(
                runId, computation, null);
        InterpretationRun run = new InterpretationRun(
                runId,
                QUERY_TENANT,
                InterpretationTestFixtures.SUBJECT,
                InterpretationTestFixtures.WORK_DATE,
                InterpretationTestFixtures.WORK_DATE,
                InterpretationTestFixtures.AS_OF,
                ruleId,
                ruleRevision,
                computation.inputDigest(),
                computation.ruleDigest(),
                computation.outputDigest(),
                1,
                RunStatus.SUCCEEDED,
                plan.ledgerDeltaApplied(),
                null,
                InterpretationTestFixtures.CORRELATION,
                createdAt,
                results,
                plan.entries(),
                trace);
        UUID idempotencyKey = InterpretationDigests.stableUuid("query-idempotency", runId);
        repository.append(new InterpretationAppend(
                InterpretationTestFixtures.authority(
                        QUERY_TENANT,
                        Set.of(InterpretationService.RUN_PERMISSION),
                        InterpretationModels.PopulationKind.ORG_UNIT,
                        Set.of(WORKER)),
                idempotencyKey,
                InterpretationDigests.sha256("query-request", idempotencyKey),
                run));
    }

    private void insertQueryRule(
            UUID ruleId,
            long revision,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        jdbc.update("""
                INSERT INTO tme_interpretation_rule_versions (
                    public_id, tenant_id, revision, effective_from, effective_to,
                    lifecycle_state, registry_id, registry_version, rule_payload,
                    rule_digest, published_at)
                VALUES (?, ?, ?, ?, ?, 'PUBLISHED', 'TIM_RULE_AST_V1', 1,
                        CAST('[{}]' AS jsonb), ?, ?)
                """,
                ruleId,
                QUERY_TENANT,
                revision,
                effectiveFrom,
                effectiveTo,
                InterpretationDigests.sha256("query-rule-row", ruleId),
                Timestamp.from(NOW));
    }

    private InterpretationModels.InterpretationCommandReceipt appendAfterBarrier(
            UUID runId,
            UUID idempotencyKey,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("barrier timed out");
        InterpretationRun run = candidate(runId);
        return repository.append(new InterpretationAppend(
                InterpretationTestFixtures.authority(
                        TENANT,
                        java.util.Set.of(InterpretationService.RUN_PERMISSION),
                        InterpretationModels.PopulationKind.ORG_UNIT,
                        java.util.Set.of(WORKER)),
                idempotencyKey,
                InterpretationDigests.sha256("request", idempotencyKey),
                run));
    }

    private static InterpretationRun candidate(UUID runId) {
        List<WorkerMinuteResult> results = List.of(
                new WorkerMinuteResult(WORKER, 480, 480, 0, 60));
        List<TraceStep> trace = List.of(new TraceStep(1, "REGULAR_MINUTES", 480, 480));
        InterpretationComputation computation = new InterpretationComputation(
                "b".repeat(64), "c".repeat(64), "d".repeat(64), results, trace);
        InterpretationLedgerPlanner.Plan plan = InterpretationLedgerPlanner.plan(
                runId, computation, null);
        return new InterpretationRun(
                runId,
                TENANT,
                InterpretationTestFixtures.SUBJECT,
                InterpretationTestFixtures.WORK_DATE,
                InterpretationTestFixtures.WORK_DATE,
                InterpretationTestFixtures.AS_OF,
                RULE_ID,
                7,
                computation.inputDigest(),
                computation.ruleDigest(),
                computation.outputDigest(),
                1,
                RunStatus.SUCCEEDED,
                plan.ledgerDeltaApplied(),
                null,
                InterpretationTestFixtures.CORRELATION,
                NOW,
                results,
                plan.entries(),
                trace);
    }

    private DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static String itemId(UUID runId, UUID workerId) {
        return InterpretationDigests.sha256(
                "IAQ-041-InterpretationItem.v1", runId, workerId);
    }
}
