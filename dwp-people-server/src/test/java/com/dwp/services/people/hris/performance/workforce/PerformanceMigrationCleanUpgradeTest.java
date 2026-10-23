package com.dwp.services.people.hris.performance.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PerformanceMigrationCleanUpgradeTest {

    private static final String SCHEMA = "hris_performance";
    private static final String HISTORY_TABLE = "flyway_performance_schema_history";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    @Test
    void cleanSchemaMigratesTheFullPerformanceStreamThroughV16() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        com.dwp.services.people.hris.performance.support
                .PerformanceMigrationRuntimeRoleFixture.prepare(dataSource);

        Flyway.configure(getClass().getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/performance-migration")
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load()
                .migrate();

        assertThat(jdbc.queryForList("""
                SELECT version
                  FROM hris_performance.flyway_performance_schema_history
                 WHERE success
                 ORDER BY installed_rank
                """, String.class)).contains("1", "2", "3", "16");

        assertThat(jdbc.queryForList("""
                SELECT tablename
                  FROM pg_catalog.pg_tables
                 WHERE schemaname = 'hris_performance'
                   AND tablename LIKE 'prf_workforce_projection_%'
                 ORDER BY tablename
                """, String.class)).contains(
                "prf_workforce_projection_heads",
                "prf_workforce_projection_inbox",
                "prf_workforce_projection_members",
                "prf_workforce_projection_revisions");

        assertThat(jdbc.queryForList("""
                SELECT constraint_name
                  FROM information_schema.table_constraints
                 WHERE constraint_schema = 'hris_performance'
                   AND table_name LIKE 'prf_workforce_projection_%'
                 ORDER BY constraint_name
                """, String.class)).contains(
                "uk_prf_workforce_projection_heads_scope",
                "ck_prf_workforce_projection_heads_current",
                "ck_prf_workforce_projection_heads_gap",
                "uk_prf_workforce_projection_revisions_sequence",
                "fk_prf_workforce_projection_revisions_head",
                "uk_prf_workforce_projection_members_assignment",
                "fk_prf_workforce_projection_members_revision",
                "uk_prf_workforce_projection_inbox_event",
                "ck_prf_workforce_projection_inbox_event_binding",
                "ck_prf_workforce_projection_inbox_revision_binding",
                "ck_prf_workforce_projection_inbox_outcome",
                "ck_prf_workforce_projection_inbox_conflict");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_constraint constraint_record
                  JOIN pg_class relation_record
                    ON relation_record.oid = constraint_record.conrelid
                  JOIN pg_namespace namespace_record
                    ON namespace_record.oid = relation_record.relnamespace
                 WHERE namespace_record.nspname = 'hris_performance'
                   AND relation_record.relname LIKE 'prf_workforce_projection_%'
                   AND NOT constraint_record.convalidated
                """, Integer.class)).isZero();

        assertThat(jdbc.queryForList("""
                SELECT relname
                  FROM pg_class
                 WHERE relnamespace = 'hris_performance'::regnamespace
                   AND relname LIKE 'prf_workforce_projection_%'
                   AND relkind = 'r'
                   AND relrowsecurity
                   AND relforcerowsecurity
                 ORDER BY relname
                """, String.class)).contains(
                "prf_workforce_projection_heads",
                "prf_workforce_projection_inbox",
                "prf_workforce_projection_members",
                "prf_workforce_projection_revisions");

        assertThat(jdbc.queryForList("""
                SELECT policyname
                  FROM pg_policies
                 WHERE schemaname = 'hris_performance'
                   AND tablename LIKE 'prf_workforce_projection_%'
                 ORDER BY policyname
                """, String.class)).contains(
                "prf_workforce_projection_heads_tenant_isolation",
                "prf_workforce_projection_inbox_tenant_isolation",
                "prf_workforce_projection_members_tenant_isolation",
                "prf_workforce_projection_revisions_tenant_isolation");
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
