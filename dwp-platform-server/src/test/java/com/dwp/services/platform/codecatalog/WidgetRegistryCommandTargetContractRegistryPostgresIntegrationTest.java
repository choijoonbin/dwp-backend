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
class WidgetRegistryCommandTargetContractRegistryPostgresIntegrationTest {

    private static final String CODE_SET_KEY =
            "PLATFORM.WIDGET_REGISTRY.COMMAND_TARGET_CONTRACT";
    private static final String SOURCE_REFERENCE =
            "WidgetRegistryCommandTrustPolicy.TargetContract";
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V206__govern_widget_registry_command_target_contract.sql");
    private static final List<String> EXPECTED_CODES = List.of(
            "DEFINITION",
            "DEFINITION_CHANNEL_HASH",
            "DEFINITION_KEY_HASH",
            "DEFINITION_SEMVER_HASH",
            "EVIDENCE",
            "RUNTIME_CONTROL",
            "RUNTIME_CONTROL_SCOPE_HASH",
            "VERSION");

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
    void cleanLatestRegistersTheExactTargetContractAndBinding() {
        cleanAndMigrateLatest();

        assertThat(migrationSucceeded("206")).isTrue();
        assertExactContract();
    }

    @Test
    void forwardMigrationIsIdempotentWithoutSyntheticRevisionBumps()
            throws Exception {
        cleanAndMigrateThroughV205();

        executeForwardMigration();
        String fingerprint = contractFingerprint();
        executeForwardMigration();

        assertExactContract();
        assertThat(contractFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void codeSetIdentityCollisionFailsClosed() {
        cleanAndMigrateThroughV205();
        jdbc.update("""
                INSERT INTO sys_code_sets (
                    code_set_key, owner_service, display_name, description,
                    configuration_level, validation_source, source_reference,
                    contract_kind, runtime_visibility, lifecycle_state)
                VALUES (?, 'foreign-service', 'Collision', 'Collision',
                    'SYSTEM', 'TYPED_CONTRACT', 'Foreign.TargetContract',
                    'SECURITY', 'ADMIN_ONLY', 'ACTIVE')
                """, CODE_SET_KEY);

        assertThatThrownBy(
                WidgetRegistryCommandTargetContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("code-set identity or metadata drifted");
    }

    @Test
    void activeValueDriftFailsClosed() throws Exception {
        cleanAndMigrateThroughV205();
        executeForwardMigration();
        jdbc.update("""
                UPDATE sys_code_values
                   SET lifecycle_state = 'RETIRED'
                 WHERE code_set_key = ?
                   AND code = 'RUNTIME_CONTROL'
                """, CODE_SET_KEY);

        assertThatThrownBy(
                WidgetRegistryCommandTargetContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("code-set values drifted");
    }

    @Test
    void conflictingSourceBindingFailsClosed() {
        cleanAndMigrateThroughV205();
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
                WidgetRegistryCommandTargetContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("conflicting command target binding");
    }

    private static void cleanAndMigrateThroughV205() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("205")
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
        assertThat(jdbc.queryForList("""
                SELECT code
                  FROM sys_code_values
                 WHERE code_set_key = ?
                   AND lifecycle_state = 'ACTIVE'
                 ORDER BY code
                """, String.class, CODE_SET_KEY))
                .containsExactlyElementsOf(EXPECTED_CODES);
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
                   AND usage_type = 'BEHAVIOR'
                   AND source_reference = ?
                   AND enforcement_type = 'TYPED_CONTRACT'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class, CODE_SET_KEY, SOURCE_REFERENCE)).isEqualTo(1);
    }

    private static boolean migrationSucceeded(String version) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) = 1
                  FROM flyway_schema_history
                 WHERE version = ?
                   AND success
                """, Boolean.class, version);
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
