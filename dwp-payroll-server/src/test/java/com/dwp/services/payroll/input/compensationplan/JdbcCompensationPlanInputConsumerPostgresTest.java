package com.dwp.services.payroll.input.compensationplan;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputContracts.Outcome.APPLIED;
import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputContracts.Outcome.QUARANTINED;
import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputContracts.Outcome.REPLAYED;
import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputTestFixtures.NOW;
import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputTestFixtures.SNAPSHOT_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcCompensationPlanInputConsumerPostgresTest {

    private static final String RUNTIME_USER = "pay016_runtime";
    private static final String RUNTIME_PASSWORD = "pay016-runtime-password";
    private static final String PUBLISHER_USER = "pay016_projection_publisher";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource adminDataSource;
    private static DataSource runtimeDataSource;
    private static JdbcTemplate adminJdbc;
    private ObjectMapper mapper;
    private CompensationPlanInputCanonicalizer canonicalizer;
    private JdbcCompensationPlanInputRepository repository;
    private AtomicReference<Xcon020ApprovedCompensationPlanSnapshotV1> ownerSnapshot;
    private CompensationPlanInputConsumer consumer;

    @BeforeAll
    static void migrateThroughV49() throws Exception {
        adminDataSource = dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME_USER
                    + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("CREATE ROLE " + PUBLISHER_USER
                    + " LOGIN NOINHERIT PASSWORD 'publisher-test-password'");
        }
        Flyway.configure()
                .dataSource(adminDataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "payrollRuntimeRole", RUNTIME_USER,
                        "payrollProjectionPublisherRole", PUBLISHER_USER))
                .load()
                .migrate();
        runtimeDataSource = dataSource(
                POSTGRES.getJdbcUrl(), RUNTIME_USER, RUNTIME_PASSWORD);
        adminJdbc = new JdbcTemplate(adminDataSource);
    }

    @AfterAll
    static void releaseReferences() {
        adminJdbc = null;
        runtimeDataSource = null;
        adminDataSource = null;
    }

    @BeforeEach
    void reset() {
        adminJdbc.execute("""
                TRUNCATE TABLE
                    pay_compensation_plan_input_replay_audit,
                    pay_compensation_plan_input_outbox_events,
                    pay_compensation_plan_input_receipts,
                    pay_compensation_plan_inputs,
                    pay_compensation_plan_event_attempts,
                    pay_compensation_plan_event_inbox
                RESTART IDENTITY CASCADE
                """);
        mapper = JsonMapper.builder().findAndAddModules().build();
        canonicalizer = new CompensationPlanInputCanonicalizer(mapper);
        repository = new JdbcCompensationPlanInputRepository(
                new NamedParameterJdbcTemplate(runtimeDataSource), mapper);
        ownerSnapshot = new AtomicReference<>();
        CompensationPlanSnapshotOwnerPort owner = request -> {
            Xcon020ApprovedCompensationPlanSnapshotV1 value = ownerSnapshot.get();
            if (value == null) {
                throw new CompensationPlanSnapshotOwnerPort
                        .SnapshotRejectedException("Owner rejected the exact snapshot.");
            }
            return value;
        };
        PlatformTransactionManager transactions =
                new DataSourceTransactionManager(runtimeDataSource);
        consumer = new CompensationPlanInputConsumer(
                repository, owner, canonicalizer, transactions,
                Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC),
                "dwp-payroll-server:test");
    }

    @Test
    void cleanV49ForcesRlsAppendOnlyRuntimeAndLeavesFeatureOutOfCalculation() {
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM flyway_schema_history
                 WHERE version = '49' AND success
                """, Long.class)).isEqualTo(1L);
        assertThat(adminJdbc.queryForObject("""
                SELECT bool_and(relrowsecurity AND relforcerowsecurity)
                  FROM pg_class
                 WHERE relname IN (
                    'pay_compensation_plan_event_inbox',
                    'pay_compensation_plan_event_attempts',
                    'pay_compensation_plan_inputs',
                    'pay_compensation_plan_input_receipts',
                    'pay_compensation_plan_input_outbox_events',
                    'pay_compensation_plan_input_replay_audit')
                """, Boolean.class)).isTrue();

        JdbcTemplate runtime = new JdbcTemplate(runtimeDataSource);
        assertThat(runtime.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_inputs", Long.class))
                .isZero();
        for (String privilege : Set.of("UPDATE", "DELETE", "TRUNCATE")) {
            assertThat(runtime.queryForObject(
                    "SELECT has_table_privilege(current_user, "
                            + "'pay_compensation_plan_inputs', ?)",
                    Boolean.class, privilege)).isFalse();
        }
        assertThatThrownBy(() -> runtime.update(
                "UPDATE pay_compensation_plan_inputs SET currency = 'USD'"))
                .isInstanceOf(DataAccessException.class);
        assertThat(adminJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name LIKE 'payroll_run%'
                """, Long.class)).isZero();
    }

    @Test
    void materializesExactlyOnceAndIsolatesTenants() {
        var fixture = CompensationPlanInputTestFixtures.fixture(
                mapper, canonicalizer, 41, UUID.randomUUID(), SNAPSHOT_1,
                1, 1, 2, UUID.randomUUID(), new BigDecimal("1200000"));
        ownerSnapshot.set(fixture.snapshot());

        var applied = consumer.consume(fixture.envelope());
        var replayed = consumer.consume(fixture.envelope());

        assertThat(applied.outcome()).isEqualTo(APPLIED);
        assertThat(replayed.outcome()).isEqualTo(REPLAYED);
        assertThat(replayed.receiptId()).isEqualTo(applied.receiptId());
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_inputs", Long.class))
                .isEqualTo(1L);
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_input_receipts", Long.class))
                .isEqualTo(1L);
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_input_outbox_events", Long.class))
                .isEqualTo(1L);
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_event_attempts", Long.class))
                .isEqualTo(1L);

        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(runtimeDataSource));
        Long tenant41 = transaction.execute(status -> {
            repository.receipt(41, fixture.envelope().id());
            return new JdbcTemplate(runtimeDataSource).queryForObject(
                    "SELECT COUNT(*) FROM pay_compensation_plan_inputs", Long.class);
        });
        Long tenant42 = transaction.execute(status -> {
            repository.receipt(42, fixture.envelope().id());
            return new JdbcTemplate(runtimeDataSource).queryForObject(
                    "SELECT COUNT(*) FROM pay_compensation_plan_inputs", Long.class);
        });
        assertThat(tenant41).isEqualTo(1L);
        assertThat(tenant42).isZero();
    }

    @Test
    void fencesReusedAppliedEventIdAndDoesNotPermitReplayOfItsOriginalReceipt() {
        UUID eventId = UUID.randomUUID();
        var original = CompensationPlanInputTestFixtures.fixture(
                mapper, canonicalizer, 41, eventId, SNAPSHOT_1,
                1, 1, 2, UUID.randomUUID(), new BigDecimal("1200000"));
        ownerSnapshot.set(original.snapshot());
        assertThat(consumer.consume(original.envelope()).outcome()).isEqualTo(APPLIED);

        var hostileReuse = CompensationPlanInputTestFixtures.fixture(
                mapper, canonicalizer, 41, eventId, UUID.randomUUID(),
                2, 3, 4, UUID.randomUUID(), new BigDecimal("1300000"));
        ownerSnapshot.set(hostileReuse.snapshot());

        var result = consumer.consume(hostileReuse.envelope());

        assertThat(result.outcome()).isEqualTo(QUARANTINED);
        assertThat(result.failureCode()).isEqualTo("EVENT_ID_REUSED");
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_input_receipts", Long.class))
                .isEqualTo(1L);
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_event_attempts", Long.class))
                .isEqualTo(2L);
        assertThatThrownBy(() -> consumer.replay(
                41, eventId,
                new CompensationPlanInputContracts.ReplayAuthority(
                        "TENANT_ADMIN", "admin-41",
                        Set.of(CompensationPlanInputContracts.REPLAY_AUTHORITY),
                        "Investigate event ID reuse.")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already applied");
    }

    @Test
    void quarantinesMismatchAndOnlyAuditedExactAdminReplayCanApply() {
        var first = CompensationPlanInputTestFixtures.fixture(
                mapper, canonicalizer, 41, UUID.randomUUID(), SNAPSHOT_1,
                1, 1, 2, UUID.randomUUID(), new BigDecimal("1200000"));
        ownerSnapshot.set(first.snapshot());
        assertThat(consumer.consume(first.envelope()).outcome()).isEqualTo(APPLIED);

        UUID snapshot2 = UUID.fromString("20000000-0000-4000-8000-000000000017");
        var second = CompensationPlanInputTestFixtures.fixture(
                mapper, canonicalizer, 41, UUID.randomUUID(), snapshot2,
                2, 3, 4, UUID.randomUUID(), new BigDecimal("1300000"));
        ownerSnapshot.set(first.snapshot());
        var quarantined = consumer.consume(second.envelope());

        assertThat(quarantined.outcome()).isEqualTo(QUARANTINED);
        assertThat(quarantined.failureCode()).isEqualTo("SNAPSHOT_CONTRACT_MISMATCH");
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_inputs", Long.class))
                .isEqualTo(1L);
        assertThatThrownBy(() -> consumer.replay(
                41, second.envelope().id(),
                new CompensationPlanInputContracts.ReplayAuthority(
                        "SERVICE", "bad", Set.of(
                        CompensationPlanInputContracts.REPLAY_AUTHORITY), "bad")))
                .isInstanceOf(SecurityException.class);

        ownerSnapshot.set(second.snapshot());
        var replayed = consumer.replay(
                41, second.envelope().id(),
                new CompensationPlanInputContracts.ReplayAuthority(
                        "TENANT_ADMIN", "admin-41",
                        Set.of(CompensationPlanInputContracts.REPLAY_AUTHORITY),
                        "Owner snapshot was repaired and revalidated."));

        assertThat(replayed.outcome()).isEqualTo(APPLIED);
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_inputs", Long.class))
                .isEqualTo(2L);
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_input_receipts", Long.class))
                .isEqualTo(2L);
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_input_replay_audit", Long.class))
                .isEqualTo(1L);
        assertThat(adminJdbc.queryForMap("""
                SELECT actor_type, actor_id, authority, prior_outcome
                  FROM pay_compensation_plan_input_replay_audit
                """)).containsAllEntriesOf(Map.of(
                        "actor_type", "TENANT_ADMIN",
                        "actor_id", "admin-41",
                        "authority", CompensationPlanInputContracts.REPLAY_AUTHORITY,
                        "prior_outcome", "QUARANTINED"));
    }

    @Test
    void rejectsMissingSnapshotRevisionPredecessorWithoutPartialWrites() {
        var first = CompensationPlanInputTestFixtures.fixture(
                mapper, canonicalizer, 41, UUID.randomUUID(), SNAPSHOT_1,
                1, 1, 2, UUID.randomUUID(), new BigDecimal("1200000"));
        ownerSnapshot.set(first.snapshot());
        consumer.consume(first.envelope());

        var gap = CompensationPlanInputTestFixtures.fixture(
                mapper, canonicalizer, 41, UUID.randomUUID(), UUID.randomUUID(),
                3, 5, 6, UUID.randomUUID(), new BigDecimal("1400000"));
        ownerSnapshot.set(gap.snapshot());
        var result = consumer.consume(gap.envelope());

        assertThat(result.outcome()).isEqualTo(QUARANTINED);
        assertThat(result.failureCode())
                .isEqualTo("SNAPSHOT_REVISION_PREDECESSOR_MISSING");
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_inputs", Long.class))
                .isEqualTo(1L);
        assertThat(adminJdbc.queryForObject(
                "SELECT COUNT(*) FROM pay_compensation_plan_input_outbox_events", Long.class))
                .isEqualTo(1L);
    }

    private static DataSource dataSource(
            String url, String username, String password) {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(url, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }
}
