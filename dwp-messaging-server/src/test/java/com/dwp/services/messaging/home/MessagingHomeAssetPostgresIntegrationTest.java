package com.dwp.services.messaging.home;

import com.dwp.services.messaging.attachment.AttachmentRepository;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class MessagingHomeAssetPostgresIntegrationTest {
    private static final long TENANT = 96_400;
    private static final long VIEWER = 100;
    private static JdbcTemplate jdbc;
    private MessagingHomeAssetService service;
    private AttachmentRepository attachments;
    private UUID conversation;
    private UUID message;
    private UUID attachment;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(System.getenv("DWP_MESSAGING_INTEGRATION_DB_URL"),
                System.getenv().getOrDefault("DWP_MESSAGING_INTEGRATION_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("DWP_MESSAGING_INTEGRATION_DB_PASSWORD", "postgres"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM msg_conversations WHERE tenant_id IN (?, ?)", TENANT, TENANT + 1);
        jdbc.update("DELETE FROM msg_people_snapshot WHERE tenant_id IN (?, ?)", TENANT, TENANT + 1);
        jdbc.update("""
                INSERT INTO msg_people_snapshot (tenant_id, user_id, email_address, display_name)
                VALUES (?, ?, 'viewer@home.test', 'Viewer')
                """, TENANT, VIEWER);
        conversation = conversation(TENANT, VIEWER);
        message = message(conversation, TENANT, 1, "[Plan](https://example.com/plan)");
        attachment = attachment(conversation, TENANT, message, "CLEAN");
        attachments = new AttachmentRepository(jdbc);
        service = new MessagingHomeAssetService(new MessagingHomeAssetRepository(jdbc));
        as(TENANT, VIEWER);
    }

    @AfterEach
    void clear() { MessagingRequestContext.clear(); }

    @Test
    void exposesActualCleanFilesAndSafeLinksWithoutStorageOrDownloadCredentials() {
        var items = service.recent(6).items();
        assertThat(items).hasSize(2);
        var file = items.stream().filter(item -> item.kind() == MessagingHomeDtos.AssetKind.FILE).findFirst().orElseThrow();
        assertThat(file.attachmentId()).isEqualTo(attachment);
        assertThat(file.title()).isEqualTo("plan.pdf");
        assertThat(file.url()).isNull();
        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertThat(file.sizeBytes()).isEqualTo(128);
        assertThat(file.messageId()).isEqualTo(message);
        assertThat(items.stream().filter(item -> item.kind() == MessagingHomeDtos.AssetKind.LINK)
                .map(MessagingHomeDtos.SharedAsset::url)).containsExactly("https://example.com/plan");
    }

    @Test
    void tenantAndMembershipScopesCannotLeakAssets() {
        UUID other = conversation(TENANT + 1, VIEWER);
        UUID foreign = message(other, TENANT + 1, 1, "https://private.example/tenant");
        attachment(other, TENANT + 1, foreign, "CLEAN");
        assertThat(service.recent(20).items()).allSatisfy(item -> assertThat(item.conversationId()).isEqualTo(conversation));
        as(TENANT, 999);
        assertThat(service.recent(20).items()).isEmpty();
        as(TENANT + 1, VIEWER);
        assertThat(service.recent(20).items()).isEmpty();
        as(TENANT, VIEWER);
        jdbc.update("UPDATE msg_conversation_members SET lifecycle_state = 'REVOKED' WHERE conversation_id = ?", conversation);
        assertThat(service.recent(20).items()).isEmpty();
    }

    @Test
    void historyBoundaryAppliesToFilesLinksMetadataAndDownloadGrantConsumption() {
        String token = grant();
        jdbc.update("""
                UPDATE msg_conversation_members SET history_start_sequence = 2, last_read_sequence = 1
                 WHERE conversation_id = ?
                """, conversation);
        assertThat(service.recent(6).items()).isEmpty();
        assertThat(attachments.findVisible(TENANT, conversation, VIEWER, attachment)).isEmpty();
        assertThat(attachments.consumeDownloadGrant(TENANT, conversation, VIEWER, attachment, token)).isEmpty();
    }

    @Test
    void deletionAfterGrantRevokesMetadataAndDownloadWithoutConsumingGrant() {
        String token = grant();
        jdbc.update("UPDATE msg_messages SET deleted_at = CURRENT_TIMESTAMP WHERE message_id = ?", message);
        assertThat(service.recent(6).items()).isEmpty();
        assertThat(attachments.findVisible(TENANT, conversation, VIEWER, attachment)).isEmpty();
        assertThat(attachments.consumeDownloadGrant(TENANT, conversation, VIEWER, attachment, token)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM msg_attachment_download_grants WHERE token_hash = ?",
                Boolean.class, token)).isTrue();
    }

    @Test
    void archiveAndDisabledViewerHideAssetsAndInvalidateOutstandingGrant() {
        String token = grant();
        jdbc.update("UPDATE msg_conversations SET lifecycle_state = 'ARCHIVED' WHERE conversation_id = ?", conversation);
        assertThat(service.recent(6).items()).isEmpty();
        assertThat(attachments.activeMember(TENANT, conversation, VIEWER)).isFalse();
        assertThat(attachments.findVisible(TENANT, conversation, VIEWER, attachment)).isEmpty();
        assertThat(attachments.consumeDownloadGrant(TENANT, conversation, VIEWER, attachment, token)).isEmpty();
        jdbc.update("UPDATE msg_conversations SET lifecycle_state = 'ACTIVE' WHERE conversation_id = ?", conversation);
        jdbc.update("UPDATE msg_people_snapshot SET lifecycle_state = 'INACTIVE' WHERE tenant_id = ?", TENANT);
        assertThat(service.recent(6).items()).isEmpty();
        assertThat(attachments.activeMember(TENANT, conversation, VIEWER)).isFalse();
        assertThat(attachments.consumeDownloadGrant(TENANT, conversation, VIEWER, attachment, token)).isEmpty();
    }

    @Test
    void quarantineRejectedOrphanAndSystemAssetsNeverAppear() {
        for (String status : List.of("QUARANTINED", "SCANNING", "REJECTED", "EXPIRED", "CLEAN")) {
            attachment(conversation, TENANT, null, status);
        }
        UUID system = message(conversation, TENANT, 2, "https://internal.example/system");
        jdbc.update("UPDATE msg_messages SET message_kind = 'SYSTEM' WHERE message_id = ?", system);
        attachment(conversation, TENANT, system, "CLEAN");
        assertThat(service.recent(20).items()).hasSize(2);
    }

    @Test
    void realDownloadRemainsSingleUseAndBoundToCorrectTenantAndUser() {
        String token = grant();
        assertThat(attachments.findVisible(TENANT, conversation, VIEWER, attachment)).isPresent();
        assertThat(attachments.consumeDownloadGrant(TENANT + 1, conversation, VIEWER, attachment, token)).isEmpty();
        assertThat(attachments.consumeDownloadGrant(TENANT, conversation, 999, attachment, token)).isEmpty();
        assertThat(attachments.consumeDownloadGrant(TENANT, conversation, VIEWER, attachment, token)).isPresent();
        assertThat(attachments.consumeDownloadGrant(TENANT, conversation, VIEWER, attachment, token)).isEmpty();
    }

    @Test
    void recentOrderingAndGlobalLimitAreDeterministicAcrossKinds() {
        UUID next = message(conversation, TENANT, 2, "https://example.org/latest https://example.org/latest");
        jdbc.update("UPDATE msg_messages SET created_at = '2026-09-04T10:00:00Z' WHERE message_id = ?", next);
        assertThat(service.recent(1).items()).singleElement()
                .satisfies(item -> assertThat(item.messageId()).isEqualTo(next));
        assertThat(service.recent(20).items()).hasSize(3);
        assertThat(service.recent(20).items()).isEqualTo(service.recent(20).items());
    }

    private String grant() {
        String token = "b".repeat(64);
        attachments.createDownloadGrant(UUID.randomUUID(), attachment, TENANT, VIEWER, token,
                OffsetDateTime.now().plusMinutes(2));
        return token;
    }

    private UUID conversation(long tenantId, long userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_conversations (conversation_id, tenant_id, conversation_key, conversation_type,
                    name, visibility, data_classification) VALUES (?, ?, ?, 'GROUP', 'Home team', 'PRIVATE', 'INTERNAL')
                """, id, tenantId, "home:" + id);
        jdbc.update("INSERT INTO msg_conversation_members (tenant_id, conversation_id, user_id) VALUES (?, ?, ?)",
                tenantId, id, userId);
        return id;
    }

    private UUID message(UUID conversationId, long tenantId, int sequence, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_messages (message_id, tenant_id, conversation_id, sequence, sender_user_id,
                    sender_name, body, created_at) VALUES (?, ?, ?, ?, 200, 'Sender', ?, '2026-09-01T00:00:00Z')
                """, id, tenantId, conversationId, sequence, body);
        return id;
    }

    private UUID attachment(UUID conversationId, long tenantId, UUID messageId, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_attachments (attachment_id, tenant_id, conversation_id, uploader_user_id, message_id,
                    original_filename, normalized_filename, file_extension, declared_content_type, detected_content_type,
                    size_bytes, object_key, status, idempotency_key, request_hash, upload_token_hash, upload_expires_at)
                VALUES (?, ?, ?, 100, ?, 'plan.pdf', 'plan.pdf', 'pdf', 'application/octet-stream', 'application/pdf',
                    128, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                """, id, tenantId, conversationId, messageId, "private/object/" + id, status,
                UUID.randomUUID(), "a".repeat(64), "a".repeat(64));
        return id;
    }

    private void as(long tenantId, long userId) {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(userId, tenantId, null, "Viewer", Set.of(),
                Set.of("APP.MESSAGING:VIEW"), Set.of()));
    }
}
