package com.dwp.services.platform.codecatalog;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class WidgetRegistryIngressFailureContractRegistryPostgresIntegrationTest {

    private static final String CODE_SET_KEY =
            "PLATFORM.WIDGET_REGISTRY.INGRESS_FAILURE";
    private static final String SOURCE_REFERENCE =
            "WidgetRegistryIngressFailure";
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V207__reconcile_widget_registry_ingress_failure_contract.sql");
    private static final List<String> EXPECTED_CODES = List.of(
            "ASSERTION_INVALID",
            "ASSERTION_REPLAYED",
            "AUTHORITY_HEADERS_FORBIDDEN",
            "DUAL_PROOF_REQUIRED",
            "METHOD_NOT_ALLOWED",
            "PAYLOAD_TOO_LARGE",
            "PROVISIONING_TOKEN_FORBIDDEN",
            "REQUEST_BINDING_INVALID",
            "ROUTE_NOT_FOUND",
            "SERVICE_TOKEN_INVALID",
            "TLS_REQUIRED",
            "TRUST_UNAVAILABLE");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void configureDataSource() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void cleanLatestRegistersTheExactIngressFailureContract() {
        cleanAndMigrateLatest();

        assertThat(migrationSucceeded("207")).isTrue();
        assertExactContract();
    }

    @Test
    void upgradingV206AddsOnlyTheNewFailureAndOneRevision() throws Exception {
        cleanAndMigrateThroughV206();
        int revisionBefore = currentRevision();

        executeForwardMigration();

        assertExactContract();
        assertThat(currentRevision()).isEqualTo(revisionBefore + 1);
    }

    @Test
    void forwardMigrationIsIdempotentWithoutSyntheticRevisionBumps()
            throws Exception {
        cleanAndMigrateThroughV206();
        executeForwardMigration();
        String fingerprint = contractFingerprint();
        int revision = currentRevision();

        executeForwardMigration();

        assertExactContract();
        assertThat(contractFingerprint()).isEqualTo(fingerprint);
        assertThat(currentRevision()).isEqualTo(revision);
    }

    @Test
    void unexpectedActiveValueFailsClosed() {
        cleanAndMigrateThroughV206();
        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    behavior_metadata, sort_order, predefined, lifecycle_state)
                VALUES (?, 'FORGED_FAILURE', 'FORGED_FAILURE',
                    '{}'::jsonb, '{}'::jsonb, 999, FALSE, 'ACTIVE')
                """, CODE_SET_KEY);

        assertThatThrownBy(
                WidgetRegistryIngressFailureContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("values or revision drifted");
        assertThat(activeCodes()).doesNotContain("AUTHORITY_HEADERS_FORBIDDEN");
    }

    @Test
    void bindingDriftFailsClosed() {
        cleanAndMigrateThroughV206();
        jdbc.update("""
                INSERT INTO sys_code_bindings (
                    code_set_key, consumer_service, usage_type,
                    source_reference, enforcement_type, lifecycle_state)
                VALUES (
                    'PLATFORM.WIDGET_REGISTRY.INTERNAL_ROUTE',
                    'dwp-platform-server', 'API_CONTRACT', ?,
                    'TYPED_CONTRACT', 'ACTIVE')
                """, SOURCE_REFERENCE);

        assertThatThrownBy(
                WidgetRegistryIngressFailureContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("binding drifted");
        assertThat(activeCodes()).doesNotContain("AUTHORITY_HEADERS_FORBIDDEN");
    }

    @Test
    void codeSetMetadataDriftFailsClosed() {
        cleanAndMigrateThroughV206();
        jdbc.update("""
                UPDATE sys_code_sets
                   SET description = 'forged contract description'
                 WHERE code_set_key = ?
                """, CODE_SET_KEY);

        assertThatThrownBy(
                WidgetRegistryIngressFailureContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("identity or metadata drifted");
        assertThat(activeCodes()).doesNotContain("AUTHORITY_HEADERS_FORBIDDEN");
    }

    @Test
    void revisionDriftFailsClosed() {
        cleanAndMigrateThroughV206();
        jdbc.update("""
                UPDATE sys_code_sets
                   SET schema_version = 4
                 WHERE code_set_key = ?
                """, CODE_SET_KEY);

        assertThatThrownBy(
                WidgetRegistryIngressFailureContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("values or revision drifted");
        assertThat(activeCodes()).doesNotContain("AUTHORITY_HEADERS_FORBIDDEN");
    }

    @Test
    void preexistingTargetValueMetadataDriftFailsClosed() {
        cleanAndMigrateThroughV206();
        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    behavior_metadata, sort_order, predefined, lifecycle_state)
                VALUES (?, 'AUTHORITY_HEADERS_FORBIDDEN', 'FORGED_DISPLAY',
                    '{}'::jsonb, '{}'::jsonb, 999, FALSE, 'ACTIVE')
                """, CODE_SET_KEY);

        assertThatThrownBy(
                WidgetRegistryIngressFailureContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("target value metadata drifted");
        assertThat(jdbc.queryForObject("""
                SELECT display_name
                  FROM sys_code_values
                 WHERE code_set_key = ?
                   AND code = 'AUTHORITY_HEADERS_FORBIDDEN'
                """, String.class, CODE_SET_KEY)).isEqualTo("FORGED_DISPLAY");
    }

    private static void cleanAndMigrateThroughV206() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("206")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void cleanAndMigrateLatest() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void executeForwardMigration() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute(Files.readString(MIGRATION));
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void assertExactContract() {
        assertThat(activeCodes()).containsExactlyElementsOf(EXPECTED_CODES);
        assertThat(jdbc.queryForObject("""
                SELECT owner_service || '|' || source_reference || '|' ||
                       configuration_level || '|' || validation_source || '|' ||
                       contract_kind || '|' || runtime_visibility || '|' ||
                       lifecycle_state
                  FROM sys_code_sets
                 WHERE code_set_key = ?
                """, String.class, CODE_SET_KEY)).isEqualTo(
                "dwp-platform-server|" + SOURCE_REFERENCE
                        + "|SYSTEM|TYPED_CONTRACT|SECURITY|ADMIN_ONLY|ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_code_bindings
                 WHERE code_set_key = ?
                   AND consumer_service = 'dwp-platform-server'
                   AND usage_type = 'API_CONTRACT'
                   AND source_reference = ?
                   AND enforcement_type = 'TYPED_CONTRACT'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class, CODE_SET_KEY, SOURCE_REFERENCE)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_code_values
                 WHERE code_set_key = ?
                   AND code = 'AUTHORITY_HEADERS_FORBIDDEN'
                   AND display_name = code
                   AND label_i18n = jsonb_build_object('ko', code, 'en', code)
                   AND behavior_metadata = '{}'::jsonb
                   AND sort_order = 45
                   AND predefined
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class, CODE_SET_KEY)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT value_count || '|' || binding_count || '|' ||
                       enforced_binding_count || '|' || registration_state
                  FROM sys_code_catalog_health
                 WHERE code_set_key = ?
                """, String.class, CODE_SET_KEY)).isEqualTo(
                "12|1|1|REGISTERED");
    }

    private static List<String> activeCodes() {
        return jdbc.queryForList("""
                SELECT code
                  FROM sys_code_values
                 WHERE code_set_key = ?
                   AND lifecycle_state = 'ACTIVE'
                 ORDER BY code
                """, String.class, CODE_SET_KEY);
    }

    private static boolean migrationSucceeded(String version) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) = 1
                  FROM flyway_schema_history
                 WHERE version = ?
                   AND success
                """, Boolean.class, version);
    }

    private static int currentRevision() {
        return jdbc.queryForObject("""
                SELECT schema_version
                  FROM sys_code_sets
                 WHERE code_set_key = ?
                """, Integer.class, CODE_SET_KEY);
    }

    private static String contractFingerprint() {
        return jdbc.queryForObject("""
                SELECT md5(
                    (SELECT to_jsonb(code_set)::TEXT
                       FROM sys_code_sets code_set
                      WHERE code_set_key = ?) || '#' ||
                    (SELECT string_agg(to_jsonb(code_value)::TEXT, '|'
                                       ORDER BY code)
                       FROM sys_code_values code_value
                      WHERE code_set_key = ?) || '#' ||
                    (SELECT string_agg(to_jsonb(binding)::TEXT, '|'
                                       ORDER BY code_binding_id)
                       FROM sys_code_bindings binding
                      WHERE code_set_key = ?))
                """, String.class, CODE_SET_KEY, CODE_SET_KEY, CODE_SET_KEY);
    }
}
