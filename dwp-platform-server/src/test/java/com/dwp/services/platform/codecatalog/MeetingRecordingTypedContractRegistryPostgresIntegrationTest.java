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
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MeetingRecordingTypedContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V217__bind_meeting_recording_typed_contracts.sql");

    private static final String EXPECTED_BINDINGS = """
            MEETING.VM_MEETING_RECORDING_PROVIDER_COMMANDS.COMMAND_STATE\tMeetingRecordingCommandModels.CommandState\tFAILED,RUNNING,SUCCEEDED
            MEETING.VM_MEETING_RECORDING_PROVIDER_COMMANDS.COMMAND_TYPE\tMeetingRecordingCommandModels.CommandType\tSTART,STOP
            MEETING.VM_MEETING_RECORDING_DELETION_COMMANDS.COMMAND_STATE\tMeetingRecordingDeletionModels.CommandState\tFAILED,RUNNING,SUCCEEDED
            """;

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
    void freshPlatformMigrationBindsMeetingRecordingEnums() {
        cleanAndMigrateThrough(null);

        assertExactBindings();
    }

    @Test
    void upgradeFromV216IsSemanticallyIdempotent() throws Exception {
        cleanAndMigrateThrough("216");

        executeForwardMigration();
        assertExactBindings();
        String fingerprint = bindingFingerprint();

        executeForwardMigration();

        assertExactBindings();
        assertThat(bindingFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void conflictingActiveTypedBindingFailsClosed() {
        cleanAndMigrateThrough("216");
        jdbc.update("""
                INSERT INTO sys_code_sets (
                    code_set_key, owner_service, display_name, description,
                    configuration_level, validation_source, source_reference,
                    contract_kind, runtime_visibility)
                VALUES (
                    'MEETING.CONFLICTING.RECORDING_COMMAND_STATE.API',
                    'dwp-meeting-server', 'Conflict', 'Conflict fixture',
                    'SYSTEM', 'TYPED_CONTRACT', 'test.recording.command.state',
                    'STATE_MACHINE', 'ADMIN_ONLY')
                """);
        jdbc.update("""
                INSERT INTO sys_code_bindings (
                    code_set_key, consumer_service, usage_type,
                    source_reference, enforcement_type)
                VALUES (
                    'MEETING.CONFLICTING.RECORDING_COMMAND_STATE.API',
                    'dwp-meeting-server', 'API_CONTRACT',
                    'MeetingRecordingCommandModels.CommandState',
                    'TYPED_CONTRACT')
                """);

        assertThatThrownBy(
                MeetingRecordingTypedContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .hasMessageContaining(
                        "conflicting Meeting recording typed binding");
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

    private static void assertExactBindings() {
        Map<String, ExpectedBinding> expected = Arrays.stream(
                        EXPECTED_BINDINGS.strip().split("\\R"))
                .map(line -> line.split("\\t", 3))
                .collect(Collectors.toMap(
                        parts -> parts[0],
                        parts -> new ExpectedBinding(parts[1], parts[2])));

        for (Map.Entry<String, ExpectedBinding> entry : expected.entrySet()) {
            String codeSetKey = entry.getKey();
            ExpectedBinding expectedBinding = entry.getValue();
            Integer bindingCount = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM sys_code_bindings
                     WHERE code_set_key = ?
                       AND consumer_service = 'dwp-meeting-server'
                       AND usage_type = 'API_CONTRACT'
                       AND source_reference = ?
                       AND enforcement_type = 'TYPED_CONTRACT'
                       AND lifecycle_state = 'ACTIVE'
                    """, Integer.class, codeSetKey,
                    expectedBinding.sourceReference());
            assertThat(bindingCount).isEqualTo(1);

            String activeCodes = jdbc.queryForObject("""
                    SELECT string_agg(code, ',' ORDER BY code)
                      FROM sys_code_values
                     WHERE code_set_key = ?
                       AND lifecycle_state = 'ACTIVE'
                    """, String.class, codeSetKey);
            assertThat(activeCodes).isEqualTo(expectedBinding.codes());
        }
    }

    private static String bindingFingerprint() {
        return jdbc.queryForObject("""
                SELECT md5(string_agg(to_jsonb(binding)::TEXT, '|'
                                      ORDER BY code_binding_id))
                  FROM sys_code_bindings binding
                 WHERE binding.consumer_service = 'dwp-meeting-server'
                   AND binding.source_reference IN (
                       'MeetingRecordingCommandModels.CommandState',
                       'MeetingRecordingCommandModels.CommandType',
                       'MeetingRecordingDeletionModels.CommandState')
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

    private record ExpectedBinding(String sourceReference, String codes) {
    }
}
