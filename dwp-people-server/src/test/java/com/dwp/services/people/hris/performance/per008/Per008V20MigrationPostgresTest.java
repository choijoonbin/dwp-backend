package com.dwp.services.people.hris.performance.per008;

import static com.dwp.services.people.hris.performance.per008.Per008V20MigrationFixture.*;
import static com.dwp.services.people.hris.performance.per008.Per008V20MigrationGuards.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class Per008V20MigrationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private final List<String> fixtureRoles = new ArrayList<>();

    @AfterEach
    void dropFixtureRoles() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        for (String role : fixtureRoles) {
            jdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA hris_performance "
                    + "REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLES FROM " + role);
            jdbc.execute("DROP OWNED BY " + role);
            jdbc.execute("DROP ROLE IF EXISTS " + role);
        }
        fixtureRoles.clear();
    }

    @Test
    void cleanV1ToV20CreatesOnlyTheSealedOwnerTablesAndForcesAllEightTenantPolicies() {
        DataSource source = dataSource();
        reset(source);
        prepareSharedRlsRuntimeRole(new JdbcTemplate(source));

        assertThat(flyway(source, null).migrate().migrationsExecuted).isPositive();
        JdbcTemplate jdbc = new JdbcTemplate(source);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.flyway_performance_schema_history
                 WHERE version = '20' AND success
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT tablename
                  FROM pg_catalog.pg_tables
                 WHERE schemaname = 'hris_performance'
                   AND tablename LIKE 'prf_feedback_%'
                 ORDER BY tablename
                """, String.class)).containsExactlyElementsOf(CREATED_TABLES.stream().sorted().toList());
        for (String table : CREATED_TABLES) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hris_performance." + table,
                    Integer.class)).isZero();
        }
        assertThat(jdbc.queryForList("""
                SELECT relname
                  FROM pg_catalog.pg_class
                 WHERE relnamespace = 'hris_performance'::regnamespace
                   AND relkind = 'r'
                   AND relname IN (%s)
                   AND relrowsecurity
                   AND relforcerowsecurity
                 ORDER BY relname
                """.formatted(sqlNames(PROTECTED_TABLES)), String.class))
                .containsExactlyElementsOf(PROTECTED_TABLES.stream().sorted().toList());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_constraint constraint_record
                  JOIN pg_catalog.pg_class relation_record
                    ON relation_record.oid = constraint_record.conrelid
                 WHERE relation_record.relnamespace = 'hris_performance'::regnamespace
                   AND relation_record.relname IN (%s)
                   AND NOT constraint_record.convalidated
                """.formatted(sqlNames(PROTECTED_TABLES)), Integer.class)).isZero();

        List<Map<String, Object>> policies = jdbc.queryForList("""
                SELECT tablename, qual, with_check
                  FROM pg_catalog.pg_policies
                 WHERE schemaname = 'hris_performance'
                   AND tablename IN (%s)
                 ORDER BY tablename
                """.formatted(sqlNames(PROTECTED_TABLES)));
        assertThat(policies).hasSize(8);
        for (Map<String, Object> policy : policies) {
            assertThat(policy.get("qual")).isEqualTo(policy.get("with_check"));
            assertThat(String.valueOf(policy.get("qual")))
                    .contains("current_setting")
                    .contains("dwp.tenant_id");
        }
        for (String table : PROTECTED_TABLES) {
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM information_schema.table_privileges
                     WHERE grantee = 'PUBLIC'
                       AND table_schema = 'hris_performance'
                       AND table_name = ?
                       AND privilege_type IN ('SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE')
                    """, Integer.class, table)).isZero();
        }
    }

    @Test
    void v19ToV20PreservesEveryPriorChecksumAndEveryReusedV1RowWithoutSeedOrBackfill() {
        DataSource source = dataSource();
        reset(source);
        prepareSharedRlsRuntimeRole(new JdbcTemplate(source));
        flyway(source, "19").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Map<String, Integer> checksums = historyChecksums(jdbc);
        BaseRows rows = baseRows(TENANT_A, "upgrade");
        insertBaseRows(jdbc, rows);

        assertThat(flyway(source, null).migrate().migrationsExecuted).isEqualTo(1);

        assertThat(historyChecksums(jdbc)).containsAllEntriesOf(checksums);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_cycles
                 WHERE tenant_id = ? AND cycle_id = ?
                """, Integer.class, rows.tenantId(), rows.cycleId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_cycle_versions
                 WHERE tenant_id = ? AND cycle_version_id = ?
                """, Integer.class, rows.tenantId(), rows.cycleVersionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, Integer.class, rows.tenantId(), rows.receiptId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_outbox_events
                 WHERE tenant_id = ? AND event_id = ?
                """, Integer.class, rows.tenantId(), rows.eventId())).isEqualTo(1);
        for (String table : CREATED_TABLES) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hris_performance." + table,
                    Integer.class)).isZero();
        }
    }

    @Test
    void nonBypassRoleAndDatabaseGuardsDenyMissingCrossTenantAndForgedEvidenceAndRollbackLifecycle()
            throws Exception {
        DataSource source = dataSource();
        reset(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        String role = prepareSharedRlsRuntimeRole(jdbc);
        flyway(source, null).migrate();
        seedProtectedRowsForBothTenants(source);

        assertSafeRuntimeRoleAndAcl(jdbc, role);
        for (String table : PROTECTED_TABLES) {
            assertThat(countAs(source, role, table, null)).isZero();
            assertThat(countAs(source, role, table, TENANT_A)).isEqualTo(1);
            assertThat(countAs(source, role, table, TENANT_B)).isEqualTo(1);
        }
        assertThat(updateAs(source, role, TENANT_A, """
                UPDATE hris_performance.prf_feedback_requests
                   SET updated_at = updated_at
                 WHERE tenant_id = 8082
                """)).isZero();

        verifyInitialRequestInsertGuard(source, jdbc);
        verifyCreateCommitAtomicity(source, jdbc);
        verifyResponseReceiptAndWindowGuards(source, jdbc);
        verifyThresholdAndLifecycleRollback(source, jdbc);
        verifyAssignmentTerminalAndCancellationBinding(source, jdbc);
    }

    private String prepareSharedRlsRuntimeRole(JdbcTemplate jdbc) {
        String role = "per008_runtime_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12);
        jdbc.execute("CREATE ROLE " + role
                + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA hris_performance TO " + role);
        jdbc.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA hris_performance "
                + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + role);
        fixtureRoles.add(role);
        return role;
    }

    private static DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

}
