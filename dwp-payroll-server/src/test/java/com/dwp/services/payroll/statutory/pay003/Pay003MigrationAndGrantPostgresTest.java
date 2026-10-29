package com.dwp.services.payroll.statutory.pay003;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class Pay003MigrationAndGrantPostgresTest {

    private static final String RUNTIME = "pay003_runtime";
    private static final String RUNTIME_PASSWORD = "pay003-runtime-password";
    private static final String PUBLISHER = "pay003_projection_publisher";
    private static final String PUBLISHER_PASSWORD = "pay003-publisher-password";
    private static final String UPGRADE_DATABASE = "pay003_v52_upgrade";
    private static final List<String> TABLES = List.of(
            "pay_country_pack_version_snapshots",
            "pay_country_pack_activation_versions",
            "pay_country_pack_command_receipts",
            "pay_country_pack_audit_outbox_events",
            "pay_social_insurance_result_facts");
    private static final UUID LEGAL_ENTITY =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PAY_GROUP =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID INSTALLER =
            UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID APPROVER =
            UUID.fromString("10000000-0000-0000-0000-000000000005");
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final Instant RECORDED = Instant.parse("2026-10-18T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource adminDataSource;
    private static DataSource runtimeDataSource;
    private static DataSource upgradeDataSource;
    private static JdbcTemplate admin;
    private static JdbcTemplate upgrade;

    @BeforeAll
    static void migrateFreshAndV52Upgrade() throws Exception {
        adminDataSource = dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = adminDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME
                    + " LOGIN NOINHERIT PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("CREATE ROLE " + PUBLISHER
                    + " LOGIN NOINHERIT PASSWORD '" + PUBLISHER_PASSWORD + "'");
            statement.execute("CREATE DATABASE " + UPGRADE_DATABASE);
        }

        migrate(adminDataSource, null);
        runtimeDataSource = dataSource(POSTGRES.getJdbcUrl(), RUNTIME, RUNTIME_PASSWORD);
        admin = new JdbcTemplate(adminDataSource);

        upgradeDataSource = dataSource(
                jdbcUrl(UPGRADE_DATABASE), POSTGRES.getUsername(), POSTGRES.getPassword());
        migrate(upgradeDataSource, MigrationVersion.fromVersion("52"));
        upgrade = new JdbcTemplate(upgradeDataSource);
        upgrade.update("""
                INSERT INTO pay_population_source_revisions (
                    tenant_id, source_id, source_revision, payload_digest,
                    legal_entity_id, payroll_group_id, population_kind,
                    named_population_id, item_count, effective_at, recorded_at)
                VALUES (77, ?, 1, ?, ?, ?, 'LEGAL_ENTITY', NULL, 0,
                    TIMESTAMPTZ '2026-10-18 00:00:00Z',
                    TIMESTAMPTZ '2026-10-18 00:00:00Z')
                """, UUID.fromString("10000000-0000-0000-0000-000000000077"), A,
                LEGAL_ENTITY, PAY_GROUP);
        migrate(upgradeDataSource, null);
    }

    @AfterAll
    static void releaseReferences() {
        upgrade = null;
        admin = null;
        runtimeDataSource = null;
        upgradeDataSource = null;
        adminDataSource = null;
    }

    @BeforeEach
    void resetPay003Facts() {
        admin.execute("TRUNCATE TABLE " + String.join(", ", TABLES)
                + " RESTART IDENTITY CASCADE");
    }

    @Test
    void freshV53AndV52ToV53UpgradeCreateExactlyFiveHardenedTables() {
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version = '53' AND success
                """, Long.class)).isEqualTo(1L);
        assertThat(upgrade.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE version IN ('52', '53') AND success
                """, Long.class)).isEqualTo(2L);
        assertThat(upgrade.queryForObject("""
                SELECT COUNT(*) FROM pay_population_source_revisions
                 WHERE tenant_id = 77 AND source_revision = 1
                """, Long.class)).isEqualTo(1L);
        assertThat(admin.queryForList("""
                SELECT relname FROM pg_class
                 WHERE relkind = 'r' AND relname IN (%s)
                 ORDER BY relname
                """.formatted(sqlNames(TABLES)), String.class))
                .containsExactlyElementsOf(TABLES.stream().sorted().toList());
        assertThat(admin.queryForObject("""
                SELECT bool_and(relrowsecurity AND relforcerowsecurity)
                  FROM pg_class WHERE relname IN (%s)
                """.formatted(sqlNames(TABLES)), Boolean.class)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pg_policies
                 WHERE schemaname = current_schema()
                   AND tablename IN (%s)
                   AND qual LIKE '%%dwp.payroll_tenant_id%%'
                   AND with_check LIKE '%%dwp.payroll_tenant_id%%'
                """.formatted(sqlNames(TABLES)), Long.class)).isEqualTo(5L);
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger
                 WHERE NOT tgisinternal
                   AND tgname LIKE 'pay_%%_immutable'
                   AND tgrelid::regclass::text IN (%s)
                """.formatted(sqlNames(TABLES)), Long.class)).isEqualTo(5L);
    }

    @Test
    void grantsSplitSourcePublicationFromRuntimeCommandsAndRls() {
        assertThat(privilege(RUNTIME,
                "pay_country_pack_version_snapshots", "INSERT")).isFalse();
        assertThat(privilege(RUNTIME,
                "pay_social_insurance_result_facts", "INSERT")).isFalse();
        assertThat(privilege(RUNTIME,
                "pay_country_pack_activation_versions", "INSERT")).isTrue();
        assertThat(privilege(RUNTIME,
                "pay_country_pack_command_receipts", "INSERT")).isTrue();
        assertThat(privilege(PUBLISHER,
                "pay_country_pack_version_snapshots", "INSERT")).isTrue();
        assertThat(privilege(PUBLISHER,
                "pay_social_insurance_result_facts", "INSERT")).isTrue();
        assertThat(privilege(PUBLISHER,
                "pay_country_pack_command_receipts", "INSERT")).isFalse();
        for (String table : TABLES) {
            assertThat(privilege(RUNTIME, table, "UPDATE")).isFalse();
            assertThat(privilege(PUBLISHER, table, "DELETE")).isFalse();
        }

        assertThatThrownBy(() -> inTransaction(runtimeDataSource, jdbc -> {
            bind(jdbc, 1);
            snapshot(jdbc, UUID.randomUUID(), "GB.NI", "forged", 1,
                    LocalDate.parse("2026-10-01"), RECORDED);
        })).hasRootCauseInstanceOf(PSQLException.class);

        UUID tenantOne = UUID.randomUUID();
        UUID tenantTwo = UUID.randomUUID();
        snapshot(admin, tenantOne, "GB.NI", "one", 1,
                LocalDate.parse("2026-10-01"), RECORDED);
        snapshot(admin, tenantTwo, "GB.NI", "two", 2,
                LocalDate.parse("2026-10-01"), RECORDED);
        inTransaction(runtimeDataSource, jdbc -> {
            bind(jdbc, 1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pay_country_pack_version_snapshots",
                    Long.class)).isEqualTo(1L);
        });
    }

    private static void migrate(DataSource source, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "payrollRuntimeRole", RUNTIME,
                        "payrollProjectionPublisherRole", PUBLISHER));
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    private boolean privilege(String role, String table, String permission) {
        return Boolean.TRUE.equals(admin.queryForObject(
                "SELECT has_table_privilege(?, ?, ?)",
                Boolean.class, role, table, permission));
    }

    private static void snapshot(
            JdbcTemplate jdbc,
            UUID pack,
            String packKey,
            String version,
            long tenant,
            LocalDate effectiveFrom,
            Instant recordedAt) {
        jdbc.update("""
                INSERT INTO pay_country_pack_version_snapshots (
                    tenant_id, pack_id, pack_key, pack_version,
                    snapshot_revision, legal_entity_id, payroll_group_id,
                    jurisdiction_code, lifecycle_state, payload_digest,
                    artifact_digest, signature_digest, signature_algorithm,
                    signing_key_id, compatibility_version,
                    policy_schema_version, official_reference_set,
                    golden_pack_digest, statutory_approval_receipt_id,
                    installer_subject_public_id,
                    statutory_approver_subject_public_id,
                    effective_from, effective_to, recorded_at)
                VALUES (?, ?, ?, ?, 0, ?, ?, 'GB', 'AVAILABLE', ?, ?, ?,
                    'ED25519', 'key-1', '1', 'policy-v1', 'UK-REF-2026', ?,
                    ?, ?, ?, ?, NULL, ?)
                """, tenant, pack, packKey, version, LEGAL_ENTITY, PAY_GROUP,
                A, B, C, A, UUID.randomUUID(), INSTALLER, APPROVER,
                Date.valueOf(effectiveFrom), Timestamp.from(recordedAt));
    }

    private static void bind(JdbcTemplate jdbc, long tenant) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', ?, true)",
                String.class, Long.toString(tenant));
    }

    private static void inTransaction(
            DataSource source, java.util.function.Consumer<JdbcTemplate> work) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(ignored -> work.accept(new JdbcTemplate(source)));
    }

    private static String sqlNames(List<String> names) {
        return names.stream().map(name -> "'" + name + "'")
                .reduce((left, right) -> left + "," + right).orElseThrow();
    }

    private static DataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                url, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    private static String jdbcUrl(String database) {
        String url = POSTGRES.getJdbcUrl();
        int query = url.indexOf('?');
        String suffix = query < 0 ? "" : url.substring(query);
        String base = query < 0 ? url : url.substring(0, query);
        return base.substring(0, base.lastIndexOf('/') + 1) + database + suffix;
    }
}
