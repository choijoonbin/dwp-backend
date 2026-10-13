package com.dwp.services.people.hr.performance.compensationplanning;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class CompensationPlanningMigrationContractTest {

    private static final String MIGRATION =
            "db/performance-migration/V17__per_modern_compensation_planning.sql";
    private static final List<String> TABLES = List.of(
            "prf_cmp_approved_snapshot_lines",
            "prf_cmp_approved_snapshots",
            "prf_cmp_budget_ledger",
            "prf_cmp_cycles",
            "prf_cmp_proposal_revisions",
            "prf_cmp_proposals",
            "prf_per_artifact_versions");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    @Test
    void definesTheExactSevenTableLeaseAndFiveTenantScopedForeignKeys()
            throws IOException {
        String sql = migration();

        assertThat(sql.lines()
                .filter(line -> line.startsWith("CREATE TABLE hris_performance.")))
                .hasSize(7);
        assertThat(sql.lines().filter(line -> line.contains("CONSTRAINT fk_")))
                .hasSize(5);
        for (String table : TABLES) {
            assertThat(sql)
                    .contains("CREATE TABLE hris_performance." + table)
                    .contains("ALTER TABLE hris_performance." + table
                            + " ENABLE ROW LEVEL SECURITY")
                    .contains("ALTER TABLE hris_performance." + table
                            + " FORCE ROW LEVEL SECURITY")
                    .contains("CREATE POLICY " + table + "_tenant_isolation");
        }
        assertThat(sql)
                .contains("exception_approval_receipt_public_id IS NULL")
                .contains("DEFERRABLE INITIALLY DEFERRED")
                .contains("PER_COMPENSATION_APPEND_ONLY")
                .contains("PER_COMPENSATION_BUDGET_OVERFLOW")
                .contains("PER_COMPENSATION_CYCLE_CAS_VIOLATION")
                .contains("PER_COMPENSATION_PROPOSAL_CAS_VIOLATION")
                .contains("BEFORE TRUNCATE")
                .doesNotContain("ppl_")
                .doesNotContain("hrm_")
                .doesNotContain("REFERENCES public.")
                .doesNotContain("dblink")
                .doesNotContain("http://")
                .doesNotContain("https://");
    }

    @Test
    void upgradesV16ToV17AndForcesRlsOnEveryAllocatedTable() throws Exception {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS hris_performance CASCADE");
        jdbc.execute("CREATE SCHEMA hris_performance");

        flyway(dataSource, "16").migrate();
        assertThat(jdbc.queryForList("""
                SELECT version
                  FROM hris_performance.flyway_performance_schema_history
                 WHERE success
                 ORDER BY installed_rank
                """, String.class)).doesNotContain("17");

        Flyway unrestricted = flyway(dataSource, null);
        assertThat(unrestricted.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT version
                  FROM hris_performance.flyway_performance_schema_history
                 WHERE success
                 ORDER BY installed_rank
                """, String.class)).contains("16", "17");
        assertThat(jdbc.queryForList("""
                SELECT relname
                  FROM pg_class
                 WHERE relnamespace = 'hris_performance'::regnamespace
                   AND relkind = 'r'
                   AND relname = ANY (?)
                   AND relrowsecurity
                   AND relforcerowsecurity
                 ORDER BY relname
                """, String.class, jdbc.getDataSource().getConnection()
                .createArrayOf("text", TABLES.toArray()))).containsExactlyElementsOf(TABLES);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_constraint constraint_record
                  JOIN pg_class relation_record
                    ON relation_record.oid = constraint_record.conrelid
                 WHERE relation_record.relnamespace =
                       'hris_performance'::regnamespace
                   AND relation_record.relname = ANY (?)
                   AND NOT constraint_record.convalidated
                """, Integer.class, jdbc.getDataSource().getConnection()
                .createArrayOf("text", TABLES.toArray()))).isZero();
    }

    private static Flyway flyway(DataSource dataSource, String target) {
        var configuration = Flyway.configure(
                        CompensationPlanningMigrationContractTest.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/performance-migration")
                .defaultSchema("hris_performance")
                .schemas("hris_performance")
                .table("flyway_performance_schema_history")
                .validateOnMigrate(true)
                .outOfOrder(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static String migration() throws IOException {
        return new ClassPathResource(MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
