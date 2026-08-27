package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.ChatMessage;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.ChatMessageState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.HandRequest;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.HandRequestState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.StoredCommand;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingCollaborationModels.StreamPage;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class VideoMeetingCollaborationRepository {

    private final JdbcTemplate jdbc;

    public VideoMeetingCollaborationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextSequence(long tenantId, UUID meetingId) {
        return jdbc.queryForObject("""
                INSERT INTO vm_meeting_collaboration_sequences (
                    tenant_id, meeting_id, last_sequence)
                VALUES (?, ?, 1)
                ON CONFLICT (tenant_id, meeting_id) DO UPDATE
                   SET last_sequence = vm_meeting_collaboration_sequences.last_sequence + 1,
                       updated_at = CURRENT_TIMESTAMP
                RETURNING last_sequence
                """, Long.class, tenantId, meetingId);
    }

    public long currentSequence(long tenantId, UUID meetingId) {
        return jdbc.query("""
                SELECT last_sequence FROM vm_meeting_collaboration_sequences
                 WHERE tenant_id = ? AND meeting_id = ?
                """, (resultSet, rowNumber) -> resultSet.getLong("last_sequence"),
                tenantId, meetingId).stream().findFirst().orElse(0L);
    }

    public Optional<StoredCommand> command(
            long tenantId,
            UUID meetingId,
            long actorUserId,
            String commandType,
            String idempotencyKey) {
        return jdbc.query("""
                SELECT request_hash, result_resource_id, result_sequence, result_count
                  FROM vm_meeting_collaboration_commands
                 WHERE tenant_id = ? AND meeting_id = ? AND actor_user_id = ?
                   AND command_type = ? AND idempotency_key = ?
                """, (resultSet, rowNumber) -> new StoredCommand(
                        resultSet.getString("request_hash"),
                        resultSet.getObject("result_resource_id", UUID.class),
                        resultSet.getLong("result_sequence"),
                        resultSet.getInt("result_count")),
                tenantId, meetingId, actorUserId, commandType, idempotencyKey)
                .stream().findFirst();
    }

    public void saveCommand(
            long tenantId,
            UUID meetingId,
            long actorUserId,
            String commandType,
            String idempotencyKey,
            String requestHash,
            UUID resultResourceId,
            long resultSequence,
            int resultCount) {
        jdbc.update("""
                INSERT INTO vm_meeting_collaboration_commands (
                    tenant_id, meeting_id, actor_user_id, command_type, idempotency_key,
                    request_hash, result_resource_id, result_sequence, result_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, meetingId, actorUserId, commandType, idempotencyKey,
                requestHash, resultResourceId, resultSequence, resultCount);
    }

    public ChatMessage createChatMessage(
            long tenantId,
            UUID meetingId,
            Participant sender,
            long sequence,
            String text,
            OffsetDateTime sentAt,
            OffsetDateTime retentionUntil) {
        UUID messageId = UUID.randomUUID();
        return jdbc.query("""
                INSERT INTO vm_meeting_chat_messages (
                    message_id, tenant_id, meeting_id, participant_id, sender_user_id,
                    sender_person_public_id, sender_display_name, sender_role,
                    created_sequence, last_sequence, message_text, retention_until,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """, this::chatMessage,
                messageId, tenantId, meetingId, sender.participantId(), sender.userId(),
                sender.personPublicId(), sender.displayName(), sender.participantRole().name(),
                sequence, sequence, text, retentionUntil, sentAt, sentAt)
                .stream().findFirst().orElseThrow();
    }

    public Optional<ChatMessage> chatMessage(
            long tenantId, UUID meetingId, UUID messageId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_chat_messages
                 WHERE tenant_id = ? AND meeting_id = ? AND message_id = ?
                """, this::chatMessage, tenantId, meetingId, messageId)
                .stream().findFirst();
    }

    public StreamPage<ChatMessage> chatMessages(
            long tenantId, UUID meetingId, long afterSequence, int limit) {
        List<ChatMessage> rows = jdbc.query("""
                SELECT message.*
                  FROM vm_meeting_chat_messages message
                  JOIN vm_meetings meeting
                    ON meeting.tenant_id = message.tenant_id
                   AND meeting.meeting_id = message.meeting_id
                 WHERE message.tenant_id = ? AND message.meeting_id = ?
                   AND message.last_sequence > ?
                   AND (meeting.lifecycle_state = 'LIVE'
                        OR message.retention_until > CURRENT_TIMESTAMP)
                 ORDER BY message.last_sequence
                 LIMIT ?
                """, this::chatMessage, tenantId, meetingId, afterSequence, limit + 1);
        return page(rows, afterSequence, limit);
    }

    public ChatMessage deleteChatMessage(
            ChatMessage message,
            long sequence,
            long actorUserId,
            String reason,
            OffsetDateTime deletedAt) {
        return jdbc.query("""
                UPDATE vm_meeting_chat_messages
                   SET message_state = 'DELETED', message_text = NULL,
                       last_sequence = ?, deleted_at = ?, deleted_by = ?,
                       deletion_reason = ?, updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND message_id = ?
                   AND message_state = 'ACTIVE'
                RETURNING *
                """, this::chatMessage,
                sequence, deletedAt, actorUserId, reason, deletedAt,
                message.tenantId(), message.meetingId(), message.messageId())
                .stream().findFirst()
                .orElseGet(() -> chatMessage(
                        message.tenantId(), message.meetingId(), message.messageId())
                        .orElseThrow());
    }

    public Optional<HandRequest> activeHand(
            long tenantId, UUID meetingId, UUID participantId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_hand_requests
                 WHERE tenant_id = ? AND meeting_id = ? AND participant_id = ?
                   AND request_state IN ('RAISED', 'ACKNOWLEDGED')
                """, this::handRequest, tenantId, meetingId, participantId)
                .stream().findFirst();
    }

    public Optional<HandRequest> handRequest(
            long tenantId, UUID meetingId, UUID requestId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_hand_requests
                 WHERE tenant_id = ? AND meeting_id = ? AND request_id = ?
                """, this::handRequest, tenantId, meetingId, requestId)
                .stream().findFirst();
    }

    public HandRequest createHandRequest(
            long tenantId,
            UUID meetingId,
            Participant requester,
            long sequence,
            long actorUserId,
            OffsetDateTime raisedAt) {
        UUID requestId = UUID.randomUUID();
        HandRequest request = jdbc.query("""
                INSERT INTO vm_meeting_hand_requests (
                    request_id, tenant_id, meeting_id, participant_id, requester_user_id,
                    requester_person_public_id, requester_display_name, requester_role,
                    raised_sequence, last_sequence, raised_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """, this::handRequest,
                requestId, tenantId, meetingId, requester.participantId(), requester.userId(),
                requester.personPublicId(), requester.displayName(),
                requester.participantRole().name(), sequence, sequence, raisedAt, raisedAt)
                .stream().findFirst().orElseThrow();
        appendHandEvent(request, sequence, HandRequestState.RAISED, actorUserId, raisedAt);
        return request;
    }

    public HandRequest transitionHand(
            HandRequest request,
            HandRequestState nextState,
            long sequence,
            long actorUserId,
            OffsetDateTime occurredAt) {
        boolean acknowledge = nextState == HandRequestState.ACKNOWLEDGED;
        boolean resolve = !nextState.active();
        HandRequest updated = jdbc.query("""
                UPDATE vm_meeting_hand_requests
                   SET request_state = ?, last_sequence = ?,
                       acknowledged_at = CASE WHEN ? THEN ? ELSE acknowledged_at END,
                       acknowledged_by = CASE WHEN ? THEN ? ELSE acknowledged_by END,
                       resolved_at = CASE WHEN ? THEN ? ELSE resolved_at END,
                       resolved_by = CASE WHEN ? THEN ? ELSE resolved_by END,
                       updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND request_id = ?
                RETURNING *
                """, this::handRequest,
                nextState.name(), sequence,
                acknowledge, occurredAt, acknowledge, acknowledge ? actorUserId : null,
                resolve, occurredAt, resolve, resolve ? actorUserId : null, occurredAt,
                request.tenantId(), request.meetingId(), request.requestId())
                .stream().findFirst().orElseThrow();
        appendHandEvent(updated, sequence, nextState, actorUserId, occurredAt);
        return updated;
    }

    public List<HandRequest> activeHands(long tenantId, UUID meetingId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_hand_requests
                 WHERE tenant_id = ? AND meeting_id = ?
                   AND request_state IN ('RAISED', 'ACKNOWLEDGED')
                 ORDER BY raised_sequence
                """, this::handRequest, tenantId, meetingId);
    }

    public StreamPage<HandRequest> handRequests(
            long tenantId, UUID meetingId, long afterSequence, int limit) {
        List<HandRequest> rows = jdbc.query("""
                SELECT * FROM vm_meeting_hand_requests
                 WHERE tenant_id = ? AND meeting_id = ? AND last_sequence > ?
                 ORDER BY last_sequence
                 LIMIT ?
                """, this::handRequest, tenantId, meetingId, afterSequence, limit + 1);
        return page(rows, afterSequence, limit);
    }

    private void appendHandEvent(
            HandRequest request,
            long sequence,
            HandRequestState eventType,
            long actorUserId,
            OffsetDateTime occurredAt) {
        jdbc.update("""
                INSERT INTO vm_meeting_hand_events (
                    event_id, tenant_id, meeting_id, request_id, sequence,
                    event_type, actor_user_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), request.tenantId(), request.meetingId(),
                request.requestId(), sequence, eventType.name(), actorUserId, occurredAt);
    }

    private ChatMessage chatMessage(ResultSet row, int rowNumber) throws SQLException {
        return new ChatMessage(
                row.getObject("message_id", UUID.class), row.getLong("tenant_id"),
                row.getObject("meeting_id", UUID.class),
                row.getObject("participant_id", UUID.class), row.getLong("sender_user_id"),
                row.getObject("sender_person_public_id", UUID.class),
                row.getString("sender_display_name"),
                VideoMeetingModels.ParticipantRole.valueOf(row.getString("sender_role")),
                row.getLong("created_sequence"), row.getLong("last_sequence"),
                ChatMessageState.valueOf(row.getString("message_state")),
                row.getString("message_text"),
                row.getObject("retention_until", OffsetDateTime.class),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("deleted_at", OffsetDateTime.class));
    }

    private HandRequest handRequest(ResultSet row, int rowNumber) throws SQLException {
        return new HandRequest(
                row.getObject("request_id", UUID.class), row.getLong("tenant_id"),
                row.getObject("meeting_id", UUID.class),
                row.getObject("participant_id", UUID.class), row.getLong("requester_user_id"),
                row.getObject("requester_person_public_id", UUID.class),
                row.getString("requester_display_name"),
                VideoMeetingModels.ParticipantRole.valueOf(row.getString("requester_role")),
                row.getLong("raised_sequence"), row.getLong("last_sequence"),
                HandRequestState.valueOf(row.getString("request_state")),
                row.getObject("raised_at", OffsetDateTime.class),
                row.getObject("acknowledged_at", OffsetDateTime.class),
                nullableLong(row, "acknowledged_by"),
                row.getObject("resolved_at", OffsetDateTime.class),
                nullableLong(row, "resolved_by"));
    }

    private Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private <T> StreamPage<T> page(List<T> rows, long afterSequence, int limit) {
        boolean hasMore = rows.size() > limit;
        List<T> items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        long nextSequence = items.isEmpty()
                ? afterSequence
                : items.getLast() instanceof ChatMessage chat
                        ? chat.lastSequence()
                        : ((HandRequest) items.getLast()).lastSequence();
        return new StreamPage<>(items, nextSequence, hasMore);
    }

}
