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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MailDraftReceiptAndMeetingWebhookEnumContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V214__govern_mail_draft_receipt_and_meeting_webhook_enums.sql");
    private static final String MAIL_COMMAND_SET =
            "PLATFORM.MAIL_DRAFT_COMMAND_RECEIPTS.COMMAND_TYPE";
    private static final String MAIL_STATUS_SET =
            "PLATFORM.MAIL_DRAFT_COMMAND_RECEIPTS.COMMAND_STATUS";
    private static final String MEETING_EVENT_SET =
            "MEETING.VM_MEETING_PROVIDER_EVENTS.EVENT_TYPE";

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
    void freshMigrationRegistersExactMailAndMeetingEnumContracts() {
        cleanAndMigrateThrough(null);

        assertExactContracts();
    }

    @Test
    void upgradeFromV213IsSemanticallyIdempotent() throws Exception {
        cleanAndMigrateThrough("213");

        executeForwardMigration();
        assertExactContracts();
        String fingerprint = registryFingerprint();

        executeForwardMigration();

        assertExactContracts();
        assertThat(registryFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void invalidReceiptCommandAndStateAreRejectedByPostgres() {
        cleanAndMigrateThrough(null);

        assertThatThrownBy(() -> insertReceipt("DELETE", "IN_PROGRESS"))
                .isInstanceOf(
                        org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReceipt("CREATE", "CORRUPT"))
                .isInstanceOf(
                        org.springframework.dao.DataIntegrityViolationException.class);
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

    private static void assertExactContracts() {
        assertCodes(MAIL_COMMAND_SET, List.of("CREATE", "SAVE"));
        assertCodes(MAIL_STATUS_SET, List.of("COMPLETED", "IN_PROGRESS"));
        assertCodes(MEETING_EVENT_SET, List.of(
                "PARTICIPANT_CONNECTION_ABORTED",
                "PARTICIPANT_JOINED",
                "PARTICIPANT_LEFT",
                "ROOM_FINISHED",
                "ROOM_STARTED"));

        assertBinding(
                MAIL_COMMAND_SET,
                "dwp-platform-server",
                "BEHAVIOR",
                "MailDraftCommandReceiptRepository.CommandType",
                "TYPED_CONTRACT");
        assertBinding(
                MEETING_EVENT_SET,
                "dwp-meeting-server",
                "API_CONTRACT",
                "MeetingMediaWebhook.EventType",
                "TYPED_CONTRACT");
        assertBinding(
                MAIL_COMMAND_SET,
                "dwp-platform-server",
                "DATABASE_COLUMN",
                "mail_draft_command_receipts.command_type",
                "CHECK");
        assertBinding(
                MAIL_STATUS_SET,
                "dwp-platform-server",
                "DATABASE_COLUMN",
                "mail_draft_command_receipts.command_status",
                "CHECK");
    }

    private static void assertCodes(String codeSetKey, List<String> expected) {
        assertThat(jdbc.queryForList("""
                SELECT code
                  FROM sys_code_values
                 WHERE code_set_key = ?
                   AND lifecycle_state = 'ACTIVE'
                 ORDER BY code
                """, String.class, codeSetKey)).containsExactlyElementsOf(expected);
    }

    private static void assertBinding(
            String codeSetKey,
            String consumerService,
            String usageType,
            String sourceReference,
            String enforcementType) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_code_bindings
                 WHERE code_set_key = ?
                   AND consumer_service = ?
                   AND usage_type = ?
                   AND source_reference = ?
                   AND enforcement_type = ?
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class, codeSetKey, consumerService, usageType,
                sourceReference, enforcementType);
        assertThat(count).isEqualTo(1);
    }

    private static void insertReceipt(String commandType, String commandStatus) {
        jdbc.update("""
                INSERT INTO mail_draft_command_receipts (
                    tenant_id, actor_user_id, command_type, idempotency_key,
                    request_fingerprint, command_status)
                VALUES (1, 1, ?, ?, ?, ?)
                """, commandType, UUID.randomUUID(), "0".repeat(64), commandStatus);
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
