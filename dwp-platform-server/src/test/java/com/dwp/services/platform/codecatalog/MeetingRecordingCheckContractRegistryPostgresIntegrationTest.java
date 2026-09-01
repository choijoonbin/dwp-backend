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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MeetingRecordingCheckContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V216__register_meeting_recording_check_contracts.sql");

    private static final String EXPECTED_CONTRACTS = """
            MEETING.VM_MEETING_RECORDING_DELETION_COMMANDS.COMMAND_STATE\tvm_meeting_recording_deletion_commands.command_state\tSTATE_MACHINE\tFAILED,RUNNING,SUCCEEDED
            MEETING.VM_MEETING_RECORDING_DELETION_HEALTH.HEALTH_KEY\tvm_meeting_recording_deletion_health.health_key\tOBSERVABILITY\tRECORDING_RETENTION
            MEETING.VM_MEETING_RECORDING_PROVIDER_COMMANDS.COMMAND_STATE\tvm_meeting_recording_provider_commands.command_state\tSTATE_MACHINE\tFAILED,RUNNING,SUCCEEDED
            MEETING.VM_MEETING_RECORDING_PROVIDER_COMMANDS.COMMAND_TYPE\tvm_meeting_recording_provider_commands.command_type\tPROTOCOL\tSTART,STOP
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
    void freshPlatformMigrationProjectsMeetingRecordingCheckContracts() {
        cleanAndMigrateThrough(null);

        assertExactProjection();
    }

    @Test
    void upgradeFromV215IsSemanticallyIdempotent() throws Exception {
        cleanAndMigrateThrough("215");

        executeForwardMigration();
        assertExactProjection();
        String fingerprint = registryFingerprint();

        executeForwardMigration();

        assertExactProjection();
        assertThat(registryFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void conflictingActiveCheckBindingFailsClosed() {
        cleanAndMigrateThrough("215");
        jdbc.update("""
                INSERT INTO sys_code_sets (
                    code_set_key, owner_service, display_name, description,
                    configuration_level, validation_source, source_reference,
                    contract_kind, runtime_visibility)
                VALUES (
                    'MEETING.CONFLICTING.RECORDING_COMMAND_STATE',
                    'dwp-meeting-server', 'Conflict', 'Conflict fixture',
                    'SYSTEM', 'CHECK', 'test.recording_command_state',
                    'STATE_MACHINE', 'ADMIN_ONLY')
                """);
        jdbc.update("""
                INSERT INTO sys_code_bindings (
                    code_set_key, consumer_service, usage_type,
                    source_reference, enforcement_type)
                VALUES (
                    'MEETING.CONFLICTING.RECORDING_COMMAND_STATE',
                    'dwp-meeting-server', 'DATABASE_COLUMN',
                    'vm_meeting_recording_provider_commands.command_state',
                    'CHECK')
                """);

        assertThatThrownBy(
                MeetingRecordingCheckContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .hasMessageContaining(
                        "conflicting active Meeting recording CHECK binding");
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

    private static void assertExactProjection() {
        Map<String, ExpectedContract> expected = Arrays.stream(
                        EXPECTED_CONTRACTS.strip().split("\\R"))
                .map(line -> line.split("\\t", 4))
                .collect(Collectors.toMap(
                        parts -> parts[0],
                        parts -> new ExpectedContract(
                                parts[1], parts[2], parts[3])));

        for (Map.Entry<String, ExpectedContract> entry : expected.entrySet()) {
            String codeSetKey = entry.getKey();
            ExpectedContract contract = entry.getValue();
            CodeSetMetadata metadata = jdbc.queryForObject("""
                    SELECT owner_service, validation_source, source_reference,
                           contract_kind, configuration_level,
                           runtime_visibility, lifecycle_state
                      FROM sys_code_sets
                     WHERE code_set_key = ?
                    """, (row, ignored) -> new CodeSetMetadata(
                    row.getString("owner_service"),
                    row.getString("validation_source"),
                    row.getString("source_reference"),
                    row.getString("contract_kind"),
                    row.getString("configuration_level"),
                    row.getString("runtime_visibility"),
                    row.getString("lifecycle_state")), codeSetKey);
            assertThat(metadata).isEqualTo(new CodeSetMetadata(
                    "dwp-meeting-server",
                    "CHECK",
                    contract.sourceReference(),
                    contract.contractKind(),
                    "SYSTEM",
                    "ADMIN_ONLY",
                    "ACTIVE"));

            List<String> expectedCodes = Arrays.stream(contract.codes().split(","))
                    .sorted()
                    .toList();
            assertThat(jdbc.queryForList("""
                    SELECT code
                      FROM sys_code_values
                     WHERE code_set_key = ?
                       AND lifecycle_state = 'ACTIVE'
                     ORDER BY code
                    """, String.class, codeSetKey))
                    .containsExactlyElementsOf(expectedCodes);

            Integer bindingCount = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM sys_code_bindings
                     WHERE code_set_key = ?
                       AND consumer_service = 'dwp-meeting-server'
                       AND usage_type = 'DATABASE_COLUMN'
                       AND source_reference = ?
                       AND enforcement_type = 'CHECK'
                       AND lifecycle_state = 'ACTIVE'
                    """, Integer.class, codeSetKey, contract.sourceReference());
            assertThat(bindingCount).isEqualTo(1);
        }
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

    private record ExpectedContract(
            String sourceReference,
            String contractKind,
            String codes) {
    }

    private record CodeSetMetadata(
            String ownerService,
            String validationSource,
            String sourceReference,
            String contractKind,
            String configurationLevel,
            String runtimeVisibility,
            String lifecycleState) {
    }
}
