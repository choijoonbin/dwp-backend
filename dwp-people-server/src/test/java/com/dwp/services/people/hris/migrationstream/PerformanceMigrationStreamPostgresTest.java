package com.dwp.services.people.hris.migrationstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PerformanceMigrationStreamPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    @BeforeEach
    void resetIsolatedStream() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("DROP SCHEMA IF EXISTS " + PerformanceMigrationStreamProperties.SCHEMA
                + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + PerformanceMigrationStreamProperties.SCHEMA);
    }

    @Test
    void bootstrapsAndRevalidatesSeparatePeopleAndPerformanceHistories() {
        DataSource dataSource = dataSource();
        Flyway people = peopleFlyway(dataSource);
        PerformanceMigrationStreamBootstrap bootstrap =
                new PerformanceMigrationStreamBootstrap(
                        performanceProperties(), getClass().getClassLoader());

        bootstrap.migrate(people);
        bootstrap.migrate(people);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(tableCount(jdbc, "public", "flyway_schema_history")).isEqualTo(1);
        assertThat(tableCount(
                jdbc,
                PerformanceMigrationStreamProperties.SCHEMA,
                PerformanceMigrationStreamProperties.HISTORY_TABLE)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.flyway_schema_history WHERE success",
                Integer.class)).isPositive();
    }

    @Test
    void rejectsFutureAppliedPerformanceMigrationThenRetriesCleanlyAfterRepair() {
        DataSource dataSource = dataSource();
        Flyway people = peopleFlyway(dataSource);
        PerformanceMigrationStreamBootstrap bootstrap =
                new PerformanceMigrationStreamBootstrap(
                        performanceProperties(), getClass().getClassLoader());
        bootstrap.migrate(people);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO hris_performance.flyway_performance_schema_history (
                    installed_rank,
                    version,
                    description,
                    type,
                    script,
                    checksum,
                    installed_by,
                    execution_time,
                    success
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_USER, ?, ?)
                """, 999, "999", "future migration sentinel", "SQL",
                "V999__future_migration_sentinel.sql", null, 0, true);

        assertThatThrownBy(() -> bootstrap.migrate(people))
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("not resolved locally");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.flyway_performance_schema_history
                 WHERE version = '999'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM public.flyway_schema_history
                 WHERE success
                """, Integer.class)).isPositive();

        jdbc.update("""
                DELETE FROM hris_performance.flyway_performance_schema_history
                 WHERE version = '999'
                """);
        bootstrap.migrate(people);
        bootstrap.migrate(people);

        assertThat(tableCount(
                jdbc,
                PerformanceMigrationStreamProperties.SCHEMA,
                PerformanceMigrationStreamProperties.HISTORY_TABLE)).isEqualTo(1);
    }

    @Test
    void rejectsMissingOrWrongOwnerPerformanceSchemaBeforeFlywayRuns() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("DROP SCHEMA " + PerformanceMigrationStreamProperties.SCHEMA + " CASCADE");
        PerformanceMigrationStreamBootstrap missing = new PerformanceMigrationStreamBootstrap(
                performanceProperties(), getClass().getClassLoader());
        assertThatThrownBy(() -> missing.migrate(peopleFlyway(dataSource())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be preprovisioned and owned");

        jdbc.execute("CREATE ROLE performance_wrong_owner");
        jdbc.execute("CREATE SCHEMA " + PerformanceMigrationStreamProperties.SCHEMA
                + " AUTHORIZATION performance_wrong_owner");
        PerformanceMigrationStreamBootstrap wrongOwner =
                new PerformanceMigrationStreamBootstrap(
                        performanceProperties(), getClass().getClassLoader());
        assertThatThrownBy(() -> wrongOwner.migrate(peopleFlyway(dataSource())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be preprovisioned and owned");
        jdbc.execute("DROP SCHEMA " + PerformanceMigrationStreamProperties.SCHEMA + " CASCADE");
        jdbc.execute("DROP ROLE performance_wrong_owner");
    }

    private static Flyway peopleFlyway(DataSource dataSource) {
        return Flyway.configure(PerformanceMigrationStreamPostgresTest.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema("public")
                .schemas("public")
                .table("flyway_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load();
    }

    private static PerformanceMigrationStreamProperties performanceProperties() {
        return new PerformanceMigrationStreamProperties(
                PerformanceMigrationStreamProperties.LOCATION,
                PerformanceMigrationStreamProperties.SCHEMA,
                PerformanceMigrationStreamProperties.HISTORY_TABLE,
                PerformanceMigrationStreamProperties.BASELINE_VERSION,
                false,
                true,
                false);
    }

    private static int tableCount(JdbcTemplate jdbc, String schema, String table) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = ?
                   AND table_name = ?
                """, Integer.class, schema, table);
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
