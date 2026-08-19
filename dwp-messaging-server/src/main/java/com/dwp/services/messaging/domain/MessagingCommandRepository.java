package com.dwp.services.messaging.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;

@Repository
class MessagingCommandRepository {

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

    UUID insertMessage(
            long tenantId,
            long userId,
            UUID conversationId,
            UUID idempotencyKey,
            String senderName,
            UUID senderPersonPublicId,
            String body,
            UUID replyToMessageId) {
        return jdbc.queryForObject("""
                WITH inserted AS (
                    INSERT INTO msg_messages (
                        message_id, tenant_id, conversation_id, sender_user_id,
                        sender_person_public_id, sender_name, body, content_type,
                        message_kind, reply_to_message_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'TEXT', 'USER', ?)
                    ON CONFLICT (message_id) DO NOTHING
                    RETURNING message_id, created_at
                ), selected AS (
                    SELECT message_id, created_at FROM inserted
                    UNION ALL
                    SELECT message_id, created_at FROM msg_messages
                     WHERE message_id = ? AND tenant_id = ? AND conversation_id = ?
                    LIMIT 1
                )
                UPDATE msg_conversations conversation
                   SET last_message_id = selected.message_id,
                       last_message_at = selected.created_at,
                       version = conversation.version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                  FROM selected
                 WHERE conversation.tenant_id = ?
                   AND conversation.conversation_id = ?
                RETURNING selected.message_id
                """, UUID.class,
                idempotencyKey, tenantId, conversationId, userId, senderPersonPublicId,
                senderName, body.trim(), replyToMessageId, idempotencyKey,
                tenantId, conversationId, userId, tenantId, conversationId);
    }

    int markRead(long tenantId, long userId, UUID conversationId, UUID messageId) {
        return jdbc.update("""
                UPDATE msg_conversation_members member
                   SET last_read_message_id = ?,
                       last_read_at = message.created_at,
                       version = member.version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                  FROM msg_messages message
                 WHERE member.tenant_id = ?
                   AND member.user_id = ?
                   AND member.conversation_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                   AND message.tenant_id = member.tenant_id
                   AND message.conversation_id = member.conversation_id
                   AND message.message_id = ?
                """, messageId, userId, tenantId, userId, conversationId, messageId);
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
}
