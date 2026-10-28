package com.dwp.services.payroll.statutory.pay005;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class Pay005V57MigrationPostgresTest {

    private static final String RUNTIME = "pay005_migration_runtime";
    private static final String PUBLISHER = "pay005_migration_publisher";
    private static final String UPGRADE_DATABASE = "pay005_v56_upgrade";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void cleanThroughV57AndV56ToV57UpgradePreserveExactBoundary()
            throws Exception {
        DataSource clean = dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        try (Connection connection = clean.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME + " NOLOGIN NOINHERIT");
            statement.execute("CREATE ROLE " + PUBLISHER + " NOLOGIN NOINHERIT");
            statement.execute("CREATE DATABASE " + UPGRADE_DATABASE);
        }

        migrate(clean, null);
        JdbcTemplate cleanJdbc = new JdbcTemplate(clean);
        assertHardenedV57(cleanJdbc);

        DataSource upgrade = dataSource(
                jdbcUrl(UPGRADE_DATABASE), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        migrate(upgrade, MigrationVersion.fromVersion("56"));
        JdbcTemplate upgradeJdbc = new JdbcTemplate(upgrade);
        assertThat(upgradeJdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version = '56' AND success
                """, Long.class)).isEqualTo(1L);
        migrate(upgrade, null);
        assertHardenedV57(upgradeJdbc);
        assertThat(upgradeJdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version IN ('56', '57') AND success
                """, Long.class)).isEqualTo(2L);
    }

    private void assertHardenedV57(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version = '57' AND success
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = current_schema()
                   AND table_name = 'pay_tax_result_facts'
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT relrowsecurity AND relforcerowsecurity
                  FROM pg_class
                 WHERE relname = 'pay_tax_result_facts'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_policies
                 WHERE schemaname = current_schema()
                   AND tablename = 'pay_tax_result_facts'
                   AND qual LIKE '%dwp.payroll_tenant_id%'
                   AND with_check LIKE '%dwp.payroll_tenant_id%'
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger
                 WHERE NOT tgisinternal AND tgrelid =
                       'pay_tax_result_facts'::regclass
                """, Long.class)).isEqualTo(2L);
    }

    private static void migrate(
            DataSource source, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "payrollRuntimeRole", RUNTIME,
                        "payrollProjectionPublisherRole", PUBLISHER));
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    private static DataSource dataSource(
            String url, String username, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource(
                url, username, password);
        source.setDriverClassName("org.postgresql.Driver");
        return source;
    }

    private static String jdbcUrl(String database) {
        String url = POSTGRES.getJdbcUrl();
        int query = url.indexOf('?');
        String suffix = query < 0 ? "" : url.substring(query);
        String base = query < 0 ? url : url.substring(0, query);
        return base.substring(0, base.lastIndexOf('/') + 1)
                + database + suffix;
    }
}
