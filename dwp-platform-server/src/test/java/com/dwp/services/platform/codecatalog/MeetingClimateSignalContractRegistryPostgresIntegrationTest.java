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
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MeetingClimateSignalContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V215__retire_unsupported_meeting_climate_signals.sql");
    private static final String CODE_SET = "MEETING.INTELLIGENCE.CLIMATE_SIGNAL";
    private static final List<String> EXPECTED_ACTIVE = List.of(
            "CONSTRUCTIVE_DISAGREEMENT",
            "LOW_TRANSCRIPT_EVIDENCE",
            "UNRESOLVED_DISAGREEMENT");
    private static final List<String> EXPECTED_RETIRED = List.of(
            "BALANCED_TURN_TAKING",
            "DOMINANT_MONOLOGUE_PATTERN");

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
    void freshMigrationExposesOnlySupportedClimateSignals() {
        cleanAndMigrateThrough(null);

        assertExactContract();
    }

    @Test
    void upgradeFromV214IsSemanticallyIdempotent() throws Exception {
        cleanAndMigrateThrough("214");

        executeForwardMigration();
        assertExactContract();
        String fingerprint = registryFingerprint();

        executeForwardMigration();

        assertExactContract();
        assertThat(registryFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void ownershipDriftFailsClosed() {
        cleanAndMigrateThrough("214");
        jdbc.update("""
                UPDATE sys_code_sets
                   SET source_reference = 'ConflictingClimateSignal'
                 WHERE code_set_key = ?
                """, CODE_SET);

        assertThatThrownBy(
                MeetingClimateSignalContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .hasMessageContaining("ownership drifted");
    }

    private static void cleanAndMigrateThrough(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        Flyway flyway = configuration.load();
        flyway.clean();
        flyway.migrate();
    }

    private static void assertExactContract() {
        assertThat(jdbc.queryForList("""
                SELECT code
                  FROM sys_code_values
                 WHERE code_set_key = ?
                   AND lifecycle_state = 'ACTIVE'
                 ORDER BY code
                """, String.class, CODE_SET))
                .containsExactlyElementsOf(EXPECTED_ACTIVE);
        assertThat(jdbc.queryForList("""
                SELECT code
                  FROM sys_code_values
                 WHERE code_set_key = ?
                   AND lifecycle_state = 'RETIRED'
                   AND code IN (
                       'BALANCED_TURN_TAKING',
                       'DOMINANT_MONOLOGUE_PATTERN')
                 ORDER BY code
                """, String.class, CODE_SET))
                .containsExactlyElementsOf(EXPECTED_RETIRED);
    }

    private static String registryFingerprint() {
        return jdbc.queryForObject("""
                SELECT md5(
                    COALESCE((
                        SELECT string_agg(to_jsonb(code_set)::TEXT, '|'
                                          ORDER BY code_set_key)
                          FROM sys_code_sets code_set), '') || '#' ||
                    COALESCE((
                        SELECT string_agg(to_jsonb(code_value)::TEXT, '|'
                                          ORDER BY code_set_key, code)
                          FROM sys_code_values code_value), '') || '#' ||
                    COALESCE((
                        SELECT string_agg(to_jsonb(binding)::TEXT, '|'
                                          ORDER BY code_binding_id)
                          FROM sys_code_bindings binding), ''))
                """, String.class);
    }

    private static void executeForwardMigration() throws Exception {
        String migration = Files.readString(MIGRATION);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute(migration);
            connection.commit();
        }
    }
}
