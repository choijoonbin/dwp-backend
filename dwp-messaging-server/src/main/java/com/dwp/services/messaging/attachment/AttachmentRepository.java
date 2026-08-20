package com.dwp.services.messaging.attachment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AttachmentRepository {

    private final JdbcTemplate jdbc;

    public AttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean activeMember(long tenantId, UUID conversationId, long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM msg_conversation_members
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class, tenantId, conversationId, userId);
        return count != null && count == 1;
    }

    public int maximumAttachmentMb(long tenantId) {
        List<Integer> values = jdbc.query("""
                SELECT maximum_attachment_mb FROM msg_tenant_policies WHERE tenant_id = ?
                """, (row, ignored) -> row.getInt(1), tenantId);
        return values.isEmpty() ? 100 : values.getFirst();
    }

    public AttachmentRow createOrReplay(
            long tenantId,
            UUID conversationId,
            long userId,
            UUID attachmentId,
            AttachmentSecurity.ValidatedMetadata metadata,
            UUID idempotencyKey,
            String requestHash,
            String objectKey,
            String uploadTokenHash,
            OffsetDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO msg_attachments (
                    attachment_id, tenant_id, conversation_id, uploader_user_id,
                    original_filename, normalized_filename, file_extension,
                    declared_content_type, size_bytes, object_key,
                    idempotency_key, request_hash, upload_token_hash, upload_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, uploader_user_id, conversation_id, idempotency_key)
                DO NOTHING
                """, attachmentId, tenantId, conversationId, userId,
                metadata.originalFilename(), metadata.normalizedFilename(), metadata.extension(),
                metadata.contentType(), metadata.sizeBytes(), objectKey,
                idempotencyKey, requestHash, uploadTokenHash, expiresAt);
        AttachmentRow row = byIdempotency(tenantId, conversationId, userId, idempotencyKey)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INTERNAL_SERVER_ERROR, "The upload session could not be reserved."));
        if (!requestHash.equals(row.requestHash())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key was already used for a different attachment.");
        }
        if ("QUARANTINED".equals(row.status())) {
            jdbc.update("""
                    UPDATE msg_attachments
                       SET upload_token_hash = ?, upload_expires_at = ?,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE attachment_id = ? AND tenant_id = ? AND status = 'QUARANTINED'
                    """, uploadTokenHash, expiresAt, row.attachmentId(), tenantId);
            return findOwned(tenantId, conversationId, userId, row.attachmentId()).orElseThrow();
        }
        return row;
    }

    public Optional<AttachmentRow> findOwned(
            long tenantId, UUID conversationId, long userId, UUID attachmentId) {
        return rows("""
                SELECT * FROM msg_attachments
                 WHERE tenant_id = ? AND conversation_id = ? AND uploader_user_id = ?
                   AND attachment_id = ?
                """, tenantId, conversationId, userId, attachmentId).stream().findFirst();
    }

    public Optional<AttachmentRow> findVisible(
            long tenantId, UUID conversationId, long userId, UUID attachmentId) {
        return rows("""
                SELECT attachment.* FROM msg_attachments attachment
                  JOIN msg_conversation_members member
                    ON member.tenant_id = attachment.tenant_id
                   AND member.conversation_id = attachment.conversation_id
                   AND member.user_id = ? AND member.lifecycle_state = 'ACTIVE'
                 WHERE attachment.tenant_id = ? AND attachment.conversation_id = ?
                   AND attachment.attachment_id = ?
                """, userId, tenantId, conversationId, attachmentId).stream().findFirst();
    }

    public Optional<AttachmentRow> beginScan(
            long tenantId,
            UUID conversationId,
            long userId,
            UUID attachmentId,
            String uploadTokenHash,
            String contentHash,
            long actualSize,
            long expectedVersion) {
        return rows("""
                UPDATE msg_attachments
                   SET status = 'SCANNING', content_sha256 = ?, uploaded_at = CURRENT_TIMESTAMP,
                       scan_started_at = CURRENT_TIMESTAMP, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND conversation_id = ? AND uploader_user_id = ?
                   AND attachment_id = ? AND upload_token_hash = ?
                   AND status = 'QUARANTINED' AND upload_expires_at > CURRENT_TIMESTAMP
                   AND size_bytes = ? AND version = ?
                RETURNING *
                """, contentHash, tenantId, conversationId, userId, attachmentId,
                uploadTokenHash, actualSize, expectedVersion).stream().findFirst();
    }

    public AttachmentRow completeScan(
            long tenantId,
            UUID attachmentId,
            long expectedVersion,
            AttachmentScanner.ScanResult result) {
        List<AttachmentRow> rows = rows("""
                UPDATE msg_attachments
                   SET status = ?, detected_content_type = ?, rejection_reason = ?,
                       scan_completed_at = CURRENT_TIMESTAMP, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND attachment_id = ?
                   AND status = 'SCANNING' AND version = ?
                RETURNING *
                """, result.clean() ? "CLEAN" : "REJECTED", result.detectedContentType(),
                result.reason(), tenantId, attachmentId, expectedVersion);
        if (rows.isEmpty()) throw conflict("The attachment scan state changed.");
        return rows.getFirst();
    }

    public void expirePending(long tenantId, UUID conversationId, long userId) {
        jdbc.update("""
                UPDATE msg_attachments
                   SET status = 'EXPIRED', version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND conversation_id = ? AND uploader_user_id = ?
                   AND status = 'QUARANTINED' AND upload_expires_at <= CURRENT_TIMESTAMP
                """, tenantId, conversationId, userId);
    }

    public Optional<AttachmentRow> expireOwnedUnattached(
            long tenantId, UUID conversationId, long userId, UUID attachmentId) {
        return rows("""
                UPDATE msg_attachments
                   SET status = 'EXPIRED', version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND conversation_id = ? AND uploader_user_id = ?
                   AND attachment_id = ? AND message_id IS NULL
                   AND status IN ('QUARANTINED', 'CLEAN', 'REJECTED')
                RETURNING *
                """, tenantId, conversationId, userId, attachmentId).stream().findFirst();
    }

    public void expireDueOrphans() {
        jdbc.update("""
                UPDATE msg_attachments
                   SET status = 'EXPIRED', version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE message_id IS NULL AND upload_expires_at <= CURRENT_TIMESTAMP
                   AND status IN ('QUARANTINED', 'CLEAN', 'REJECTED')
                """);
    }

    public List<AttachmentRow> expiredOrphans(int limit) {
        return rows("""
                SELECT * FROM msg_attachments
                 WHERE message_id IS NULL AND status = 'EXPIRED'
                 ORDER BY upload_expires_at, attachment_id
                 LIMIT ?
                """, Math.max(1, Math.min(limit, 500)));
    }

    public int deleteExpired(UUID attachmentId) {
        return jdbc.update("""
                DELETE FROM msg_attachments
                 WHERE attachment_id = ? AND message_id IS NULL AND status = 'EXPIRED'
                """, attachmentId);
    }

    public void purgeDownloadGrants() {
        jdbc.update("""
                DELETE FROM msg_attachment_download_grants
                 WHERE expires_at <= CURRENT_TIMESTAMP
                    OR consumed_at <= CURRENT_TIMESTAMP - INTERVAL '1 day'
                """);
    }

    public void requireAttachable(
            long tenantId, UUID conversationId, long userId, List<UUID> attachmentIds) {
        if (attachmentIds.isEmpty()) return;
        List<AttachmentRow> rows = lockAttachments(tenantId, conversationId, attachmentIds);
        if (rows.size() != attachmentIds.size()) throw notFound();
        for (AttachmentRow row : rows) {
            if (row.uploaderUserId() != userId || !"CLEAN".equals(row.status())
                    || row.messageId() != null) {
                throw new BaseException(
                        ErrorCode.INVALID_STATE,
                        "Only the uploader can attach an unused, clean upload session.");
            }
        }
    }

    public void attachToMessage(
            long tenantId,
            UUID conversationId,
            long userId,
            UUID messageId,
            List<UUID> attachmentIds) {
        requireAttachable(tenantId, conversationId, userId, attachmentIds);
        for (UUID attachmentId : attachmentIds) {
            int updated = jdbc.update("""
                    UPDATE msg_attachments
                       SET message_id = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ? AND conversation_id = ? AND uploader_user_id = ?
                       AND attachment_id = ? AND status = 'CLEAN' AND message_id IS NULL
                    """, messageId, tenantId, conversationId, userId, attachmentId);
            if (updated != 1) throw conflict("The attachment was already used.");
        }
    }

    public Map<UUID, List<AttachmentDtos.AttachmentSummary>> cleanForMessages(
            long tenantId, List<UUID> messageIds) {
        if (messageIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", messageIds.stream().map(value -> "?").toList());
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId);
        arguments.addAll(messageIds);
        return jdbc.query("""
                SELECT * FROM msg_attachments
                 WHERE tenant_id = ? AND status = 'CLEAN' AND message_id IN (
                """ + placeholders + ") ORDER BY created_at", result -> {
            Map<UUID, List<AttachmentDtos.AttachmentSummary>> grouped = new LinkedHashMap<>();
            while (result.next()) {
                AttachmentRow row = map(result);
                grouped.computeIfAbsent(row.messageId(), ignored -> new ArrayList<>()).add(row.summary());
            }
            return grouped;
        }, arguments.toArray());
    }

    public void createDownloadGrant(
            UUID grantId,
            UUID attachmentId,
            long tenantId,
            long userId,
            String tokenHash,
            OffsetDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO msg_attachment_download_grants (
                    grant_id, attachment_id, tenant_id, user_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, grantId, attachmentId, tenantId, userId, tokenHash, expiresAt);
    }

    public Optional<AttachmentRow> consumeDownloadGrant(
            long tenantId,
            UUID conversationId,
            long userId,
            UUID attachmentId,
            String tokenHash) {
        return rows("""
                UPDATE msg_attachment_download_grants download_grant
                   SET consumed_at = CURRENT_TIMESTAMP
                  FROM msg_attachments attachment, msg_conversation_members member
                 WHERE download_grant.attachment_id = attachment.attachment_id
                   AND attachment.tenant_id = member.tenant_id
                   AND attachment.conversation_id = member.conversation_id
                   AND member.user_id = download_grant.user_id AND member.lifecycle_state = 'ACTIVE'
                   AND download_grant.tenant_id = ? AND download_grant.user_id = ?
                   AND download_grant.token_hash = ?
                   AND download_grant.attachment_id = ? AND download_grant.consumed_at IS NULL
                   AND download_grant.expires_at > CURRENT_TIMESTAMP
                   AND attachment.tenant_id = ? AND attachment.conversation_id = ?
                   AND attachment.status = 'CLEAN' AND attachment.message_id IS NOT NULL
                RETURNING attachment.*
                """, tenantId, userId, tokenHash, attachmentId,
                tenantId, conversationId).stream().findFirst();
    }

    public void audit(
            long tenantId, long userId, String eventType, UUID attachmentId, String correlationId) {
        jdbc.update("""
                INSERT INTO msg_audit_events (
                    tenant_id, actor_user_id, event_type, object_type, object_id, correlation_id)
                VALUES (?, ?, ?, 'MSG_ATTACHMENT', ?, ?)
                """, tenantId, userId, eventType, attachmentId.toString(), correlationId);
    }

    private Optional<AttachmentRow> byIdempotency(
            long tenantId, UUID conversationId, long userId, UUID key) {
        return rows("""
                SELECT * FROM msg_attachments
                 WHERE tenant_id = ? AND conversation_id = ? AND uploader_user_id = ?
                   AND idempotency_key = ?
                """, tenantId, conversationId, userId, key).stream().findFirst();
    }

    private List<AttachmentRow> lockAttachments(
            long tenantId, UUID conversationId, List<UUID> attachmentIds) {
        String placeholders = String.join(",", attachmentIds.stream().map(value -> "?").toList());
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId);
        arguments.add(conversationId);
        arguments.addAll(attachmentIds);
        return rows("""
                SELECT * FROM msg_attachments
                 WHERE tenant_id = ? AND conversation_id = ? AND attachment_id IN (
                """ + placeholders + ") FOR UPDATE", arguments.toArray());
    }

    private List<AttachmentRow> rows(String sql, Object... arguments) {
        return jdbc.query(sql, (row, ignored) -> map(row), arguments);
    }

    private AttachmentRow map(ResultSet row) throws SQLException {
        return new AttachmentRow(
                row.getObject("attachment_id", UUID.class), row.getLong("tenant_id"),
                row.getObject("conversation_id", UUID.class), row.getLong("uploader_user_id"),
                row.getObject("message_id", UUID.class), row.getString("original_filename"),
                row.getString("normalized_filename"), row.getString("file_extension"),
                row.getString("declared_content_type"), row.getString("detected_content_type"),
                row.getLong("size_bytes"), row.getString("object_key"), row.getString("content_sha256"),
                row.getString("status"), row.getString("rejection_reason"),
                row.getString("request_hash"), row.getString("upload_token_hash"),
                row.getObject("upload_expires_at", OffsetDateTime.class),
                row.getObject("created_at", OffsetDateTime.class), row.getLong("version"));
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private BaseException notFound() {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, "The attachment was not found.");
    }

    public record AttachmentRow(
            UUID attachmentId, long tenantId, UUID conversationId, long uploaderUserId,
            UUID messageId, String originalFilename, String normalizedFilename,
            String extension, String declaredContentType, String detectedContentType,
            long sizeBytes, String objectKey, String contentSha256, String status, String rejectionReason,
            String requestHash, String uploadTokenHash, OffsetDateTime uploadExpiresAt, OffsetDateTime createdAt,
            long version) {

        public AttachmentDtos.AttachmentSummary summary() {
            return new AttachmentDtos.AttachmentSummary(
                    attachmentId, normalizedFilename,
                    detectedContentType == null ? declaredContentType : detectedContentType,
                    sizeBytes, status, rejectionReason, createdAt, version);
        }
    }
}
