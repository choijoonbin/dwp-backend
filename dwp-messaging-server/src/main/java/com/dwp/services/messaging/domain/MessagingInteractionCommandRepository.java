package com.dwp.services.messaging.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
class MessagingInteractionCommandRepository {

    private final JdbcTemplate jdbc;

    MessagingInteractionCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    int editMessage(
            long tenantId,
            long userId,
            UUID conversationId,
            UUID messageId,
            String body,
            long expectedVersion) {
        return jdbc.update("""
                UPDATE msg_messages
                   SET body = ?, edited_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE tenant_id = ? AND conversation_id = ? AND message_id = ?
                   AND sender_user_id = ? AND version = ? AND deleted_at IS NULL
                """, body.trim(), tenantId, conversationId, messageId, userId, expectedVersion);
    }

    int softDeleteMessage(
            long tenantId,
            UUID conversationId,
            UUID messageId,
            long expectedVersion) {
        return jdbc.update("""
                UPDATE msg_messages
                   SET deleted_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE tenant_id = ? AND conversation_id = ? AND message_id = ?
                   AND version = ? AND deleted_at IS NULL
                """, tenantId, conversationId, messageId, expectedVersion);
    }

    void touchConversation(long tenantId, long userId, UUID conversationId) {
        jdbc.update("""
                UPDATE msg_conversations
                   SET version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ? AND conversation_id = ?
                """, userId, tenantId, conversationId);
    }

    int saveMessage(long tenantId, long userId, UUID conversationId, UUID messageId) {
        return jdbc.update("""
                INSERT INTO msg_saved_items (tenant_id, user_id, message_id)
                SELECT message.tenant_id, ?, message.message_id
                  FROM msg_messages message
                 WHERE message.tenant_id = ?
                   AND message.conversation_id = ?
                   AND message.message_id = ?
                ON CONFLICT (tenant_id, user_id, message_id) DO NOTHING
                """, userId, tenantId, conversationId, messageId);
    }

    int unsaveMessage(long tenantId, long userId, UUID conversationId, UUID messageId) {
        return jdbc.update("""
                DELETE FROM msg_saved_items saved
                 USING msg_messages message
                 WHERE saved.tenant_id = ?
                   AND saved.user_id = ?
                   AND saved.message_id = ?
                   AND message.tenant_id = saved.tenant_id
                   AND message.message_id = saved.message_id
                   AND message.conversation_id = ?
                """, tenantId, userId, messageId, conversationId);
    }

    int updateSettings(
            long tenantId,
            long userId,
            UUID conversationId,
            MessagingDtos.ConversationSettingsRequest request) {
        return jdbc.update("""
                UPDATE msg_conversation_members
                   SET notification_level = ?, favorite = ?, pinned = ?,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, request.notificationLevel(), request.favorite(), request.pinned(),
                userId, tenantId, conversationId, userId, request.version());
    }
}
