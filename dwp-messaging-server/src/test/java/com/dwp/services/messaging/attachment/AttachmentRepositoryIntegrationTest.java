package com.dwp.services.messaging.attachment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class AttachmentRepositoryIntegrationTest {

    private static final long TENANT_ID = 9911;
    private static final long USER_ID = 7001;
    private static final long OTHER_USER_ID = 7002;

    private static JdbcTemplate jdbc;
    private static AttachmentRepository repository;

    @BeforeAll
    static void migrateDatabase() {
        String url = System.getenv("DWP_MESSAGING_INTEGRATION_DB_URL");
        String username = System.getenv().getOrDefault(
                "DWP_MESSAGING_INTEGRATION_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault(
                "DWP_MESSAGING_INTEGRATION_DB_PASSWORD", "postgres");
        Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        repository = new AttachmentRepository(jdbc);
        jdbc.update("""
                INSERT INTO msg_tenant_policies (tenant_id, maximum_attachment_mb)
                VALUES (?, 25) ON CONFLICT (tenant_id) DO NOTHING
                """, TENANT_ID);
    }

    @Test
    void idempotentUploadConvergesAndChangedMetadataConflicts() {
        UUID conversationId = conversation("attachment-idempotency");
        UUID idempotencyKey = UUID.randomUUID();
        AttachmentSecurity.ValidatedMetadata metadata = AttachmentSecurity.validate(
                "report.pdf", "application/pdf", 100, 25);
        String requestHash = AttachmentSecurity.requestHash(conversationId, metadata);

        AttachmentRepository.AttachmentRow first = repository.createOrReplay(
                TENANT_ID, conversationId, USER_ID, UUID.randomUUID(), metadata,
                idempotencyKey, requestHash, TENANT_ID + "/" + UUID.randomUUID(),
                "a".repeat(64), OffsetDateTime.now().plusMinutes(10));
        AttachmentRepository.AttachmentRow replay = repository.createOrReplay(
                TENANT_ID, conversationId, USER_ID, UUID.randomUUID(), metadata,
                idempotencyKey, requestHash, TENANT_ID + "/" + UUID.randomUUID(),
                "b".repeat(64), OffsetDateTime.now().plusMinutes(10));

        assertThat(replay.attachmentId()).isEqualTo(first.attachmentId());
        AttachmentSecurity.ValidatedMetadata changed = AttachmentSecurity.validate(
                "other.pdf", "application/pdf", 100, 25);
        assertThatThrownBy(() -> repository.createOrReplay(
                TENANT_ID, conversationId, USER_ID, UUID.randomUUID(), changed,
                idempotencyKey, AttachmentSecurity.requestHash(conversationId, changed),
                TENANT_ID + "/" + UUID.randomUUID(), "c".repeat(64),
                OffsetDateTime.now().plusMinutes(10)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void cleanAttachmentCanOnlyBeAttachedByItsUploaderAndCannotBeReused() {
        UUID conversationId = conversation("attachment-message-link");
        UUID messageId = message(conversationId);
        UUID attachmentId = cleanAttachment(conversationId);

        assertThatThrownBy(() -> repository.requireAttachable(
                TENANT_ID, conversationId, OTHER_USER_ID, List.of(attachmentId)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_STATE));

        repository.attachToMessage(
                TENANT_ID, conversationId, USER_ID, messageId, List.of(attachmentId));

        assertThat(repository.cleanForMessages(TENANT_ID, List.of(messageId)).get(messageId))
                .singleElement()
                .extracting(AttachmentDtos.AttachmentSummary::attachmentId)
                .isEqualTo(attachmentId);
        assertThatThrownBy(() -> repository.attachToMessage(
                TENANT_ID, conversationId, USER_ID, messageId, List.of(attachmentId)))
                .isInstanceOf(BaseException.class);
        assertThat(repository.findOwned(
                TENANT_ID + 1, conversationId, USER_ID, attachmentId)).isEmpty();

        String token = AttachmentSecurity.newToken();
        repository.createDownloadGrant(
                UUID.randomUUID(), attachmentId, TENANT_ID, USER_ID,
                AttachmentSecurity.hash(token), OffsetDateTime.now().plusMinutes(1));
        assertThat(repository.consumeDownloadGrant(
                TENANT_ID, conversationId, USER_ID, attachmentId,
                AttachmentSecurity.hash(token))).isPresent();
        assertThat(repository.consumeDownloadGrant(
                TENANT_ID, conversationId, USER_ID, attachmentId,
                AttachmentSecurity.hash(token))).isEmpty();
    }

    private UUID cleanAttachment(UUID conversationId) {
        UUID attachmentId = UUID.randomUUID();
        byte[] content = "%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        AttachmentSecurity.ValidatedMetadata metadata = AttachmentSecurity.validate(
                "report.pdf", "application/pdf", content.length, 25);
        AttachmentRepository.AttachmentRow pending = repository.createOrReplay(
                TENANT_ID, conversationId, USER_ID, attachmentId, metadata, UUID.randomUUID(),
                AttachmentSecurity.requestHash(conversationId, metadata),
                TENANT_ID + "/" + attachmentId, "a".repeat(64),
                OffsetDateTime.now().plusMinutes(10));
        AttachmentRepository.AttachmentRow scanning = repository.beginScan(
                        TENANT_ID, conversationId, USER_ID, attachmentId, "a".repeat(64),
                        AttachmentSecurity.contentHash(content), content.length, pending.version())
                .orElseThrow();
        repository.completeScan(
                TENANT_ID, attachmentId, scanning.version(),
                AttachmentScanner.ScanResult.clean("application/pdf"));
        return attachmentId;
    }

    private UUID conversation(String key) {
        UUID conversationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_conversations (
                    conversation_id, tenant_id, conversation_key, conversation_type,
                    name, visibility, data_classification, lifecycle_state)
                VALUES (?, ?, ?, 'GROUP', 'Attachment test', 'PRIVATE', 'INTERNAL', 'ACTIVE')
                """, conversationId, TENANT_ID, key + ":" + conversationId);
        for (long userId : List.of(USER_ID, OTHER_USER_ID)) {
            jdbc.update("""
                    INSERT INTO msg_conversation_members (
                        tenant_id, conversation_id, user_id, member_role,
                        membership_source, lifecycle_state)
                    VALUES (?, ?, ?, 'MEMBER', 'DIRECT', 'ACTIVE')
                    """, TENANT_ID, conversationId, userId);
        }
        return conversationId;
    }

    private UUID message(UUID conversationId) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_messages (
                    message_id, tenant_id, conversation_id, sequence, sender_user_id,
                    sender_name, body, content_type, message_kind)
                VALUES (?, ?, ?, 1, ?, 'Attachment Tester', 'Attached file', 'TEXT', 'USER')
                """, messageId, TENANT_ID, conversationId, USER_ID);
        return messageId;
    }
}
