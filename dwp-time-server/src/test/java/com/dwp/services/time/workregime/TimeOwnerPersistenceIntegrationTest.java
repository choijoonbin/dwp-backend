package com.dwp.services.time.workregime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.Command;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.IdempotencyConflictException;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.ReceiptPhase;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.Reservation;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.TerminalOutcome;
import com.dwp.services.time.workregime.WorkRegimeApiModels.SimulationRequest;
import com.dwp.services.time.workregime.WorkRegimeModels.ArrangementKind;
import com.dwp.services.time.workregime.WorkRegimeModels.Authority;
import com.dwp.services.time.workregime.WorkRegimeModels.CommandReceipt;
import com.dwp.services.time.workregime.WorkRegimeModels.DstOverlapPolicy;
import com.dwp.services.time.workregime.WorkRegimeModels.Duty;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeModels.LocalSegment;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyExtensionKind;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeModels.ReceiptState;
import com.dwp.services.time.workregime.WorkRegimeModels.ScopeType;
import com.dwp.services.time.workregime.WorkRegimeModels.SegmentKind;
import com.dwp.services.time.workregime.WorkRegimeRepository.CommandEvidence;
import com.dwp.services.time.workregime.WorkRegimeRepository.DraftWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.PolicyTermWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.TransitionWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.WorkPlanRecord;
import com.dwp.services.time.workregime.WorkRegimeOwnerAuthoritySource.VerifiedRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
class TimeOwnerPersistenceIntegrationTest {

    private static final long TENANT = 71L;
    private static final long AUTHOR = 7_101L;
    private static final String RUNTIME_ROLE = "tim_owner_it_runtime";
    private static final String RUNTIME_PASSWORD = "tim-owner-it-runtime-password";
    private static final String PURPOSE = "TIME_CONFIGURATION";
    private static final String DECISION = "psr-" + "7".repeat(64);
    private static final String SCOPE = "tenant:71";
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);
    private static final String DIGEST_D = "d".repeat(64);
    private static final String DIGEST_E = "e".repeat(64);
    private static final String DIGEST_F = "f".repeat(64);
    private static final UUID RULE_PACK_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID WORK_PLAN_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000002");
    private static final UUID ASSIGNMENT_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000003");
    private static final UUID WORKER_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000004");
    private static final UUID PEOPLE_ASSIGNMENT_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000005");
    private static final UUID CREATE_IDEMPOTENCY_KEY =
            UUID.fromString("71000000-0000-0000-0000-000000000006");
    private static final UUID VALIDATE_IDEMPOTENCY_KEY =
            UUID.fromString("71000000-0000-0000-0000-000000000007");
    private static final UUID CREATE_CORRELATION_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000008");
    private static final UUID VALIDATE_CORRELATION_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000009");
    private static final UUID SIMULATE_IDEMPOTENCY_KEY =
            UUID.fromString("71000000-0000-4000-8000-000000000010");
    private static final UUID REPLAY_IDEMPOTENCY_KEY =
            UUID.fromString("71000000-0000-4000-8000-000000000012");
    private static final EffectivePeriod PERIOD = new EffectivePeriod(
            LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1));
    private static final Instant NOW = Instant.parse("2026-09-17T08:00:00Z");

    private PostgreSQLContainer<?> postgres;
    private JdbcTemplate admin;
    private TransactionTemplate adminTransactions;
    private JdbcTemplate runtime;
    private TransactionTemplate runtimeTransactions;
    private DataSourceTransactionManager runtimeTransactionManager;
    private JdbcWorkRegimeRepository repository;
    private WorkRegimeCommandCoordinator commands;

    @BeforeAll
    void migrateOnlyTheLeasedV1AndCreateAdapters() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker is required for the owner persistence integration test")
                .isTrue();
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        DataSource adminDataSource = dataSource(
                postgres.getUsername(), postgres.getPassword());
        admin = new JdbcTemplate(adminDataSource);
        adminTransactions = transactions(adminDataSource);
        admin.execute("CREATE ROLE " + RUNTIME_ROLE
                + " LOGIN PASSWORD '" + RUNTIME_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");

        Flyway flyway = Flyway.configure()
                .dataSource(adminDataSource)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .table("flyway_schema_history")
                .target(MigrationVersion.fromVersion("1"))
                .repeatableSqlMigrationPrefix("DO_NOT_RUN_REPEATABLE_")
                .placeholderReplacement(true)
                .placeholders(Map.of("timeRuntimeRole", RUNTIME_ROLE))
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isOne();
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success",
                Integer.class)).isOne();
        assertThat(admin.queryForObject(
                "SELECT script FROM flyway_schema_history WHERE success AND version = '1'",
                String.class)).isEqualTo("V1__tim_create_work_regime_foundation.sql");

        DataSource runtimeDataSource = dataSource(RUNTIME_ROLE, RUNTIME_PASSWORD);
        runtime = new JdbcTemplate(runtimeDataSource);
        runtimeTransactionManager = new DataSourceTransactionManager(runtimeDataSource);
        runtimeTransactions = new TransactionTemplate(runtimeTransactionManager);
        JdbcWorkRegimeReceiptStore receipts =
                new JdbcWorkRegimeReceiptStore(runtime, runtimeTransactionManager);
        repository = new JdbcWorkRegimeRepository(runtime, runtimeTransactionManager);
        commands = new WorkRegimeCommandCoordinator(
                receipts, Clock.fixed(NOW, ZoneOffset.UTC));
        seedPublishedRulePack();
    }

    @AfterAll
    void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void receiptBackedDraftPersistsReadModelAndImmutableEvidenceThenValidates() {
        Authority authority = new Authority(
                TENANT, AUTHOR, Set.of(Duty.TIME_CONFIG_AUTHOR), Set.of(SCOPE),
                PURPOSE, DECISION, true, false);
        Command create = new Command(
                authority, CREATE_IDEMPOTENCY_KEY, LifecycleAction.CREATE_DRAFT,
                WORK_PLAN_ID, SCOPE, null, DIGEST_A, CREATE_CORRELATION_ID);

        CommandReceipt created = commands.executeWithReceipt(create, (ignored, running) -> {
            assertThat(running.state()).isEqualTo(ReceiptState.RUNNING);
            WorkPlanRecord stored = repository.createDraft(draft(
                    evidence(running.receiptId(), CREATE_CORRELATION_ID)));
            assertThat(stored.revision().state()).isEqualTo(PolicyState.DRAFT);
            return TerminalOutcome.succeeded("DRAFT_CREATED", DIGEST_F);
        });

        assertThat(created.state()).isEqualTo(ReceiptState.SUCCEEDED);
        assertThat(created.operation()).isEqualTo(LifecycleAction.CREATE_DRAFT);
        assertThat(created.aggregateId()).isEqualTo(WORK_PLAN_ID);
        assertThat(created.idempotencyKey()).isEqualTo(CREATE_IDEMPOTENCY_KEY);
        assertThat(commands.receipt(TENANT, created.receiptId())).contains(created);

        WorkPlanRecord readBack = repository.findByPublicId(TENANT, WORK_PLAN_ID)
                .orElseThrow();
        assertThat(readBack.revision().version()).isOne();
        assertThat(readBack.revision().state()).isEqualTo(PolicyState.DRAFT);
        assertThat(readBack.rulePackPublicId()).isEqualTo(RULE_PACK_ID);
        assertThat(readBack.policyRevision()).isOne();
        assertThat(readBack.assignmentPublicId()).isEqualTo(ASSIGNMENT_ID);
        assertThat(tenantValue(() -> runtime.queryForObject("""
                SELECT rule_pack_public_id
                  FROM tim_work_plan_assignments
                 WHERE tenant_id = ? AND public_id = ?
                """, UUID.class, TENANT, ASSIGNMENT_ID))).isEqualTo(RULE_PACK_ID);
        assertThat(readBack.segments())
                .extracting(LocalSegment::key)
                .containsExactly("MONDAY_CORE");

        assertEvidence(
                created.receiptId(), "WORK_REGIME_DRAFT_CREATED", PolicyState.DRAFT.name());
        assertAuditIsImmutable(created.receiptId());

        Command validate = new Command(
                authority, VALIDATE_IDEMPOTENCY_KEY, LifecycleAction.VALIDATE,
                WORK_PLAN_ID, SCOPE, 1L, DIGEST_B, VALIDATE_CORRELATION_ID);
        CommandReceipt validated = commands.executeWithReceipt(validate, (ignored, running) -> {
            assertThat(running.state()).isEqualTo(ReceiptState.RUNNING);
            var revision = repository.transition(new TransitionWrite(
                    TENANT, WORK_PLAN_ID, 1L, PolicyState.VALIDATED,
                    null, null, null, null, AUTHOR,
                    evidence(running.receiptId(), VALIDATE_CORRELATION_ID)));
            return TerminalOutcome.succeeded("VALIDATED", revision.artifactDigest());
        });

        assertThat(validated.state()).isEqualTo(ReceiptState.SUCCEEDED);
        assertThat(validated.operation()).isEqualTo(LifecycleAction.VALIDATE);
        WorkPlanRecord validatedPlan = repository.findByPublicId(TENANT, WORK_PLAN_ID)
                .orElseThrow();
        assertThat(validatedPlan.revision().state()).isEqualTo(PolicyState.VALIDATED);
        assertThat(validatedPlan.revision().version()).isEqualTo(2L);
        assertEvidence(
                validated.receiptId(), "WORK_REGIME_VALIDATED", PolicyState.VALIDATED.name());
        assertThat(tenantValue(() -> runtime.queryForObject(
                "SELECT count(*) FROM tim_work_regime_audit_events WHERE tenant_id = ?",
                Long.class, TENANT))).isEqualTo(2L);
        assertThat(tenantValue(() -> runtime.queryForObject(
                "SELECT count(*) FROM tim_work_regime_outbox_events WHERE tenant_id = ?",
                Long.class, TENANT))).isEqualTo(2L);

        WorkRegimeApplicationService service = new WorkRegimeApplicationService(
                repository,
                new JdbcWorkRegimeReceiptStore(runtime, runtimeTransactionManager),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        VerifiedRequest verified = new VerifiedRequest(
                authority, SCOPE, DECISION, NOW.plusSeconds(300),
                UUID.fromString("71000000-0000-4000-8000-000000000011"));
        var simulation = service.simulate(
                verified,
                WORK_PLAN_ID,
                SIMULATE_IDEMPOTENCY_KEY,
                new SimulationRequest(2L, "12", "1", "PRE_PUBLISH_IMPACT_REVIEW"));

        assertThat(simulation.receipt().status())
                .withFailMessage(
                        "runs=%s audits=%s outbox=%s plan=%s",
                        tenantValue(() -> runtime.queryForObject(
                                "SELECT count(*) FROM tim_schedule_simulation_runs WHERE tenant_id = ?",
                                Long.class, TENANT)),
                        tenantValue(() -> runtime.queryForObject(
                                "SELECT count(*) FROM tim_work_regime_audit_events WHERE tenant_id = ?",
                                Long.class, TENANT)),
                        tenantValue(() -> runtime.queryForObject(
                                "SELECT count(*) FROM tim_work_regime_outbox_events WHERE tenant_id = ?",
                                Long.class, TENANT)),
                        repository.findByPublicId(TENANT, WORK_PLAN_ID)
                                .orElseThrow().revision().state())
                .isEqualTo(ReceiptState.SUCCEEDED.name());
        assertThat(simulation.receipt().workPlanId()).isEqualTo(WORK_PLAN_ID.toString());
        assertThat(simulation.receipt().operation()).isEqualTo("SIMULATE");
        assertThat(simulation.receipt().idempotencyKey())
                .isEqualTo(SIMULATE_IDEMPOTENCY_KEY.toString());
        assertThat(simulation.simulation()).isNotNull();
        assertThat(simulation.simulation().rows())
                .extracting(row -> row.label())
                .containsExactly("MONDAY_CORE");
        WorkPlanRecord simulatedPlan = repository.findByPublicId(TENANT, WORK_PLAN_ID)
                .orElseThrow();
        assertThat(simulatedPlan.revision().state()).isEqualTo(PolicyState.SIMULATED);
        assertThat(simulatedPlan.revision().version()).isEqualTo(3L);
        assertThat(tenantValue(() -> runtime.queryForObject(
                "SELECT count(*) FROM tim_schedule_simulation_runs WHERE tenant_id = ?",
                Long.class, TENANT))).isOne();
        assertThat(tenantValue(() -> runtime.queryForObject(
                "SELECT count(*) FROM tim_work_regime_audit_events WHERE tenant_id = ?",
                Long.class, TENANT))).isEqualTo(4L);
        assertThat(tenantValue(() -> runtime.queryForObject(
                "SELECT count(*) FROM tim_work_regime_outbox_events WHERE tenant_id = ?",
                Long.class, TENANT))).isEqualTo(4L);
    }

    @Test
    void jdbcReplayLookupRejectsScopeActorAndExpectedVersionSubstitution() {
        Authority authority = authority(AUTHOR, SCOPE);
        Command original = command(
                authority, REPLAY_IDEMPOTENCY_KEY, WORK_PLAN_ID, SCOPE, 3L, DIGEST_C);
        CommandReceipt completed = commands.execute(
                original, ignored -> TerminalOutcome.succeeded("PUBLISHED", DIGEST_F));

        assertThat(commands.findReplay(original)).contains(completed);
        assertThat(tenantValue(() -> runtime.queryForObject("""
                SELECT scope_public_ref
                  FROM tim_command_receipts
                 WHERE tenant_id = ? AND public_id = ?
                """, String.class, TENANT, completed.receiptId()))).isEqualTo(SCOPE);
        assertThatThrownBy(() -> commands.findReplay(command(
                authority(AUTHOR, "tenant:other"), REPLAY_IDEMPOTENCY_KEY,
                WORK_PLAN_ID, "tenant:other", 3L, DIGEST_C)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> commands.findReplay(command(
                authority(AUTHOR + 1, SCOPE), REPLAY_IDEMPOTENCY_KEY,
                WORK_PLAN_ID, SCOPE, 3L, DIGEST_C)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> commands.findReplay(command(
                authority, REPLAY_IDEMPOTENCY_KEY,
                WORK_PLAN_ID, SCOPE, 4L, DIGEST_C)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void jdbcStaleAcceptedAndRunningClaimsReconcileWithoutDuplicateWork() {
        JdbcWorkRegimeReceiptStore store =
                new JdbcWorkRegimeReceiptStore(runtime, runtimeTransactionManager);
        WorkRegimeCommandCoordinator recovery = new WorkRegimeCommandCoordinator(
                store, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));
        for (int index = 0; index < 2; index++) {
            ReceiptPhase abandonedPhase = index == 0
                    ? ReceiptPhase.ACCEPTED : ReceiptPhase.RUNNING;
            UUID key = UUID.fromString(
                    "71000000-0000-4000-8000-00000000002" + index);
            UUID aggregate = UUID.fromString(
                    "71000000-0000-4000-8000-00000000003" + index);
            Command command = command(
                    authority(AUTHOR, SCOPE), key, aggregate, SCOPE, 9L, DIGEST_D);
            Instant abandonedAt = NOW.minus(Duration.ofMinutes(6));
            Reservation reserved = store.reserve(command, abandonedAt);
            CommandReceipt abandoned = reserved.stored().receipt();
            if (abandonedPhase == ReceiptPhase.RUNNING) {
                abandoned = store.transition(
                        TENANT, abandoned.receiptId(), ReceiptPhase.ACCEPTED,
                        ReceiptPhase.RUNNING, null, null, abandonedAt).receipt();
            }
            AtomicInteger reconciliations = new AtomicInteger();

            CommandReceipt recovered = recovery.recover(
                    TENANT, abandoned.receiptId(), ignored -> {
                        reconciliations.incrementAndGet();
                        return TerminalOutcome.succeeded("RECOVERED", DIGEST_F);
                    });
            CommandReceipt replay = recovery.recover(
                    TENANT, abandoned.receiptId(), ignored -> {
                        reconciliations.incrementAndGet();
                        return TerminalOutcome.failed("MUST_NOT_RUN", null);
                    });

            assertThat(recovered.state()).isEqualTo(ReceiptState.SUCCEEDED);
            assertThat(replay).isEqualTo(recovered);
            assertThat(reconciliations).hasValue(1);
        }
    }

    private static Authority authority(long actorId, String scope) {
        return new Authority(
                TENANT, actorId, Set.of(Duty.TIME_CONFIG_APPROVER), Set.of(scope),
                PURPOSE, DECISION, true, false);
    }

    private static Command command(
            Authority authority,
            UUID idempotencyKey,
            UUID aggregateId,
            String scope,
            Long expectedVersion,
            String digest) {
        return new Command(
                authority, idempotencyKey, LifecycleAction.PUBLISH, aggregateId,
                scope, expectedVersion, digest,
                UUID.nameUUIDFromBytes((idempotencyKey + ":correlation").getBytes()));
    }

    private DraftWrite draft(CommandEvidence evidence) {
        LocalSegment segment = new LocalSegment(
                "MONDAY_CORE", DayOfWeek.MONDAY, SegmentKind.WORK,
                LocalTime.of(9, 0), LocalTime.of(18, 0), 0, DstOverlapPolicy.REJECT);
        return new DraftWrite(
                TENANT, WORK_PLAN_ID, ASSIGNMENT_ID, WORKER_ID, PEOPLE_ASSIGNMENT_ID,
                12L, "KR-OWNER-WAVE1", 1L, "Seoul standard work plan",
                ArrangementKind.FIXED, null, ScopeType.TENANT, SCOPE, 100, PERIOD,
                "Asia/Seoul", RULE_PACK_ID, "KR", "", 1L, DIGEST_C, 1,
                DIGEST_D, DIGEST_E, AUTHOR, CREATE_CORRELATION_ID,
                List.of(new PolicyTermWrite(
                        PolicyExtensionKind.BREAK,
                        "MIN_BREAK_MINUTES",
                        "DURATION_MINUTES",
                        null,
                        30L,
                        null,
                        null,
                        null)),
                List.of(segment), evidence);
    }

    private static CommandEvidence evidence(UUID receiptId, UUID correlationId) {
        return new CommandEvidence(receiptId, correlationId, AUTHOR, PURPOSE, DECISION);
    }

    private void assertEvidence(UUID receiptId, String eventType, String state) {
        assertThat(tenantValue(() -> runtime.queryForObject("""
                SELECT count(*)
                  FROM tim_work_regime_audit_events
                 WHERE tenant_id = ?
                   AND aggregate_public_id = ?
                   AND receipt_public_id = ?
                   AND event_type = ?
                   AND actor_id = ?
                   AND purpose_code = ?
                   AND authorization_decision_id = ?
                """, Long.class,
                TENANT, WORK_PLAN_ID, receiptId, eventType, AUTHOR, PURPOSE, DECISION)))
                .isOne();
        assertThat(tenantValue(() -> runtime.queryForObject("""
                SELECT count(*)
                  FROM tim_work_regime_outbox_events
                 WHERE tenant_id = ?
                   AND aggregate_public_id = ?
                   AND command_receipt_public_id = ?
                   AND event_type = ?
                   AND event_payload ->> 'state' = ?
                   AND event_payload ->> 'receiptId' = ?
                """, Long.class,
                TENANT, WORK_PLAN_ID, receiptId, eventType, state, receiptId.toString())))
                .isOne();
    }

    private void assertAuditIsImmutable(UUID receiptId) {
        assertThatThrownBy(() -> adminTenantRun(() -> admin.update("""
                UPDATE tim_work_regime_audit_events
                   SET event_type = 'MUTATED'
                 WHERE tenant_id = ? AND receipt_public_id = ?
                """, TENANT, receiptId)))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseMessage("ERROR: immutable TIM evidence cannot be updated or deleted\n"
                        + "  Where: PL/pgSQL function tim_reject_immutable_row_mutation() line 3 "
                        + "at RAISE");
    }

    private void seedPublishedRulePack() {
        adminTenantRun(() -> {
            admin.update("""
                    INSERT INTO tim_rule_pack_versions (
                        public_id, tenant_id, pack_key, jurisdiction_country,
                        jurisdiction_subdivision, policy_revision, lifecycle_state,
                        effective_from, effective_to, schema_version, schema_digest,
                        signature_digest, mandatory_bound_digest, signature_verified,
                        review_status, source_reference, created_by
                    ) VALUES (?, ?, 'KR-OWNER-WAVE1', 'KR', '', 1, 'STAGED', ?, ?,
                              1, ?, ?, ?, true, 'VERIFIED', 'owner-it-fixture', ?)
                    """,
                    RULE_PACK_ID, TENANT, PERIOD.from(), PERIOD.to(),
                    DIGEST_A, DIGEST_B, DIGEST_C, AUTHOR);
            assertThat(admin.update("""
                    INSERT INTO tim_rule_pack_parameters (
                        tenant_id, rule_pack_version_id, parameter_name, value_type,
                        mandatory, integer_value, created_by
                    ) SELECT tenant_id, rule_pack_version_id, 'MIN_BREAK_MINUTES',
                             'DURATION_MINUTES', true, 30, ?
                        FROM tim_rule_pack_versions
                       WHERE tenant_id = ? AND public_id = ?
                    """, AUTHOR, TENANT, RULE_PACK_ID)).isOne();
            assertThat(admin.update("""
                    UPDATE tim_rule_pack_versions
                       SET lifecycle_state = 'VALIDATED', version = 2
                     WHERE tenant_id = ? AND public_id = ?
                    """, TENANT, RULE_PACK_ID)).isOne();
            assertThat(admin.update("""
                    UPDATE tim_rule_pack_versions
                       SET lifecycle_state = 'PUBLISHED', version = 3
                     WHERE tenant_id = ? AND public_id = ?
                    """, TENANT, RULE_PACK_ID)).isOne();
        });
        assertThat(tenantValue(() -> runtime.queryForObject("""
                SELECT count(*)
                  FROM tim_rule_pack_versions
                 WHERE tenant_id = ?
                   AND public_id = ?
                   AND jurisdiction_country = 'KR'
                   AND jurisdiction_subdivision = ''
                   AND policy_revision = 1
                   AND lifecycle_state = 'PUBLISHED'
                   AND effective_from = ?
                   AND effective_to = ?
                """, Long.class,
                TENANT, RULE_PACK_ID, PERIOD.from(), PERIOD.to()))).isOne();
    }

    private <T> T tenantValue(Supplier<T> work) {
        return runtimeTransactions.execute(status -> {
            bindTenant(runtime);
            return work.get();
        });
    }

    private void adminTenantRun(Runnable work) {
        adminTransactions.executeWithoutResult(status -> {
            bindTenant(admin);
            work.run();
        });
    }

    private static void bindTenant(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', ?, true)",
                String.class, Long.toString(TENANT))).isEqualTo(Long.toString(TENANT));
    }

    private static TransactionTemplate transactions(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private PGSimpleDataSource dataSource(String user, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }
}
