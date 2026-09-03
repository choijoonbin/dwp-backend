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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MeetingTranscriptDeletionTypedContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V219__bind_meeting_transcript_deletion_typed_contract.sql");

    private static final String CODE_SET_KEY =
            "MEETING.VM_MEETING_TRANSCRIPT_DELETION_COMMANDS.COMMAND_STATE";
    private static final String SOURCE_REFERENCE =
            "MeetingTranscriptDeletionModels.CommandState";

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
    void freshPlatformMigrationBindsMeetingTranscriptDeletionEnum() {
        cleanAndMigrateThrough(null);

        assertExactBinding();
    }

    @Test
    void upgradeFromV218IsSemanticallyIdempotent() throws Exception {
        cleanAndMigrateThrough("218");

        executeForwardMigration();
        assertExactBinding();
        String fingerprint = bindingFingerprint();

        executeForwardMigration();

        assertExactBinding();
        assertThat(bindingFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void conflictingActiveTypedBindingFailsClosed() {
        cleanAndMigrateThrough("218");
        jdbc.update("""
                INSERT INTO sys_code_sets (
                    code_set_key, owner_service, display_name, description,
                    configuration_level, validation_source, source_reference,
                    contract_kind, runtime_visibility)
                VALUES (
                    'MEETING.CONFLICTING.TRANSCRIPT_COMMAND_STATE.API',
                    'dwp-meeting-server', 'Conflict', 'Conflict fixture',
                    'SYSTEM', 'TYPED_CONTRACT', 'test.transcript.command.state',
                    'STATE_MACHINE', 'ADMIN_ONLY')
                """);
        jdbc.update("""
                INSERT INTO sys_code_bindings (
                    code_set_key, consumer_service, usage_type,
                    source_reference, enforcement_type)
                VALUES (
                    'MEETING.CONFLICTING.TRANSCRIPT_COMMAND_STATE.API',
                    'dwp-meeting-server', 'API_CONTRACT',
                    'MeetingTranscriptDeletionModels.CommandState',
                    'TYPED_CONTRACT')
                """);

        assertThatThrownBy(
                MeetingTranscriptDeletionTypedContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .hasMessageContaining(
                        "conflicting Meeting transcript deletion typed binding");
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

    private static void assertExactBinding() {
        Integer bindingCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_code_bindings
                 WHERE code_set_key = ?
                   AND consumer_service = 'dwp-meeting-server'
                   AND usage_type = 'API_CONTRACT'
                   AND source_reference = ?
                   AND enforcement_type = 'TYPED_CONTRACT'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class, CODE_SET_KEY, SOURCE_REFERENCE);
        assertThat(bindingCount).isEqualTo(1);

        String activeCodes = jdbc.queryForObject("""
                SELECT string_agg(code, ',' ORDER BY code)
                  FROM sys_code_values
                 WHERE code_set_key = ?
                   AND lifecycle_state = 'ACTIVE'
                """, String.class, CODE_SET_KEY);
        assertThat(activeCodes).isEqualTo("FAILED,RUNNING,SUCCEEDED");
    }

    private static String bindingFingerprint() {
        return jdbc.queryForObject("""
                SELECT md5(string_agg(to_jsonb(binding)::TEXT, '|'
                                      ORDER BY code_binding_id))
                  FROM sys_code_bindings binding
                 WHERE binding.consumer_service = 'dwp-meeting-server'
                   AND binding.source_reference = ?
                """, String.class, SOURCE_REFERENCE);
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
