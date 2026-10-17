package com.dwp.services.time.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
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
class TimeCloseMigrationIntegrationTest {

    private static final String RUNTIME_ROLE = "tim_close_runtime";
    private static final String RUNTIME_PASSWORD = "tim-close-runtime-test";
    private static final String PUBLISHER_ROLE = "tim_close_publisher";
    private static final String PUBLISHER_PASSWORD = "tim-close-publisher-test";
    private static final String MIGRATION_ROLE = "tim_close_migrator";
    private static final String MIGRATION_PASSWORD = "tim-close-migrator-test";
    private static final long TENANT = 70_071L;
    private static final UUID PERIOD_ID =
            UUID.fromString("70071000-0000-4000-8000-000000000001");

    private PostgreSQLContainer<?> postgres;
    private DataSource superuserDataSource;
    private JdbcTemplate superuser;
    private DataSource migrationDataSource;
    private DataSource runtimeDataSource;
    private JdbcTemplate runtime;
    private Flyway cleanFlyway;

    @BeforeAll
    void migrateCleanDatabaseToV12() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker is required for the TIM-007 clean and V11-upgrade gate")
                .isTrue();
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        superuserDataSource = dataSource(
                postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword());
        superuser = new JdbcTemplate(superuserDataSource);
        createRole(RUNTIME_ROLE, RUNTIME_PASSWORD);
        createRole(PUBLISHER_ROLE, PUBLISHER_PASSWORD);
        createRole(MIGRATION_ROLE, MIGRATION_PASSWORD);
        superuser.execute("ALTER DATABASE " + postgres.getDatabaseName()
                + " OWNER TO " + MIGRATION_ROLE);
        superuser.execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);

        migrationDataSource = dataSource(
                postgres.getDatabaseName(), MIGRATION_ROLE, MIGRATION_PASSWORD);
        cleanFlyway = flyway(migrationDataSource, null);
        assertThat(cleanFlyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(12);
        runtimeDataSource = dataSource(
                postgres.getDatabaseName(), RUNTIME_ROLE, RUNTIME_PASSWORD);
        runtime = new JdbcTemplate(runtimeDataSource);
    }

    @AfterAll
    void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void cleanToV12IsRepeatableForcedRlsAndExactlyLeased() {
        assertThat(cleanFlyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(cleanFlyway.migrate().migrationsExecuted).isZero();
        assertThat(superuser.queryForObject("""
                SELECT count(*)
                  FROM flyway_schema_history
                 WHERE success AND version='12'
                   AND script='V12__tim_base_tfr_tim_007_close_handoff.sql'
                """, Integer.class)).isOne();
        assertThat(superuser.queryForObject("""
                SELECT count(*)
                  FROM pg_class
                 WHERE relnamespace='public'::regnamespace
                   AND relname IN (
                       'tme_close_periods',
                       'tme_close_command_receipts',
                       'tme_close_attempts',
                       'tme_closed_time_results',
                       'tme_closed_time_result_lines',
                       'tme_closed_time_result_sources',
                       'tme_payroll_handoffs',
                       'tme_payroll_handoff_outcomes',
                       'tme_close_workload_replay',
                       'tme_close_quarantine',
                       'tme_close_outbox_events')
                   AND relkind='r' AND relrowsecurity AND relforcerowsecurity
                """, Integer.class)).isEqualTo(11);
        assertThat(superuser.queryForObject("""
                SELECT count(*)
                  FROM pg_class
                 WHERE relnamespace='public'::regnamespace
                   AND relname LIKE 'tme_close%'
                   AND relkind='r'
                   AND pg_get_userbyid(relowner) <> ?
                """, Integer.class, MIGRATION_ROLE)).isZero();
    }

    @Test
    void onlyMigrationOwnerCanProvisionCanonicalPeriodsAndRuntimeIsDenied() {
        assertThat(superuser.queryForMap("""
                SELECT rolsuper, rolbypassrls
                  FROM pg_roles WHERE rolname = ?
                """, MIGRATION_ROLE))
                .containsEntry("rolsuper", false)
                .containsEntry("rolbypassrls", false);
        assertThat(superuser.queryForObject("""
                SELECT has_table_privilege(?, 'public.tme_close_periods', 'SELECT')
                   AND NOT has_table_privilege(
                       ?, 'public.tme_close_periods', 'INSERT')
                   AND NOT has_table_privilege(
                       ?, 'public.tme_close_periods', 'UPDATE')
                   AND NOT has_table_privilege(
                       ?, 'public.tme_close_periods', 'DELETE')
                   AND NOT has_sequence_privilege(
                       ?, 'public.tme_close_periods_close_period_id_seq', 'USAGE')
                   AND NOT has_sequence_privilege(
                       ?, 'public.tme_close_periods_close_period_id_seq', 'SELECT')
                """, Boolean.class,
                RUNTIME_ROLE, RUNTIME_ROLE, RUNTIME_ROLE, RUNTIME_ROLE,
                RUNTIME_ROLE, RUNTIME_ROLE)).isTrue();
        assertThat(superuser.queryForList("""
                SELECT column_name
                  FROM information_schema.role_column_grants
                 WHERE grantee=? AND table_schema='public'
                   AND table_name='tme_close_periods'
                   AND privilege_type='UPDATE'
                 ORDER BY column_name
                """, String.class, RUNTIME_ROLE)).containsExactly(
                        "close_approval_actor_public_id",
                        "close_revision",
                        "closed_at",
                        "closed_by_subject_public_id",
                        "latest_correction_maker_subject_public_id",
                        "latest_result_public_id",
                        "ledger_revision",
                        "row_version",
                        "status",
                        "updated_at",
                        "updated_by");

        insertCanonicalFixture(migrationDataSource, TENANT, PERIOD_ID);
        assertThat(tenantQuery(runtimeDataSource, TENANT, () -> runtime.queryForObject("""
                SELECT count(*) FROM tme_close_periods
                 WHERE tenant_id=? AND public_id=? AND status='OPEN'
                   AND close_revision=0 AND row_version=1
                """, Integer.class, TENANT, PERIOD_ID))).isOne();

        assertThatThrownBy(() -> tenantQuery(runtimeDataSource, TENANT, () ->
                runtime.update("""
                        INSERT INTO tme_close_periods (
                            close_period_id, public_id, tenant_id, close_scope,
                            legal_entity_public_id, time_group_public_id,
                            payroll_entity_public_id, pay_period_public_id,
                            period_start, period_end, status, created_at, created_by,
                            updated_at, updated_by)
                        VALUES (9000001, ?, ?, 'DAY', ?, ?, ?, ?, ?, ?, 'OPEN',
                                CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 1)
                        """,
                        UUID.fromString("70071000-0000-4000-8000-000000000099"), TENANT,
                        UUID.fromString("70071000-0000-4000-8000-000000000010"),
                        UUID.fromString("70071000-0000-4000-8000-000000000011"),
                        UUID.fromString("70071000-0000-4000-8000-000000000012"),
                        UUID.fromString("70071000-0000-4000-8000-000000000013"),
                        LocalDate.parse("2026-10-17"), LocalDate.parse("2026-10-17"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runtime.queryForObject(
                "SELECT nextval('public.tme_close_periods_close_period_id_seq')",
                Long.class)).isInstanceOf(DataAccessException.class);
        assertThat(tenantQuery(migrationDataSource, TENANT, () ->
                new JdbcTemplate(migrationDataSource).queryForObject("""
                        SELECT count(*) FROM tme_close_periods WHERE tenant_id=?
                        """, Integer.class, TENANT))).isOne();
    }

    @Test
    void runtimeCanTakeEvidenceFenceLocksWithoutPopulationWriteAuthority() {
        long tenantId = TENANT + 2;
        UUID populationId =
                UUID.fromString("70073000-0000-4000-8000-000000000001");
        UUID workerId =
                UUID.fromString("70073000-0000-4000-8000-000000000002");
        UUID assignmentId =
                UUID.fromString("70073000-0000-4000-8000-000000000003");
        UUID timecardId =
                UUID.fromString("70073000-0000-4000-8000-000000000004");
        UUID decisionActor =
                UUID.fromString("70073000-0000-4000-8000-000000000005");
        String gatewayScope = "hcm-scope-" + "7".repeat(40);
        JdbcTemplate migration = new JdbcTemplate(migrationDataSource);
        tenantQuery(migrationDataSource, tenantId, () -> {
            migration.update("""
                    INSERT INTO tim_target_population_projections (
                        tenant_id, population_public_id, scope_public_ref,
                        projection_revision, lifecycle_state, effective_from,
                        effective_to, source_digest, updated_at, updated_by)
                    VALUES (?, ?, ?, 9, 'ACTIVE', ?, NULL, ?, ?, 73)
                    """, tenantId, populationId, "population:" + populationId,
                    LocalDate.parse("2026-01-01"), "a".repeat(64),
                    Timestamp.from(Instant.parse("2026-10-17T00:00:00Z")));
            migration.update("""
                    INSERT INTO tim_target_population_actor_grants (
                        tenant_id, actor_id, gateway_scope_key, population_public_id,
                        population_revision, grant_revision, lifecycle_state,
                        valid_from, valid_to, source_digest, updated_at, updated_by)
                    VALUES (?, 73, ?, ?, 9, 11, 'ACTIVE', ?, NULL, ?, ?, 73)
                    """, tenantId, gatewayScope, populationId,
                    Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                    "c".repeat(64),
                    Timestamp.from(Instant.parse("2026-10-17T00:00:00Z")));
            migration.update("""
                    INSERT INTO tim_target_population_members (
                        tenant_id, population_public_id, population_revision,
                        worker_public_id, people_assignment_public_id,
                        people_assignment_revision, membership_revision,
                        lifecycle_state, effective_from, effective_to,
                        source_digest, updated_at, updated_by)
                    VALUES (?, ?, 9, ?, ?, 4, 5, 'ACTIVE', ?, NULL, ?, ?, 73)
                    """, tenantId, populationId, workerId, assignmentId,
                    LocalDate.parse("2026-01-01"), "b".repeat(64),
                    Timestamp.from(Instant.parse("2026-10-17T00:00:00Z")));
            migration.update("""
                    INSERT INTO tme_time_ledger_revision_counters (
                        tenant_id, worker_public_id, current_revision)
                    VALUES (?, ?, 17)
                    """, tenantId, workerId);
            migration.update("""
                    INSERT INTO tme_timecards (
                        public_id, tenant_id, worker_public_id,
                        period_start, period_end, state, total_minutes,
                        ledger_revision, period_closed, row_version,
                        created_at, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, 'OPEN', 480, 17, FALSE, 0,
                            ?, ?, 73)
                    """, timecardId, tenantId, workerId,
                    LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"),
                    Timestamp.from(Instant.parse("2026-10-17T00:00:00Z")),
                    Timestamp.from(Instant.parse("2026-10-17T00:00:00Z")));
            migration.update("""
                    UPDATE tme_timecards
                       SET state='SUBMITTED', submitted_by_subject_public_id=?,
                           row_version=1, updated_at=?, updated_by=73
                     WHERE tenant_id=? AND public_id=?
                    """, decisionActor,
                    Timestamp.from(Instant.parse("2026-10-17T00:01:00Z")),
                    tenantId, timecardId);
            migration.update("""
                    UPDATE tme_timecards
                       SET state='APPROVED', decided_by_subject_public_id=?,
                           decided_at=?, row_version=2, updated_at=?, updated_by=73
                     WHERE tenant_id=? AND public_id=?
                    """, decisionActor,
                    Timestamp.from(Instant.parse("2026-10-17T00:02:00Z")),
                    Timestamp.from(Instant.parse("2026-10-17T00:02:00Z")),
                    tenantId, timecardId);
            return null;
        });

        assertThat(tenantQuery(runtimeDataSource, tenantId, () -> {
            long projectionRevision = runtime.queryForObject("""
                    SELECT projection_revision
                      FROM tim_target_population_projections
                     WHERE tenant_id=? AND population_public_id=?
                     FOR UPDATE
                    """, Long.class, tenantId, populationId);
            long grantRevision = runtime.queryForObject("""
                    SELECT grant_revision
                      FROM tim_target_population_actor_grants
                     WHERE tenant_id=? AND actor_id=73 AND gateway_scope_key=?
                     FOR UPDATE
                    """, Long.class, tenantId, gatewayScope);
            int memberCount = runtime.queryForList("""
                    SELECT worker_public_id
                      FROM tim_target_population_members
                     WHERE tenant_id=? AND population_public_id=?
                     FOR UPDATE
                    """, UUID.class, tenantId, populationId).size();
            long ledgerRevision = runtime.queryForObject("""
                    SELECT current_revision
                      FROM tme_time_ledger_revision_counters
                     WHERE tenant_id=? AND worker_public_id=?
                     FOR UPDATE
                    """, Long.class, tenantId, workerId);
            long timecardLedgerRevision = runtime.queryForObject("""
                    SELECT ledger_revision
                      FROM tme_timecards
                     WHERE tenant_id=? AND worker_public_id=?
                       AND period_start=? AND period_end=?
                       AND state <> 'SUPERSEDED'
                       AND state IN ('APPROVED', 'LOCKED', 'CLOSED')
                     FOR UPDATE
                    """, Long.class, tenantId, workerId,
                    LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));
            return List.of(
                    projectionRevision, grantRevision, (long) memberCount,
                    ledgerRevision, timecardLedgerRevision);
        })).containsExactly(9L, 11L, 1L, 17L, 17L);

        assertThat(superuser.queryForObject("""
                SELECT has_column_privilege(
                           ?, 'public.tim_target_population_projections',
                           'updated_at', 'UPDATE')
                   AND has_column_privilege(
                           ?, 'public.tim_target_population_actor_grants',
                           'updated_at', 'UPDATE')
                   AND has_column_privilege(
                           ?, 'public.tim_target_population_members',
                           'updated_at', 'UPDATE')
                   AND NOT has_table_privilege(
                           ?, 'public.tim_target_population_projections', 'UPDATE')
                   AND NOT has_table_privilege(
                           ?, 'public.tim_target_population_actor_grants', 'UPDATE')
                   AND NOT has_table_privilege(
                           ?, 'public.tim_target_population_members', 'UPDATE')
                """, Boolean.class,
                RUNTIME_ROLE, RUNTIME_ROLE, RUNTIME_ROLE,
                RUNTIME_ROLE, RUNTIME_ROLE, RUNTIME_ROLE)).isTrue();
    }

    @Test
    void publisherHasOnlyExactCloseOutboxRelayPrivileges() {
        assertThat(superuser.queryForList("""
                SELECT table_name || ':' || privilege_type
                  FROM information_schema.role_table_grants
                 WHERE grantee=? AND table_schema='public'
                   AND table_name LIKE 'tme_close%'
                 ORDER BY table_name, privilege_type
                """, String.class, PUBLISHER_ROLE))
                .containsExactly("tme_close_outbox_events:SELECT");
        assertThat(superuser.queryForList("""
                SELECT column_name || ':' || privilege_type
                  FROM information_schema.role_column_grants
                 WHERE grantee=? AND table_schema='public'
                   AND table_name='tme_close_outbox_events'
                   AND privilege_type='UPDATE'
                 ORDER BY column_name
                """, String.class, PUBLISHER_ROLE))
                .containsExactly("publish_attempts:UPDATE", "published_at:UPDATE");
        assertThat(superuser.queryForObject("""
                SELECT has_table_privilege(
                           ?, 'public.tme_close_outbox_events', 'SELECT')
                   AND NOT has_table_privilege(
                           ?, 'public.tme_close_outbox_events', 'INSERT')
                   AND NOT has_table_privilege(
                           ?, 'public.tme_close_outbox_events', 'DELETE')
                   AND NOT has_table_privilege(
                           ?, 'public.tme_close_outbox_events', 'UPDATE')
                """, Boolean.class,
                PUBLISHER_ROLE, PUBLISHER_ROLE, PUBLISHER_ROLE, PUBLISHER_ROLE))
                .isTrue();
    }

    @Test
    void seededV11UpgradePreservesExistingFactsAndAppliesOnlyV12() {
        String database = "tim007_v11_upgrade";
        superuser.execute("CREATE DATABASE " + database + " OWNER " + MIGRATION_ROLE);
        DataSource upgradeSuperuserDataSource = dataSource(
                database, postgres.getUsername(), postgres.getPassword());
        new JdbcTemplate(upgradeSuperuserDataSource)
                .execute("ALTER SCHEMA public OWNER TO " + MIGRATION_ROLE);
        DataSource upgradeMigrationDataSource = dataSource(
                database, MIGRATION_ROLE, MIGRATION_PASSWORD);
        Flyway v11 = flyway(
                upgradeMigrationDataSource, MigrationVersion.fromVersion("11"));
        assertThat(v11.migrate().migrationsExecuted).isGreaterThanOrEqualTo(11);

        JdbcTemplate upgrade = new JdbcTemplate(upgradeMigrationDataSource);
        List<String> historyBefore = history(upgrade);
        UUID populationId =
                UUID.fromString("70072000-0000-4000-8000-000000000001");
        tenantQuery(upgradeMigrationDataSource, TENANT + 1, () -> upgrade.update("""
                INSERT INTO tim_target_population_projections (
                    tenant_id, population_public_id, scope_public_ref,
                    projection_revision, lifecycle_state, effective_from,
                    effective_to, source_digest, updated_at, updated_by)
                VALUES (?, ?, ?, 7, 'ACTIVE', ?, NULL, ?, ?, 71)
                """, TENANT + 1, populationId, "population:" + populationId,
                LocalDate.parse("2026-01-01"), "a".repeat(64),
                Timestamp.from(Instant.parse("2026-10-17T00:00:00Z"))));
        Map<String, Object> factBefore = tenantQuery(
                upgradeMigrationDataSource, TENANT + 1, () -> upgrade.queryForMap("""
                        SELECT population_public_id, projection_revision, lifecycle_state,
                               effective_from, effective_to, source_digest, updated_by
                          FROM tim_target_population_projections
                         WHERE tenant_id=? AND population_public_id=?
                        """, TENANT + 1, populationId));

        Flyway upgradeFlyway = flyway(upgradeMigrationDataSource, null);
        assertThat(upgradeFlyway.migrate().migrationsExecuted).isOne();
        assertThat(upgradeFlyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(upgradeFlyway.migrate().migrationsExecuted).isZero();
        assertThat(history(upgrade).subList(0, historyBefore.size()))
                .containsExactlyElementsOf(historyBefore);
        assertThat(upgrade.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                 WHERE success AND version='12'
                   AND script='V12__tim_base_tfr_tim_007_close_handoff.sql'
                """, Integer.class)).isOne();
        assertThat(tenantQuery(upgradeMigrationDataSource, TENANT + 1,
                () -> upgrade.queryForMap("""
                        SELECT population_public_id, projection_revision, lifecycle_state,
                               effective_from, effective_to, source_digest, updated_by
                          FROM tim_target_population_projections
                         WHERE tenant_id=? AND population_public_id=?
                        """, TENANT + 1, populationId))).isEqualTo(factBefore);
    }

    private void createRole(String role, String password) {
        superuser.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
    }

    private void insertCanonicalFixture(DataSource source, long tenantId, UUID periodId) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        tenantQuery(source, tenantId, () -> jdbc.update("""
                INSERT INTO tme_close_periods (
                    public_id, tenant_id, close_scope, legal_entity_public_id,
                    time_group_public_id, payroll_entity_public_id,
                    pay_period_public_id, period_start, period_end, status,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, 'DAY', ?, ?, ?, ?, ?, ?, 'OPEN',
                        CURRENT_TIMESTAMP, 71, CURRENT_TIMESTAMP, 71)
                """, periodId, tenantId,
                UUID.fromString("70071000-0000-4000-8000-000000000010"),
                UUID.fromString("70071000-0000-4000-8000-000000000011"),
                UUID.fromString("70071000-0000-4000-8000-000000000012"),
                UUID.fromString("70071000-0000-4000-8000-000000000013"),
                LocalDate.parse("2026-10-17"), LocalDate.parse("2026-10-17")));
    }

    private List<String> history(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT installed_rank || ':' || version || ':' || script || ':' || checksum
                  FROM flyway_schema_history
                 WHERE success AND version IS NOT NULL
                 ORDER BY installed_rank
                """, String.class);
    }

    private <T> T tenantQuery(DataSource source, long tenantId, Supplier<T> work) {
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(source));
        return transaction.execute(ignored -> {
            String expected = Long.toString(tenantId);
            String actual = new JdbcTemplate(source).queryForObject(
                    "SELECT set_config('dwp.tenant_id', ?, true)",
                    String.class, expected);
            assertThat(actual).isEqualTo(expected);
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
}
