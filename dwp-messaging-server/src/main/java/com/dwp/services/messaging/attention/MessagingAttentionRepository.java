package com.dwp.services.messaging.attention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Repository
class MessagingAttentionRepository {

    private final JdbcTemplate jdbc;

    MessagingAttentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<MessagingAttentionDtos.ActivityItem> activity(long tenantId, long userId, int limit) {
        List<MessagingAttentionDtos.ActivityItem> candidates = new ArrayList<>(limit * 4);
        candidates.addAll(mentions(tenantId, userId, limit));
        candidates.addAll(threadReplies(tenantId, userId, limit));
        candidates.addAll(reactions(tenantId, userId, limit));
        candidates.addAll(systemEvents(tenantId, userId, limit));
        candidates.addAll(scheduledFailures(tenantId, userId, limit));
        candidates.sort(Comparator
                .comparing(MessagingAttentionDtos.ActivityItem::occurredAt)
                .reversed()
                .thenComparing(MessagingAttentionDtos.ActivityItem::id));
        LinkedHashMap<String, MessagingAttentionDtos.ActivityItem> distinct = new LinkedHashMap<>();
        candidates.forEach(item -> distinct.putIfAbsent(item.id(), item));
        return distinct.values().stream().limit(limit).toList();
    }

    List<MessagingAttentionDtos.ThreadItem> threads(long tenantId, long userId, int limit) {
        return jdbc.query("""
                SELECT root.message_id AS root_message_id,
                       root.conversation_id,
                       COALESCE(conversation.name, '') AS conversation_name,
                       conversation.conversation_type,
                       root.sender_name AS root_sender_name,
                       CASE WHEN root.deleted_at IS NULL
                                 AND conversation.data_classification NOT IN ('CONFIDENTIAL', 'RESTRICTED')
                            THEN LEFT(root.body, 280) END AS root_preview,
                       (root.deleted_at IS NOT NULL) AS root_deleted,
                       (conversation.data_classification IN ('CONFIDENTIAL', 'RESTRICTED')) AS protected_content,
                       (SELECT COUNT(*)
                          FROM msg_messages reply_count
                         WHERE reply_count.tenant_id = root.tenant_id
                           AND reply_count.conversation_id = root.conversation_id
                           AND reply_count.reply_to_message_id = root.message_id
                           AND reply_count.sequence >= member.history_start_sequence) AS reply_count,
                       latest.message_id AS latest_reply_message_id,
                       latest.sender_name AS latest_reply_sender_name,
                       latest.created_at AS latest_reply_at
                  FROM msg_messages root
                  JOIN msg_conversations conversation
                    ON conversation.tenant_id = root.tenant_id
                   AND conversation.conversation_id = root.conversation_id
                   AND conversation.lifecycle_state = 'ACTIVE'
                  JOIN msg_conversation_members member
                    ON member.tenant_id = root.tenant_id
                   AND member.conversation_id = root.conversation_id
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                   AND root.sequence >= member.history_start_sequence
                  JOIN LATERAL (
                        SELECT reply.message_id, reply.sender_name, reply.created_at
                          FROM msg_messages reply
                         WHERE reply.tenant_id = root.tenant_id
                           AND reply.conversation_id = root.conversation_id
                           AND reply.reply_to_message_id = root.message_id
                           AND reply.sequence >= member.history_start_sequence
                         ORDER BY reply.sequence DESC, reply.message_id DESC
                         LIMIT 1
                  ) latest ON TRUE
                 WHERE root.tenant_id = ?
                   AND root.reply_to_message_id IS NULL
                   AND (root.sender_user_id = ? OR EXISTS (
                        SELECT 1
                          FROM msg_messages participated
                         WHERE participated.tenant_id = root.tenant_id
                           AND participated.conversation_id = root.conversation_id
                           AND participated.reply_to_message_id = root.message_id
                           AND participated.sender_user_id = ?
                           AND participated.sequence >= member.history_start_sequence))
                 ORDER BY latest.created_at DESC, root.message_id DESC
                 LIMIT ?
                """, (row, ignored) -> new MessagingAttentionDtos.ThreadItem(
                row.getObject("root_message_id", UUID.class),
                row.getObject("conversation_id", UUID.class),
                row.getString("conversation_name"),
                row.getString("conversation_type"),
                row.getString("root_sender_name"),
                row.getString("root_preview"),
                row.getBoolean("root_deleted"),
                row.getBoolean("protected_content"),
                row.getLong("reply_count"),
                row.getObject("latest_reply_message_id", UUID.class),
                row.getString("latest_reply_sender_name"),
                row.getObject("latest_reply_at", OffsetDateTime.class)),
                userId, tenantId, userId, userId, limit);
    }

    private List<MessagingAttentionDtos.ActivityItem> mentions(
            long tenantId, long userId, int limit) {
        return jdbc.query("""
                SELECT CONCAT('MENTION:', message.message_id) AS activity_id,
                       'MENTION' AS activity_kind,
                       message.conversation_id,
                       COALESCE(conversation.name, '') AS conversation_name,
                       conversation.conversation_type,
                       message.message_id,
                       message.reply_to_message_id AS root_message_id,
                       message.sender_name AS actor_name,
                       CASE WHEN conversation.data_classification NOT IN ('CONFIDENTIAL', 'RESTRICTED')
                            THEN LEFT(message.body, 280) END AS preview,
                       (conversation.data_classification IN ('CONFIDENTIAL', 'RESTRICTED')) AS protected_content,
                       mention.created_at AS occurred_at
                  FROM msg_message_mentions mention
                  JOIN msg_messages message
                    ON message.tenant_id = mention.tenant_id
                   AND message.conversation_id = mention.conversation_id
                   AND message.message_id = mention.message_id
                   AND message.deleted_at IS NULL
                  JOIN msg_conversations conversation
                    ON conversation.tenant_id = message.tenant_id
                   AND conversation.conversation_id = message.conversation_id
                   AND conversation.lifecycle_state = 'ACTIVE'
                  JOIN msg_conversation_members member
                    ON member.tenant_id = message.tenant_id
                   AND member.conversation_id = message.conversation_id
                   AND member.user_id = mention.mentioned_user_id
                   AND member.lifecycle_state = 'ACTIVE'
                   AND message.sequence >= member.history_start_sequence
                 WHERE mention.tenant_id = ?
                   AND mention.mentioned_user_id = ?
                   AND message.sender_user_id <> ?
                 ORDER BY mention.created_at DESC, message.message_id DESC
                 LIMIT ?
                """, (row, ignored) -> activity(row), tenantId, userId, userId, limit);
    }

    private List<MessagingAttentionDtos.ActivityItem> threadReplies(
            long tenantId, long userId, int limit) {
        return jdbc.query("""
                SELECT CONCAT('THREAD_REPLY:', reply.message_id) AS activity_id,
                       'THREAD_REPLY' AS activity_kind,
                       reply.conversation_id,
                       COALESCE(conversation.name, '') AS conversation_name,
                       conversation.conversation_type,
                       reply.message_id,
                       root.message_id AS root_message_id,
                       reply.sender_name AS actor_name,
                       CASE WHEN conversation.data_classification NOT IN ('CONFIDENTIAL', 'RESTRICTED')
                            THEN LEFT(reply.body, 280) END AS preview,
                       (conversation.data_classification IN ('CONFIDENTIAL', 'RESTRICTED')) AS protected_content,
                       reply.created_at AS occurred_at
                  FROM msg_messages reply
                  JOIN msg_messages root
                    ON root.tenant_id = reply.tenant_id
                   AND root.conversation_id = reply.conversation_id
                   AND root.message_id = reply.reply_to_message_id
                  JOIN msg_conversations conversation
                    ON conversation.tenant_id = reply.tenant_id
                   AND conversation.conversation_id = reply.conversation_id
                   AND conversation.lifecycle_state = 'ACTIVE'
                  JOIN msg_conversation_members member
                    ON member.tenant_id = reply.tenant_id
                   AND member.conversation_id = reply.conversation_id
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                   AND root.sequence >= member.history_start_sequence
                   AND reply.sequence >= member.history_start_sequence
                 WHERE reply.tenant_id = ?
                   AND reply.deleted_at IS NULL
                   AND reply.sender_user_id <> ?
                   AND (root.sender_user_id = ? OR EXISTS (
                        SELECT 1
                          FROM msg_messages participated
                         WHERE participated.tenant_id = root.tenant_id
                           AND participated.conversation_id = root.conversation_id
                           AND participated.reply_to_message_id = root.message_id
                           AND participated.sender_user_id = ?
                           AND participated.sequence < reply.sequence
                           AND participated.sequence >= member.history_start_sequence))
                 ORDER BY reply.created_at DESC, reply.message_id DESC
                 LIMIT ?
                """, (row, ignored) -> activity(row),
                userId, tenantId, userId, userId, userId, limit);
    }

    private List<MessagingAttentionDtos.ActivityItem> reactions(
            long tenantId, long userId, int limit) {
        return jdbc.query("""
                SELECT CONCAT('REACTION:', reaction.message_id, ':', reaction.user_id, ':', reaction.emoji)
                           AS activity_id,
                       'REACTION' AS activity_kind,
                       message.conversation_id,
                       COALESCE(conversation.name, '') AS conversation_name,
                       conversation.conversation_type,
                       message.message_id,
                       message.reply_to_message_id AS root_message_id,
                       COALESCE(reactor.display_name, 'Member') AS actor_name,
                       CASE WHEN conversation.data_classification NOT IN ('CONFIDENTIAL', 'RESTRICTED')
                            THEN reaction.emoji END AS preview,
                       (conversation.data_classification IN ('CONFIDENTIAL', 'RESTRICTED')) AS protected_content,
                       reaction.created_at AS occurred_at
                  FROM msg_message_reactions reaction
                  JOIN msg_messages message
                    ON message.tenant_id = reaction.tenant_id
                   AND message.message_id = reaction.message_id
                   AND message.deleted_at IS NULL
                  JOIN msg_conversations conversation
                    ON conversation.tenant_id = message.tenant_id
                   AND conversation.conversation_id = message.conversation_id
                   AND conversation.lifecycle_state = 'ACTIVE'
                  JOIN msg_conversation_members member
                    ON member.tenant_id = message.tenant_id
                   AND member.conversation_id = message.conversation_id
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                   AND message.sequence >= member.history_start_sequence
                  LEFT JOIN msg_people_snapshot reactor
                    ON reactor.tenant_id = reaction.tenant_id
                   AND reactor.user_id = reaction.user_id
                 WHERE reaction.tenant_id = ?
                   AND message.sender_user_id = ?
                   AND reaction.user_id <> ?
                 ORDER BY reaction.created_at DESC, reaction.message_id DESC
                 LIMIT ?
                """, (row, ignored) -> activity(row),
                userId, tenantId, userId, userId, limit);
    }

    private List<MessagingAttentionDtos.ActivityItem> systemEvents(
            long tenantId, long userId, int limit) {
        return jdbc.query("""
                SELECT CONCAT('SYSTEM:', message.message_id) AS activity_id,
                       'SYSTEM' AS activity_kind,
                       message.conversation_id,
                       COALESCE(conversation.name, '') AS conversation_name,
                       conversation.conversation_type,
                       message.message_id,
                       NULL::uuid AS root_message_id,
                       message.sender_name AS actor_name,
                       CASE WHEN conversation.data_classification NOT IN ('CONFIDENTIAL', 'RESTRICTED')
                            THEN LEFT(message.body, 280) END AS preview,
                       (conversation.data_classification IN ('CONFIDENTIAL', 'RESTRICTED')) AS protected_content,
                       message.created_at AS occurred_at
                  FROM msg_messages message
                  JOIN msg_conversations conversation
                    ON conversation.tenant_id = message.tenant_id
                   AND conversation.conversation_id = message.conversation_id
                   AND conversation.lifecycle_state = 'ACTIVE'
                  JOIN msg_conversation_members member
                    ON member.tenant_id = message.tenant_id
                   AND member.conversation_id = message.conversation_id
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                   AND message.sequence >= member.history_start_sequence
                 WHERE message.tenant_id = ?
                   AND message.message_kind = 'SYSTEM'
                   AND message.deleted_at IS NULL
                 ORDER BY message.created_at DESC, message.message_id DESC
                 LIMIT ?
                """, (row, ignored) -> activity(row), userId, tenantId, limit);
    }

    private List<MessagingAttentionDtos.ActivityItem> scheduledFailures(
            long tenantId, long userId, int limit) {
        return jdbc.query("""
                SELECT CONCAT('SCHEDULE_FAILED:', scheduled.scheduled_message_id) AS activity_id,
                       'SCHEDULE_FAILED' AS activity_kind,
                       scheduled.conversation_id,
                       CASE WHEN member.user_id IS NOT NULL
                                  AND conversation.lifecycle_state = 'ACTIVE'
                            THEN COALESCE(conversation.name, '')
                            ELSE '' END AS conversation_name,
                       conversation.conversation_type,
                       NULL::uuid AS message_id,
                       scheduled.reply_to_message_id AS root_message_id,
                       'Scheduled delivery' AS actor_name,
                       COALESCE(scheduled.failure_detail, scheduled.failure_code,
                                'SCHEDULED_DELIVERY_FAILED') AS preview,
                       FALSE AS protected_content,
                       scheduled.updated_at AS occurred_at
                  FROM msg_scheduled_messages scheduled
                  JOIN msg_conversations conversation
                    ON conversation.tenant_id = scheduled.tenant_id
                   AND conversation.conversation_id = scheduled.conversation_id
                  LEFT JOIN msg_conversation_members member
                    ON member.tenant_id = scheduled.tenant_id
                   AND member.conversation_id = scheduled.conversation_id
                   AND member.user_id = scheduled.sender_user_id
                   AND member.lifecycle_state = 'ACTIVE'
                 WHERE scheduled.tenant_id = ?
                   AND scheduled.sender_user_id = ?
                   AND scheduled.status = 'FAILED'
                 ORDER BY scheduled.updated_at DESC, scheduled.scheduled_message_id DESC
                 LIMIT ?
                """, (row, ignored) -> activity(row), tenantId, userId, limit);
    }

    private MessagingAttentionDtos.ActivityItem activity(ResultSet row) throws SQLException {
        return new MessagingAttentionDtos.ActivityItem(
                row.getString("activity_id"),
                MessagingAttentionDtos.ActivityKind.valueOf(row.getString("activity_kind")),
                row.getObject("conversation_id", UUID.class),
                row.getString("conversation_name"),
                row.getString("conversation_type"),
                row.getObject("message_id", UUID.class),
                row.getObject("root_message_id", UUID.class),
                row.getString("actor_name"),
                row.getString("preview"),
                row.getBoolean("protected_content"),
                row.getObject("occurred_at", OffsetDateTime.class));
    }
}
