package com.dwp.services.messaging.meeting;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MeetingSessionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<MeetingSession> sessionMapper = this::mapSession;

    public MeetingSessionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<ConversationAccess> access(long tenantId, UUID conversationId, long userId) {
        return jdbc.query("""
                SELECT member.member_role
                  FROM msg_conversations conversation
                  JOIN msg_conversation_members member
                    ON member.tenant_id = conversation.tenant_id
                   AND member.conversation_id = conversation.conversation_id
                 WHERE conversation.tenant_id = ?
                   AND conversation.conversation_id = ?
                   AND conversation.lifecycle_state = 'ACTIVE'
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                """, (resultSet, rowNumber) -> new ConversationAccess(
                        resultSet.getString("member_role")),
                tenantId, conversationId, userId).stream().findFirst();
    }

    public void lockConversation(long tenantId, UUID conversationId) {
        jdbc.queryForObject("""
                SELECT conversation_id
                  FROM msg_conversations
                 WHERE tenant_id = ? AND conversation_id = ?
                 FOR UPDATE
                """, UUID.class, tenantId, conversationId);
    }

    public Optional<MeetingSession> current(long tenantId, UUID conversationId) {
        return jdbc.query("""
                SELECT session_id, tenant_id, conversation_id, provider, room_name,
                       lifecycle_state, started_by, started_at, ended_by, ended_at, version
                  FROM msg_meeting_sessions
                 WHERE tenant_id = ?
                   AND conversation_id = ?
                   AND lifecycle_state = 'ACTIVE'
                 LIMIT 1
                """, sessionMapper, tenantId, conversationId).stream().findFirst();
    }

    public List<MeetingHistoryItem> history(
            long tenantId, UUID conversationId, int limit) {
        return jdbc.query("""
                SELECT session.session_id, session.conversation_id, session.provider,
                       session.lifecycle_state, session.started_by,
                       COALESCE(starter.display_name, 'User ' || session.started_by) AS started_by_name,
                       session.started_at, session.ended_by,
                       CASE WHEN session.ended_by IS NULL THEN NULL
                            ELSE COALESCE(ender.display_name, 'User ' || session.ended_by) END
                            AS ended_by_name,
                       session.ended_at, session.version
                  FROM msg_meeting_sessions session
                  LEFT JOIN msg_people_snapshot starter
                    ON starter.tenant_id = session.tenant_id
                   AND starter.user_id = session.started_by
                  LEFT JOIN msg_people_snapshot ender
                    ON ender.tenant_id = session.tenant_id
                   AND ender.user_id = session.ended_by
                 WHERE session.tenant_id = ?
                   AND session.conversation_id = ?
                   AND session.lifecycle_state = 'ENDED'
                 ORDER BY session.ended_at DESC, session.session_id DESC
                 LIMIT ?
                """, (resultSet, ignored) -> {
            long endedBy = resultSet.getLong("ended_by");
            boolean endedByNull = resultSet.wasNull();
            return new MeetingHistoryItem(
                    resultSet.getObject("session_id", UUID.class),
                    resultSet.getObject("conversation_id", UUID.class),
                    resultSet.getString("provider"),
                    resultSet.getString("lifecycle_state"),
                    resultSet.getLong("started_by"),
                    resultSet.getString("started_by_name"),
                    resultSet.getObject("started_at", OffsetDateTime.class),
                    endedByNull ? null : endedBy,
                    resultSet.getString("ended_by_name"),
                    resultSet.getObject("ended_at", OffsetDateTime.class),
                    resultSet.getLong("version"));
        }, tenantId, conversationId, limit);
    }

    public MeetingSession create(
            UUID sessionId,
            long tenantId,
            UUID conversationId,
            String provider,
            String roomName,
            long startedBy,
            String correlationId) {
        return jdbc.query("""
                INSERT INTO msg_meeting_sessions (
                    session_id, tenant_id, conversation_id, provider, room_name,
                    lifecycle_state, started_by, correlation_id, metadata)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, '{}'::jsonb)
                RETURNING session_id, tenant_id, conversation_id, provider, room_name,
                          lifecycle_state, started_by, started_at, ended_by, ended_at, version
                """, sessionMapper,
                sessionId, tenantId, conversationId, provider, roomName,
                startedBy, correlationId).stream().findFirst()
                .orElseThrow(() -> new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The meeting session could not be created."));
    }

    public MeetingSession end(
            long tenantId,
            UUID conversationId,
            UUID sessionId,
            long endedBy) {
        return jdbc.query("""
                UPDATE msg_meeting_sessions
                   SET lifecycle_state = 'ENDED',
                       ended_by = ?,
                       ended_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE tenant_id = ?
                   AND conversation_id = ?
                   AND session_id = ?
                   AND lifecycle_state = 'ACTIVE'
                RETURNING session_id, tenant_id, conversation_id, provider, room_name,
                          lifecycle_state, started_by, started_at, ended_by, ended_at, version
                """, sessionMapper,
                endedBy, tenantId, conversationId, sessionId).stream().findFirst()
                .orElseThrow(() -> new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The meeting session is no longer active."));
    }

    public void recordEvent(
            MeetingSession session,
            long actorUserId,
            String eventType,
            Map<String, Object> metadata) {
        jdbc.update("""
                INSERT INTO msg_meeting_events (
                    event_id, tenant_id, session_id, conversation_id, actor_user_id,
                    event_type, provider, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                UUID.randomUUID(), session.tenantId(), session.sessionId(),
                session.conversationId(), actorUserId, eventType, session.provider(),
                json(metadata));
    }

    public void audit(
            MeetingSession session,
            long actorUserId,
            String eventType,
            String correlationId,
            Map<String, Object> metadata) {
        jdbc.update("""
                INSERT INTO msg_audit_events (
                    tenant_id, actor_user_id, event_type, object_type, object_id,
                    after_state, correlation_id)
                VALUES (?, ?, ?, 'MSG_MEETING_SESSION', ?, ?::jsonb, ?)
                """,
                session.tenantId(), actorUserId, eventType,
                session.sessionId().toString(), json(metadata), correlationId);
    }

    private MeetingSession mapSession(ResultSet resultSet, int rowNumber) throws SQLException {
        long endedBy = resultSet.getLong("ended_by");
        return new MeetingSession(
                resultSet.getObject("session_id", UUID.class),
                resultSet.getLong("tenant_id"),
                resultSet.getObject("conversation_id", UUID.class),
                resultSet.getString("provider"),
                resultSet.getString("room_name"),
                resultSet.getString("lifecycle_state"),
                resultSet.getLong("started_by"),
                resultSet.getObject("started_at", OffsetDateTime.class),
                resultSet.wasNull() ? null : endedBy,
                resultSet.getObject("ended_at", OffsetDateTime.class),
                resultSet.getLong("version"));
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Meeting event metadata is not serializable.", exception);
        }
    }

    public record ConversationAccess(String memberRole) {
        public boolean canEndAnyMeeting() {
            return "OWNER".equals(memberRole) || "MODERATOR".equals(memberRole);
        }
    }
}
