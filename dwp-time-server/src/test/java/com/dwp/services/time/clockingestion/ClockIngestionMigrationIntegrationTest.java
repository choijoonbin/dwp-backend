package com.dwp.services.time.clockingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClockIngestionMigrationIntegrationTest {

    private static final String RUNTIME_ROLE = "tim_clock_runtime";
    private static final String RUNTIME_PASSWORD = "tim-clock-runtime-test";
    private static final String PUBLISHER_ROLE = "tim_clock_publisher";
    private static final String PUBLISHER_PASSWORD = "tim-clock-publisher-test";

    private PostgreSQLContainer<?> postgres;
    private JdbcTemplate admin;
    private Flyway flyway;

    @BeforeAll
    void migrateCleanDatabase() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker is required for the hermetic TIM clock migration gate")
                .isTrue();
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        DataSource adminDataSource = dataSource(postgres.getUsername(), postgres.getPassword());
        admin = new JdbcTemplate(adminDataSource);
        admin.execute("CREATE ROLE " + RUNTIME_ROLE
                + " LOGIN PASSWORD '" + RUNTIME_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        admin.execute("CREATE ROLE " + PUBLISHER_ROLE
                + " LOGIN PASSWORD '" + PUBLISHER_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        flyway = Flyway.configure()
                .dataSource(adminDataSource)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .table("flyway_schema_history")
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .placeholderReplacement(true)
                .placeholders(Map.of(
                        "timeRuntimeRole", RUNTIME_ROLE,
                        "timeProjectionPublisherRole", PUBLISHER_ROLE))
                .load();
        // Flyway may count its schema-creation action in this result; the history assertion below
        // pins the leased version and filename exactly.
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(8);
    }

    @AfterAll
    void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void cleanUpgradeIsRepeatableAndV8IsExactlyLeased() {
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM flyway_schema_history "
                        + "WHERE success AND version='8' "
                        + "AND script='V8__tim_create_clock_ingestion.sql'",
                Integer.class)).isOne();
    }

    @Test
    void clockTablesAreForcedRlsAndRuntimeCannotRewriteEvidence() {
        assertThat(admin.queryForObject("""
                SELECT count(*)
                  FROM pg_class
                 WHERE relnamespace = 'public'::regnamespace
                   AND relname IN (
                       'tme_clock_command_receipts',
                       'tme_clock_events',
                       'tme_clock_outbox_events')
                   AND relkind = 'r'
                   AND relrowsecurity
                   AND relforcerowsecurity
                """, Integer.class)).isEqualTo(3);
        assertThat(admin.queryForObject("""
                SELECT has_table_privilege(?, 'public.tme_clock_events', 'INSERT')
                   AND has_table_privilege(?, 'public.tme_clock_events', 'SELECT')
                   AND NOT has_table_privilege(?, 'public.tme_clock_events', 'UPDATE')
                   AND NOT has_table_privilege(?, 'public.tme_clock_events', 'DELETE')
                   AND NOT has_table_privilege(
                       ?, 'public.tme_clock_command_receipts', 'UPDATE')
                """, Boolean.class,
                RUNTIME_ROLE, RUNTIME_ROLE, RUNTIME_ROLE, RUNTIME_ROLE, RUNTIME_ROLE)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT has_column_privilege(
                           ?, 'public.tme_clock_outbox_events', 'published_at', 'UPDATE')
                   AND has_column_privilege(
                           ?, 'public.tme_clock_outbox_events', 'publish_attempts', 'UPDATE')
                   AND NOT has_table_privilege(
                           ?, 'public.tme_clock_outbox_events', 'INSERT')
                """, Boolean.class,
                PUBLISHER_ROLE, PUBLISHER_ROLE, PUBLISHER_ROLE)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT count(*)
                  FROM pg_proc p
                  JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'public'
                   AND p.proname IN (
                       'tme_guard_clock_source_order',
                       'tme_reject_clock_fact_mutation',
                       'tme_guard_clock_outbox_delivery')
                   AND has_function_privilege(?, p.oid, 'EXECUTE')
                """, Integer.class, RUNTIME_ROLE)).isZero();
    }

    private DataSource dataSource(String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(username);
        source.setPassword(password);
        return source;
    }
}
