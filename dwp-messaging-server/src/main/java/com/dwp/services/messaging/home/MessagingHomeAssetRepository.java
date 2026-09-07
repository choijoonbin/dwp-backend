package com.dwp.services.messaging.home;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
class MessagingHomeAssetRepository {
    private final JdbcTemplate jdbc;

    MessagingHomeAssetRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    List<MessagingHomeDtos.SharedAsset> files(long tenantId, long userId, int limit) {
        return jdbc.query("""
                SELECT attachment.attachment_id, attachment.normalized_filename,
                       COALESCE(attachment.detected_content_type, attachment.declared_content_type) AS content_type,
                       attachment.size_bytes, message.message_id, message.conversation_id,
                       message.created_at, message.sender_name, COALESCE(conversation.name, '') AS conversation_name
                """ + visibleMessages() + """
                  JOIN msg_attachments attachment
                    ON attachment.tenant_id = message.tenant_id
                   AND attachment.conversation_id = message.conversation_id
                   AND attachment.message_id = message.message_id
                   AND attachment.status = 'CLEAN'
                 WHERE message.tenant_id = ? AND member.user_id = ?
                   AND message.deleted_at IS NULL AND message.message_kind = 'USER'
                 ORDER BY message.created_at DESC, attachment.attachment_id
                 LIMIT ?
                """, (row, ignored) -> new MessagingHomeDtos.SharedAsset(
                "file:" + row.getObject("attachment_id", UUID.class), MessagingHomeDtos.AssetKind.FILE,
                row.getObject("conversation_id", UUID.class), row.getString("conversation_name"),
                row.getObject("message_id", UUID.class), row.getObject("created_at", OffsetDateTime.class),
                row.getString("sender_name"), row.getString("normalized_filename"),
                row.getObject("attachment_id", UUID.class), null, row.getString("content_type"),
                row.getLong("size_bytes")), tenantId, userId, limit);
    }

    List<LinkMessage> linkMessages(long tenantId, long userId) {
        return jdbc.query("""
                SELECT message.message_id, message.conversation_id, message.body,
                       message.created_at, message.sender_name, COALESCE(conversation.name, '') AS conversation_name
                """ + visibleMessages() + """
                 WHERE message.tenant_id = ? AND member.user_id = ?
                   AND message.deleted_at IS NULL AND message.message_kind = 'USER'
                   AND LOWER(message.body) ~ 'https?://'
                 ORDER BY message.created_at DESC, message.message_id
                 LIMIT 100
                """, (row, ignored) -> new LinkMessage(
                row.getObject("message_id", UUID.class), row.getObject("conversation_id", UUID.class),
                row.getString("conversation_name"), row.getString("body"),
                row.getObject("created_at", OffsetDateTime.class), row.getString("sender_name")),
                tenantId, userId);
    }

    private String visibleMessages() {
        return """
                  FROM msg_messages message
                  JOIN msg_conversations conversation
                    ON conversation.tenant_id = message.tenant_id
                   AND conversation.conversation_id = message.conversation_id
                   AND conversation.lifecycle_state = 'ACTIVE'
                  JOIN msg_conversation_members member
                    ON member.tenant_id = message.tenant_id
                   AND member.conversation_id = message.conversation_id
                   AND member.lifecycle_state = 'ACTIVE'
                   AND message.sequence >= member.history_start_sequence
                  JOIN msg_people_snapshot viewer
                    ON viewer.tenant_id = member.tenant_id AND viewer.user_id = member.user_id
                   AND viewer.lifecycle_state = 'ACTIVE'
                """;
    }

    record LinkMessage(UUID messageId, UUID conversationId, String conversationName,
                       String body, OffsetDateTime sharedAt, String senderName) { }
}
