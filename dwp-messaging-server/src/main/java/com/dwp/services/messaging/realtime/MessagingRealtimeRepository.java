package com.dwp.services.messaging.realtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class MessagingRealtimeRepository {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MessagingRealtimeRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public MessagingRealtimeEvent append(
            long tenantId,
            Long audienceUserId,
            UUID conversationId,
            UUID messageId,
            long actorUserId,
            String eventType,
            Map<String, Object> payload) {
        return jdbc.queryForObject("""
                INSERT INTO msg_realtime_events (
                    tenant_id, audience_user_id, conversation_id, message_id, message_sequence,
                    actor_user_id, event_type, payload)
                VALUES (?, ?, ?, ?,
                        (SELECT sequence FROM msg_messages
                          WHERE tenant_id = ? AND message_id = ?),
                        ?, ?, ?::jsonb)
                RETURNING event_sequence, event_id, tenant_id, audience_user_id,
                          conversation_id, message_id, message_sequence, actor_user_id, event_type,
                          payload::text AS payload_json, occurred_at
                """, this::event, tenantId, audienceUserId, conversationId, messageId,
                tenantId, messageId, actorUserId, eventType, json(payload));
    }

    public List<MessagingRealtimeEvent> eventsAfter(
            MessagingRequestContext.Subject subject, long after, int limit) {
        return eventsBetween(subject, after, Long.MAX_VALUE, limit);
    }

    public List<MessagingRealtimeEvent> eventsBetween(
            MessagingRequestContext.Subject subject, long after, long through, int limit) {
        return jdbc.query("""
                SELECT event.event_sequence, event.event_id, event.tenant_id,
                       event.audience_user_id, event.conversation_id, event.message_id,
                       event.message_sequence, event.actor_user_id, event.event_type,
                       event.payload::text AS payload_json, event.occurred_at
                 FROM msg_realtime_events event
                 WHERE event.tenant_id = ?
                   AND event.event_sequence > ?
                   AND event.event_sequence <= ?
                   AND (event.audience_user_id IS NULL OR event.audience_user_id = ?)
                   AND (event.conversation_id IS NULL OR EXISTS (
                       SELECT 1 FROM msg_conversation_members member
                        WHERE member.tenant_id = event.tenant_id
                          AND member.conversation_id = event.conversation_id
                          AND member.user_id = ?
                          AND member.lifecycle_state = 'ACTIVE'
                          AND event.occurred_at >= member.membership_started_at
                          AND (event.message_sequence IS NULL
                               OR event.message_sequence >= member.history_start_sequence)))
                 ORDER BY event.event_sequence
                 LIMIT ?
                """, (row, ignored) -> event(row), subject.tenantId(), after, through,
                subject.userId(), subject.userId(), limit);
    }

    public long latestTenantSequence(long tenantId) {
        Long sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(event_sequence), 0)
                  FROM msg_realtime_events
                 WHERE tenant_id = ?
                """, Long.class, tenantId);
        return sequence == null ? 0 : sequence;
    }

    public long latestVisibleSequence(MessagingRequestContext.Subject subject) {
        Long sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(event.event_sequence), 0)
                  FROM msg_realtime_events event
                 WHERE event.tenant_id = ?
                   AND (event.audience_user_id IS NULL OR event.audience_user_id = ?)
                   AND (event.conversation_id IS NULL OR EXISTS (
                       SELECT 1 FROM msg_conversation_members member
                        WHERE member.tenant_id = event.tenant_id
                          AND member.conversation_id = event.conversation_id
                          AND member.user_id = ?
                          AND member.lifecycle_state = 'ACTIVE'
                          AND event.occurred_at >= member.membership_started_at
                          AND (event.message_sequence IS NULL
                               OR event.message_sequence >= member.history_start_sequence)))
                """, Long.class, subject.tenantId(), subject.userId(), subject.userId());
        return sequence == null ? 0 : sequence;
    }

    public boolean canReceive(MessagingRealtimeEvent event, long userId) {
        if (event.audienceUserId() != null && event.audienceUserId() != userId) return false;
        if (event.conversationId() == null) return true;
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM msg_conversation_members member
                  JOIN msg_conversations conversation
                    ON conversation.tenant_id = member.tenant_id
                   AND conversation.conversation_id = member.conversation_id
                 WHERE member.tenant_id = ?
                   AND member.conversation_id = ?
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                   AND conversation.lifecycle_state = 'ACTIVE'
                   AND member.membership_started_at <= ?
                   AND (CAST(? AS BIGINT) IS NULL
                        OR member.history_start_sequence <= ?)
                """, Long.class,
                event.tenantId(), event.conversationId(), userId, event.occurredAt(),
                event.messageSequence(), event.messageSequence());
        return count != null && count > 0;
    }

    public boolean isActiveConversationMember(long tenantId, UUID conversationId, long userId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM msg_conversation_members member
                  JOIN msg_conversations conversation
                    ON conversation.tenant_id = member.tenant_id
                   AND conversation.conversation_id = member.conversation_id
                 WHERE member.tenant_id = ?
                   AND member.conversation_id = ?
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                   AND conversation.lifecycle_state = 'ACTIVE'
                """, Long.class, tenantId, conversationId, userId);
        return count != null && count > 0;
    }

    private MessagingRealtimeEvent event(ResultSet row, int ignored) throws SQLException {
        return event(row);
    }

    private MessagingRealtimeEvent event(ResultSet row) throws SQLException {
        return new MessagingRealtimeEvent(
                row.getLong("event_sequence"),
                row.getObject("event_id", UUID.class),
                row.getLong("tenant_id"),
                row.getObject("audience_user_id", Long.class),
                row.getObject("conversation_id", UUID.class),
                row.getObject("message_id", UUID.class),
                row.getObject("message_sequence", Long.class),
                row.getLong("actor_user_id"),
                row.getString("event_type"),
                payload(row.getString("payload_json")),
                row.getObject("occurred_at", OffsetDateTime.class));
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "The realtime event payload is invalid.", exception);
        }
    }

    private Map<String, Object> payload(String json) throws SQLException {
        try {
            return objectMapper.readValue(json, PAYLOAD_TYPE);
        } catch (JsonProcessingException exception) {
            throw new SQLException("The persisted realtime event payload is invalid.", exception);
        }
    }
}
