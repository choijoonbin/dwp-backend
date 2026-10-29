package com.dwp.services.messaging.later;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class MessagingLaterRepository {

    private final JdbcTemplate jdbc;

    MessagingLaterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    int promoteDue(long tenantId, long userId) {
        return jdbc.update("""
                UPDATE msg_saved_items
                   SET state = 'DUE', updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE tenant_id = ? AND user_id = ? AND state = 'SNOOZED'
                   AND COALESCE(reminder_at, snoozed_until) <= CURRENT_TIMESTAMP
                """, tenantId, userId);
    }

    List<MessagingLaterDtos.LaterItem> items(
            long tenantId,
            long userId,
            String state,
            String query,
            int page,
            int pageSize) {
        return jdbc.query(select() + """
                 WHERE saved.tenant_id = ? AND saved.user_id = ?
                   AND (? = 'ALL' OR saved.state = ?)
                   AND (? = ''
                        OR LOWER(COALESCE(conversation.name, '')) LIKE ?
                        OR LOWER(message.sender_name) LIKE ?
                        OR (conversation.data_classification NOT IN ('CONFIDENTIAL', 'RESTRICTED')
                            AND LOWER(message.body) LIKE ?))
                 ORDER BY CASE saved.state
                              WHEN 'DUE' THEN 0 WHEN 'SAVED' THEN 1
                              WHEN 'SNOOZED' THEN 2 ELSE 3 END,
                          COALESCE(saved.reminder_at, saved.updated_at) DESC,
                          saved.message_id DESC
                 LIMIT ? OFFSET ?
                """, (row, ignored) -> item(row),
                tenantId, userId, state, state,
                query, pattern(query), pattern(query), pattern(query),
                pageSize, page * pageSize);
    }

    long count(long tenantId, long userId, String state, String query) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM msg_saved_items saved
                  JOIN msg_messages message
                    ON message.tenant_id = saved.tenant_id
                   AND message.message_id = saved.message_id
                  JOIN msg_conversations conversation
                    ON conversation.tenant_id = message.tenant_id
                   AND conversation.conversation_id = message.conversation_id
                   AND conversation.lifecycle_state = 'ACTIVE'
                  JOIN msg_conversation_members member
                    ON member.tenant_id = message.tenant_id
                   AND member.conversation_id = message.conversation_id
                   AND member.user_id = saved.user_id
                   AND member.lifecycle_state = 'ACTIVE'
                   AND message.sequence >= member.history_start_sequence
                 WHERE saved.tenant_id = ? AND saved.user_id = ?
                   AND (? = 'ALL' OR saved.state = ?)
                   AND (? = ''
                        OR LOWER(COALESCE(conversation.name, '')) LIKE ?
                        OR LOWER(message.sender_name) LIKE ?
                        OR (conversation.data_classification NOT IN ('CONFIDENTIAL', 'RESTRICTED')
                            AND LOWER(message.body) LIKE ?))
                """, Long.class,
                tenantId, userId, state, state,
                query, pattern(query), pattern(query), pattern(query));
        return count == null ? 0 : count;
    }

    Optional<MessagingLaterDtos.LaterItem> item(long tenantId, long userId, UUID messageId) {
        return jdbc.query(select() + """
                 WHERE saved.tenant_id = ? AND saved.user_id = ? AND saved.message_id = ?
                """, (row, ignored) -> item(row), tenantId, userId, messageId)
                .stream()
                .findFirst();
    }

    int update(
            long tenantId,
            long userId,
            UUID messageId,
            MessagingLaterDtos.LaterState state,
            OffsetDateTime reminderAt,
            OffsetDateTime snoozedUntil,
            OffsetDateTime completedAt,
            long expectedVersion) {
        return jdbc.update("""
                UPDATE msg_saved_items
                   SET state = ?, reminder_at = ?, snoozed_until = ?, completed_at = ?,
                       updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE tenant_id = ? AND user_id = ? AND message_id = ? AND version = ?
                   AND EXISTS (
                       SELECT 1
                         FROM msg_messages message
                         JOIN msg_conversation_members member
                           ON member.tenant_id = message.tenant_id
                          AND member.conversation_id = message.conversation_id
                          AND member.user_id = ?
                          AND member.lifecycle_state = 'ACTIVE'
                          AND message.sequence >= member.history_start_sequence
                        WHERE message.tenant_id = msg_saved_items.tenant_id
                          AND message.message_id = msg_saved_items.message_id)
                """, state.name(), reminderAt, snoozedUntil, completedAt,
                tenantId, userId, messageId, expectedVersion, userId);
    }

    void audit(
            long tenantId,
            long userId,
            UUID messageId,
            MessagingLaterDtos.LaterState before,
            MessagingLaterDtos.LaterState after,
            String correlationId) {
        jdbc.update("""
                INSERT INTO msg_audit_events (
                    tenant_id, actor_user_id, event_type, object_type, object_id,
                    before_state, after_state, correlation_id)
                VALUES (?, ?, 'messaging.later.updated', 'MSG_SAVED_ITEM', ?,
                        jsonb_build_object('state', ?), jsonb_build_object('state', ?), ?)
                """, tenantId, userId, messageId.toString(), before.name(), after.name(), correlationId);
    }

    private String select() {
        return """
                SELECT saved.message_id, message.conversation_id,
                       COALESCE(conversation.name, '') AS conversation_name,
                       conversation.conversation_type, message.sender_name,
                       CASE WHEN message.deleted_at IS NULL
                                 AND conversation.data_classification NOT IN ('CONFIDENTIAL', 'RESTRICTED')
                            THEN LEFT(message.body, 500) END AS preview,
                       (conversation.data_classification IN ('CONFIDENTIAL', 'RESTRICTED')) AS protected_content,
                       (message.deleted_at IS NOT NULL) AS message_deleted,
                       saved.created_at AS saved_at, saved.state, saved.reminder_at,
                       saved.snoozed_until, saved.completed_at, saved.version
                  FROM msg_saved_items saved
                  JOIN msg_messages message
                    ON message.tenant_id = saved.tenant_id
                   AND message.message_id = saved.message_id
                  JOIN msg_conversations conversation
                    ON conversation.tenant_id = message.tenant_id
                   AND conversation.conversation_id = message.conversation_id
                   AND conversation.lifecycle_state = 'ACTIVE'
                  JOIN msg_conversation_members member
                    ON member.tenant_id = message.tenant_id
                   AND member.conversation_id = message.conversation_id
                   AND member.user_id = saved.user_id
                   AND member.lifecycle_state = 'ACTIVE'
                   AND message.sequence >= member.history_start_sequence
                """;
    }

    private MessagingLaterDtos.LaterItem item(ResultSet row) throws SQLException {
        return new MessagingLaterDtos.LaterItem(
                row.getObject("message_id", UUID.class),
                row.getObject("conversation_id", UUID.class),
                row.getString("conversation_name"),
                row.getString("conversation_type"),
                row.getString("sender_name"),
                row.getString("preview"),
                row.getBoolean("protected_content"),
                row.getBoolean("message_deleted"),
                row.getObject("saved_at", OffsetDateTime.class),
                MessagingLaterDtos.LaterState.valueOf(row.getString("state")),
                row.getObject("reminder_at", OffsetDateTime.class),
                row.getObject("snoozed_until", OffsetDateTime.class),
                row.getObject("completed_at", OffsetDateTime.class),
                row.getLong("version"));
    }

    private String pattern(String value) {
        return "%" + value.toLowerCase() + "%";
    }
}
