package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MailExternalSyncPostgresTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void providerBatchIsHtmlSafeCursorAtomicAndIdempotent() {
        String schema = "mail_external_sync_materialization";
        migrate(schema);
        PGSimpleDataSource source = dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AdminMailCompletionRepository repository =
                new AdminMailCompletionRepository(jdbc, objectMapper);
        MailJsonCodec json = new MailJsonCodec(objectMapper);
        SyncFixture fixture = fixture(jdbc);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(source);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        String messageReference = "external:message:" + UUID.randomUUID();
        String threadReference = "external:thread:" + UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        String checksum = "a".repeat(64);
        List<Map<String, Object>> attachmentProjection = List.of(Map.of(
                "attachmentId", attachmentId,
                "providerAttachmentReference", "provider-attachment-1",
                "contentReference", "provider-fetch-token",
                "fileName", "remote.pdf",
                "contentType", "application/pdf",
                "sizeBytes", 4096,
                "checksumSha256", checksum,
                "scanState", "BLOCKED",
                "scanEvidence", "PROVIDER_CONTENT_NOT_FETCHED"));
        AdminMailCompletionRepository.InboundMessageRow inbound = inbound(
                messageReference, threadReference, attachmentProjection);
        AdminMailCompletionRepository.InboundAttachmentRow blocked =
                new AdminMailCompletionRepository.InboundAttachmentRow(
                        attachmentId, "provider-blocked:" + checksum,
                        "remote.pdf", "application/pdf", 4096, checksum,
                        "BLOCKED", "PROVIDER_CONTENT_NOT_FETCHED");

        AdminMailCompletionRepository.InboundMaterialized first = transactions.execute(status -> {
            AdminMailCompletionRepository.SyncAccountRow account = repository
                    .lockSyncAccount(fixture.tenantId(), fixture.accountId()).orElseThrow();
            UUID folderId = repository.synchronizationFolder(
                    fixture.tenantId(), fixture.accountId(), "provider-inbox").orElseThrow();
            AdminMailCompletionRepository.InboundMaterialized inserted =
                    repository.materializeInboundMessage(
                            fixture.tenantId(), fixture.ownerUserId(), account,
                            folderId, inbound, List.of(blocked));
            assertThat(repository.updateAccountSyncCursor(
                    fixture.tenantId(), fixture.accountId(), fixture.cursor(),
                    "cursor-1", fixture.ownerUserId())).isOne();
            return inserted;
        });
        assertThat(first).isNotNull();
        assertThat(first.inserted()).isTrue();

        AdminMailCompletionRepository.InboundMaterialized duplicate = transactions.execute(status -> {
            AdminMailCompletionRepository.SyncAccountRow account = repository
                    .lockSyncAccount(fixture.tenantId(), fixture.accountId()).orElseThrow();
            UUID folderId = repository.synchronizationFolder(
                    fixture.tenantId(), fixture.accountId(), "provider-inbox").orElseThrow();
            AdminMailCompletionRepository.InboundMaterialized replay =
                    repository.materializeInboundMessage(
                            fixture.tenantId(), fixture.ownerUserId(), account,
                            folderId, inbound, List.of(blocked));
            assertThat(repository.updateAccountSyncCursor(
                    fixture.tenantId(), fixture.accountId(), "cursor-1",
                    "cursor-2", fixture.ownerUserId())).isOne();
            return replay;
        });

        assertThat(duplicate).isNotNull();
        assertThat(duplicate.inserted()).isFalse();
        assertThat(duplicate.messageId()).isEqualTo(first.messageId());
        assertThat(jdbc.queryForObject("""
                SELECT body_format FROM mail_messages
                 WHERE tenant_id = ? AND message_id = ?
                """, String.class, fixture.tenantId(), first.messageId())).isEqualTo("HTML");
        assertThat(jdbc.queryForObject("""
                SELECT message_count FROM mail_threads
                 WHERE tenant_id = ? AND thread_id = ?
                """, Integer.class, fixture.tenantId(), first.threadId())).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT synchronization_cursor FROM mail_accounts
                 WHERE tenant_id = ? AND account_id = ?
                """, String.class, fixture.tenantId(), fixture.accountId()))
                .isEqualTo("cursor-2");
        assertThat(jdbc.queryForObject("""
                SELECT scan_state FROM mail_compose_attachments
                 WHERE tenant_id = ? AND attachment_id = ?
                """, String.class, fixture.tenantId(), attachmentId)).isEqualTo("BLOCKED");
        MailWorkspaceRepository workspace = new MailWorkspaceRepository(jdbc, json);
        assertThat(workspace.visibleAttachment(
                fixture.tenantId(), fixture.ownerUserId(), first.threadId(),
                first.messageId(), attachmentId)).isEmpty();

        String rolledBackMessage = "external:message:" + UUID.randomUUID();
        String rolledBackThread = "external:thread:" + UUID.randomUUID();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            AdminMailCompletionRepository.SyncAccountRow account = repository
                    .lockSyncAccount(fixture.tenantId(), fixture.accountId()).orElseThrow();
            UUID folderId = repository.synchronizationFolder(
                    fixture.tenantId(), fixture.accountId(), "provider-inbox").orElseThrow();
            repository.materializeInboundMessage(
                    fixture.tenantId(), fixture.ownerUserId(), account, folderId,
                    inbound(rolledBackMessage, rolledBackThread, List.of()), List.of());
            assertThat(repository.updateAccountSyncCursor(
                    fixture.tenantId(), fixture.accountId(), "cursor-2",
                    "cursor-rollback", fixture.ownerUserId())).isOne();
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("force rollback");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM mail_messages WHERE provider_message_ref = ?
                """, Integer.class, rolledBackMessage)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT synchronization_cursor FROM mail_accounts
                 WHERE tenant_id = ? AND account_id = ?
                """, String.class, fixture.tenantId(), fixture.accountId()))
                .isEqualTo("cursor-2");
    }

    private AdminMailCompletionRepository.InboundMessageRow inbound(
            String messageReference,
            String threadReference,
            List<Map<String, Object>> attachments) {
        OffsetDateTime occurredAt = OffsetDateTime.of(
                2026, 9, 17, 1, 2, 3, 0, ZoneOffset.UTC);
        List<Map<String, Object>> recipients = List.of(Map.of(
                "type", "TO", "name", "Inbox", "email", "inbox@sk.com"));
        return new AdminMailCompletionRepository.InboundMessageRow(
                messageReference, threadReference, occurredAt,
                "sender@outside.test", "Provider Sender", recipients,
                "Provider subject", "<p>Provider <strong>HTML</strong></p>",
                "HTML", "Provider HTML", recipients, true,
                "CONFIDENTIAL", attachments);
    }

    private SyncFixture fixture(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT account.tenant_id, account.account_id, account.owner_user_id,
                       account.synchronization_cursor
                  FROM mail_accounts account
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                   AND connection.provider_type = 'DWP_SANDBOX'
                  JOIN mail_folders folder
                    ON folder.tenant_id = account.tenant_id
                   AND folder.account_id = account.account_id
                   AND folder.folder_type = 'INBOX'
                   AND folder.lifecycle_state = 'ACTIVE'
                 WHERE account.account_kind = 'PERSONAL'
                   AND account.connection_state = 'ACTIVE'
                 ORDER BY account.tenant_id, account.account_id
                 LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Mail account fixture missing");
            return new SyncFixture(
                    result.getLong("tenant_id"),
                    result.getObject("account_id", UUID.class),
                    result.getLong("owner_user_id"),
                    result.getString("synchronization_cursor"));
        });
    }

    private void migrate(String schema) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource())
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("282")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    private PGSimpleDataSource dataSource(String schema) {
        PGSimpleDataSource source = dataSource();
        source.setCurrentSchema(schema);
        return source;
    }

    private record SyncFixture(
            long tenantId,
            UUID accountId,
            long ownerUserId,
            String cursor) { }
}
