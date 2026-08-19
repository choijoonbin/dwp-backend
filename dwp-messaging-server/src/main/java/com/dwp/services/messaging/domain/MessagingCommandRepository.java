package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class MessagingCommandRepository {

    private static final String SEND_MESSAGE_OPERATION = "SEND_MESSAGE";

    private final JdbcTemplate jdbc;

    MessagingCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID directConversation(long tenantId, long currentUserId, long targetUserId) {
        return jdbc.queryForObject("""
                WITH seed AS (
                    SELECT LEAST(?::BIGINT, ?::BIGINT) AS left_user_id,
                           GREATEST(?::BIGINT, ?::BIGINT) AS right_user_id
                ), upsert_conversation AS (
                    INSERT INTO msg_conversations (
                        conversation_id, tenant_id, conversation_key, conversation_type,
                        name, topic, visibility, data_classification, lifecycle_state,
                        created_by, updated_by)
                    SELECT md5('msg:dm:' || ? || ':' || left_user_id || ':' || right_user_id)::uuid,
                           ?, 'dm:' || left_user_id || ':' || right_user_id,
                           'DIRECT',
                           left_person.display_name || ' / ' || right_person.display_name,
                           '1:1 업무 대화', 'PRIVATE', 'INTERNAL', 'ACTIVE',
                           ?, ?
                      FROM seed
                      JOIN msg_people_snapshot left_person
                        ON left_person.tenant_id = ?
                       AND left_person.user_id = seed.left_user_id
                      JOIN msg_people_snapshot right_person
                        ON right_person.tenant_id = ?
                       AND right_person.user_id = seed.right_user_id
                    ON CONFLICT (tenant_id, conversation_key) DO UPDATE SET
                        lifecycle_state = 'ACTIVE',
                        updated_at = CURRENT_TIMESTAMP,
                        updated_by = EXCLUDED.updated_by
                    RETURNING conversation_id
                ), conversation AS (
                    SELECT conversation_id FROM upsert_conversation
                    UNION ALL
                    SELECT existing.conversation_id
                      FROM msg_conversations existing
                      JOIN seed ON existing.tenant_id = ?
                       AND existing.conversation_key =
                           'dm:' || seed.left_user_id || ':' || seed.right_user_id
                     LIMIT 1
                ), members AS (
                    SELECT conversation.conversation_id, seed.left_user_id AS user_id
                      FROM conversation CROSS JOIN seed
                    UNION ALL
                    SELECT conversation.conversation_id, seed.right_user_id AS user_id
                      FROM conversation CROSS JOIN seed
                ), upsert_members AS (
                INSERT INTO msg_conversation_members (
                    tenant_id, conversation_id, user_id, person_public_id, member_role,
                    membership_source, notification_level, lifecycle_state,
                    created_by, updated_by)
                SELECT ?, members.conversation_id, members.user_id, person.person_public_id,
                       'MEMBER', 'DIRECT', 'DEFAULT', 'ACTIVE', ?, ?
                  FROM members
                  JOIN msg_people_snapshot person
                    ON person.tenant_id = ?
                   AND person.user_id = members.user_id
                ON CONFLICT (tenant_id, conversation_id, user_id) DO UPDATE SET
                    lifecycle_state = 'ACTIVE',
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                RETURNING 1
                )
                SELECT conversation_id FROM conversation
                """, UUID.class,
                currentUserId, targetUserId, currentUserId, targetUserId,
                tenantId, tenantId, currentUserId, currentUserId, tenantId, tenantId, tenantId,
                tenantId, currentUserId, currentUserId, tenantId);
    }

    MessageInsertResult insertMessage(
            long tenantId,
            long userId,
            UUID conversationId,
            UUID idempotencyKey,
            String senderName,
            UUID senderPersonPublicId,
            String body,
            UUID replyToMessageId) {
        return insertMessage(
                tenantId, userId, conversationId, idempotencyKey, senderName,
                senderPersonPublicId, body, replyToMessageId, List.of());
    }

    MessageInsertResult insertMessage(
            long tenantId,
            long userId,
            UUID conversationId,
            UUID idempotencyKey,
            String senderName,
            UUID senderPersonPublicId,
            String body,
            UUID replyToMessageId,
            List<UUID> attachmentIds) {
        String normalizedBody = normalizeBody(body);
        String requestHash = sendMessageRequestHash(
                conversationId, normalizedBody, replyToMessageId, attachmentIds);
        jdbc.update("""
                INSERT INTO msg_idempotency_keys (
                    tenant_id, user_id, operation, idempotency_key, request_hash)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id, operation, idempotency_key) DO NOTHING
                """, tenantId, userId, SEND_MESSAGE_OPERATION, idempotencyKey, requestHash);

        IdempotencyEntry entry = jdbc.queryForObject("""
                SELECT request_hash, result_message_id
                  FROM msg_idempotency_keys
                 WHERE tenant_id = ? AND user_id = ? AND operation = ? AND idempotency_key = ?
                 FOR UPDATE
                """, (row, ignored) -> new IdempotencyEntry(
                        row.getString("request_hash").trim(),
                        row.getObject("result_message_id", UUID.class)),
                tenantId, userId, SEND_MESSAGE_OPERATION, idempotencyKey);
        if (entry == null) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "The idempotency ledger could not be reserved.");
        }
        if (!requestHash.equals(entry.requestHash())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key was already used with a different message request.");
        }
        if (entry.resultMessageId() != null) {
            return existingMessage(tenantId, conversationId, entry.resultMessageId());
        }

        long sequence = allocateMessageSequence(tenantId, conversationId);
        UUID messageId = UUID.randomUUID();
        OffsetDateTime createdAt = jdbc.queryForObject("""
                INSERT INTO msg_messages (
                    message_id, tenant_id, conversation_id, sequence, sender_user_id,
                    sender_person_public_id, sender_name, body, content_type,
                    message_kind, reply_to_message_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'TEXT', 'USER', ?)
                RETURNING created_at
                """, OffsetDateTime.class,
                messageId, tenantId, conversationId, sequence, userId, senderPersonPublicId,
                senderName, normalizedBody, replyToMessageId);
        if (createdAt == null) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "The message creation result was incomplete.");
        }
        int completed = jdbc.update("""
                UPDATE msg_idempotency_keys
                   SET result_message_id = ?, completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_id = ? AND operation = ? AND idempotency_key = ?
                   AND request_hash = ? AND result_message_id IS NULL
                """, messageId, tenantId, userId, SEND_MESSAGE_OPERATION, idempotencyKey, requestHash);
        if (completed != 1) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "The idempotency result could not be completed.");
        }
        int touched = jdbc.update("""
                UPDATE msg_conversations
                   SET last_message_id = ?, last_message_at = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND conversation_id = ?
                """, messageId, createdAt, userId, tenantId, conversationId);
        if (touched != 1) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "The conversation was not found.");
        }
        return new MessageInsertResult(messageId, true, sequence);
    }

    Optional<MessageInsertResult> replayMessage(
            long tenantId,
            long userId,
            UUID conversationId,
            UUID idempotencyKey,
            String body,
            UUID replyToMessageId) {
        return replayMessage(
                tenantId, userId, conversationId, idempotencyKey, body,
                replyToMessageId, List.of());
    }

    Optional<MessageInsertResult> replayMessage(
            long tenantId,
            long userId,
            UUID conversationId,
            UUID idempotencyKey,
            String body,
            UUID replyToMessageId,
            List<UUID> attachmentIds) {
        String normalizedBody = normalizeBody(body);
        String requestHash = sendMessageRequestHash(
                conversationId, normalizedBody, replyToMessageId, attachmentIds);
        List<IdempotencyEntry> entries = jdbc.query("""
                SELECT request_hash, result_message_id
                  FROM msg_idempotency_keys
                 WHERE tenant_id = ? AND user_id = ? AND operation = ? AND idempotency_key = ?
                """, (row, ignored) -> new IdempotencyEntry(
                row.getString("request_hash").trim(),
                row.getObject("result_message_id", UUID.class)),
                tenantId, userId, SEND_MESSAGE_OPERATION, idempotencyKey);
        if (entries.isEmpty()) return Optional.empty();
        IdempotencyEntry entry = entries.getFirst();
        if (!requestHash.equals(entry.requestHash())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key was already used with a different message request.");
        }
        if (entry.resultMessageId() == null) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE, "The idempotency command has no completed result.");
        }
        return Optional.of(existingMessage(tenantId, conversationId, entry.resultMessageId()));
    }

    record MessageInsertResult(UUID messageId, boolean created, long sequence) {
    }

    Optional<ReadCursorState> markRead(
            long tenantId, long userId, UUID conversationId, UUID messageId) {
        List<ReadCursorTarget> targets = jdbc.query("""
                SELECT message.message_id, message.sequence
                  FROM msg_messages message
                  JOIN msg_conversation_members member
                    ON member.tenant_id = message.tenant_id
                   AND member.conversation_id = message.conversation_id
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                   AND message.sequence >= member.history_start_sequence
                 WHERE message.tenant_id = ?
                   AND message.conversation_id = ?
                   AND message.message_id = ?
                """, (row, ignored) -> new ReadCursorTarget(
                row.getObject("message_id", UUID.class),
                row.getLong("sequence")),
                userId, tenantId, conversationId, messageId);
        if (targets.isEmpty()) return Optional.empty();
        ReadCursorTarget target = targets.getFirst();
        int advanced = jdbc.update("""
                UPDATE msg_conversation_members member
                   SET last_read_message_id = ?,
                       last_read_sequence = ?,
                       last_read_at = CURRENT_TIMESTAMP,
                       version = member.version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE member.tenant_id = ?
                   AND member.user_id = ?
                   AND member.conversation_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                   AND member.last_read_sequence < ?
                """, target.messageId(), target.sequence(), userId,
                tenantId, userId, conversationId, target.sequence());
        ReadCursorState state = jdbc.queryForObject("""
                SELECT last_read_message_id, last_read_sequence, last_read_at, version
                  FROM msg_conversation_members
                 WHERE tenant_id = ? AND user_id = ? AND conversation_id = ?
                   AND lifecycle_state = 'ACTIVE'
                """, (row, ignored) -> new ReadCursorState(
                        target.messageId(),
                        target.sequence(),
                        row.getObject("last_read_message_id", UUID.class),
                        row.getLong("last_read_sequence"),
                        row.getObject("last_read_at", OffsetDateTime.class),
                        row.getLong("version"),
                        advanced == 1),
                tenantId, userId, conversationId);
        return Optional.ofNullable(state);
    }

    record ReadCursorState(
            UUID requestedMessageId,
            long requestedSequence,
            UUID currentMessageId,
            long currentSequence,
            OffsetDateTime currentReadAt,
            long currentVersion,
            boolean advanced) {
    }

    int react(long tenantId, long userId, UUID messageId, String emoji) {
        return jdbc.update("""
                INSERT INTO msg_message_reactions (tenant_id, message_id, user_id, emoji)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, message_id, user_id, emoji) DO NOTHING
                """, tenantId, messageId, userId, emoji.trim());
    }

    int removeReaction(long tenantId, long userId, UUID messageId, String emoji) {
        return jdbc.update("""
                DELETE FROM msg_message_reactions
                 WHERE tenant_id = ? AND message_id = ? AND user_id = ? AND emoji = ?
                """, tenantId, messageId, userId, emoji.trim());
    }

    int updatePolicy(long tenantId, long userId, MessagingDtos.TenantPolicyRequest request) {
        return jdbc.update("""
                UPDATE msg_tenant_policies
                   SET direct_messages_enabled = ?,
                       space_messaging_enabled = ?,
                       allow_message_edit = ?,
                       allow_message_delete = ?,
                       ai_assistance_enabled = ?,
                       ai_auto_execute_enabled = FALSE,
                       retention_days = ?,
                       maximum_attachment_mb = ?,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ? AND version = ?
                """, request.directMessagesEnabled(), request.spaceMessagingEnabled(),
                request.allowMessageEdit(), request.allowMessageDelete(),
                request.aiAssistanceEnabled(), request.retentionDays(),
                request.maximumAttachmentMb(), userId, tenantId, request.version());
    }

    void audit(
            long tenantId,
            long userId,
            String eventType,
            String objectType,
            String objectId,
            String correlationId,
            Map<String, Object> after) {
        jdbc.update("""
                INSERT INTO msg_audit_events (
                    tenant_id, actor_user_id, event_type, object_type, object_id,
                    after_state, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, tenantId, userId, eventType, objectType, objectId,
                json(after), correlationId);
    }

    private String json(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return "{}";
        return value.entrySet().stream()
                .map(entry -> "\"" + escape(entry.getKey()) + "\":\"" + escape(String.valueOf(entry.getValue())) + "\"")
                .reduce((left, right) -> left + "," + right)
                .map(body -> "{" + body + "}")
                .orElse("{}");
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String normalizeBody(String body) {
        String normalizedLineEndings = body.replace("\r\n", "\n").replace('\r', '\n').strip();
        return Normalizer.normalize(normalizedLineEndings, Normalizer.Form.NFC);
    }

    static String sendMessageRequestHash(
            UUID conversationId, String normalizedBody, UUID replyToMessageId) {
        return sendMessageRequestHash(conversationId, normalizedBody, replyToMessageId, List.of());
    }

    static String sendMessageRequestHash(
            UUID conversationId,
            String normalizedBody,
            UUID replyToMessageId,
            List<UUID> attachmentIds) {
        byte[] bodyBytes = normalizedBody.getBytes(StandardCharsets.UTF_8);
        String attachments = (attachmentIds == null ? List.<UUID>of() : attachmentIds).stream()
                .sorted()
                .map(UUID::toString)
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String canonical = "conversation=" + conversationId
                + "\nreply=" + (replyToMessageId == null ? "" : replyToMessageId)
                + "\nattachments=" + attachments
                + "\nbody-bytes=" + bodyBytes.length
                + "\nbody=" + normalizedBody;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    private MessageInsertResult existingMessage(
            long tenantId, UUID conversationId, UUID messageId) {
        List<MessageInsertResult> messages = jdbc.query("""
                SELECT message_id, sequence
                  FROM msg_messages
                 WHERE tenant_id = ? AND conversation_id = ? AND message_id = ?
                """, (row, ignored) -> new MessageInsertResult(
                row.getObject("message_id", UUID.class), false, row.getLong("sequence")),
                tenantId, conversationId, messageId);
        if (messages.isEmpty()) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key belongs to a message outside this conversation.");
        }
        return messages.getFirst();
    }

    private long allocateMessageSequence(long tenantId, UUID conversationId) {
        jdbc.update("""
                INSERT INTO msg_conversation_sequences (tenant_id, conversation_id, next_sequence)
                SELECT conversation.tenant_id, conversation.conversation_id,
                       COALESCE(MAX(message.sequence), 0) + 1
                  FROM msg_conversations conversation
                  LEFT JOIN msg_messages message
                    ON message.tenant_id = conversation.tenant_id
                   AND message.conversation_id = conversation.conversation_id
                 WHERE conversation.tenant_id = ? AND conversation.conversation_id = ?
                 GROUP BY conversation.tenant_id, conversation.conversation_id
                ON CONFLICT (tenant_id, conversation_id) DO NOTHING
                """, tenantId, conversationId);
        Long allocated = jdbc.queryForObject("""
                UPDATE msg_conversation_sequences
                   SET next_sequence = next_sequence + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND conversation_id = ?
                RETURNING next_sequence - 1
                """, Long.class, tenantId, conversationId);
        if (allocated == null) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "The conversation was not found.");
        }
        return allocated;
    }

    private record IdempotencyEntry(String requestHash, UUID resultMessageId) {
    }

    private record ReadCursorTarget(UUID messageId, long sequence) {
    }
}
