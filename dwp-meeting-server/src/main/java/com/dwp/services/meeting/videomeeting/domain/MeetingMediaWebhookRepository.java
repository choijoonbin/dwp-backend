package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaWebhook.ProviderEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
class MeetingMediaWebhookRepository {

    private final JdbcTemplate jdbc;

    MeetingMediaWebhookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean reserve(ProviderEvent event, OffsetDateTime receivedAt) {
        return jdbc.update("""
                INSERT INTO vm_meeting_provider_events (
                    provider_code, provider_event_id, event_type, tenant_id, meeting_id,
                    room_incarnation, provider_room_name, provider_room_sid,
                    participant_id, provider_participant_sid, provider_created_at,
                    processing_state, reason_code, received_at, processed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IGNORED', 'PROCESSING', ?, ?)
                ON CONFLICT (provider_code, provider_event_id) DO NOTHING
                """, event.provider(), event.eventId(), event.type().name(),
                event.room().tenantId(), event.room().meetingId(),
                event.room().incarnation(), event.room().roomName(), event.room().roomSid(),
                event.participant() == null ? null : event.participant().participantId(),
                event.participant() == null ? null : event.participant().participantSid(),
                event.createdAt(), receivedAt, receivedAt) == 1;
    }

    Optional<MeetingBinding> meetingForUpdate(ProviderEvent event) {
        return jdbc.query("""
                SELECT tenant_id, meeting_id, lifecycle_state, provider, room_name,
                       media_incarnation, media_access_state, organizer_user_id
                  FROM vm_meetings
                 WHERE tenant_id = ? AND meeting_id = ?
                 FOR UPDATE
                """, (row, index) -> new MeetingBinding(
                        row.getLong("tenant_id"),
                        row.getObject("meeting_id", UUID.class),
                        row.getString("lifecycle_state"), row.getString("provider"),
                        row.getString("room_name"),
                        row.getObject("media_incarnation", UUID.class),
                        row.getString("media_access_state"),
                        row.getLong("organizer_user_id")),
                event.room().tenantId(), event.room().meetingId()).stream().findFirst();
    }

    Optional<ParticipantBinding> participant(ProviderEvent event) {
        if (event.participant() == null) return Optional.empty();
        return jdbc.query("""
                SELECT participant_id, user_id, attendance_state
                  FROM vm_meeting_participants
                 WHERE tenant_id = ? AND meeting_id = ? AND participant_id = ?
                """, (row, index) -> new ParticipantBinding(
                        row.getObject("participant_id", UUID.class),
                        row.getLong("user_id"), row.wasNull(),
                        row.getString("attendance_state")),
                event.room().tenantId(), event.room().meetingId(),
                event.participant().participantId()).stream().findFirst();
    }

    void observeRoomStarted(ProviderEvent event) {
        jdbc.update("""
                UPDATE vm_meetings
                   SET provider_room_sid = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND meeting_id = ? AND media_incarnation = ?
                   AND provider = ? AND room_name = ?
                   AND lifecycle_state = 'LIVE' AND media_access_state IN ('ACTIVE', 'ENDING')
                """, event.room().roomSid(), event.room().tenantId(),
                event.room().meetingId(), event.room().incarnation(), event.provider(),
                event.room().roomName());
    }

    boolean participantJoined(ProviderEvent event) {
        var participant = event.participant();
        int inserted = jdbc.update("""
                INSERT INTO vm_meeting_provider_connections (
                    provider_code, provider_participant_sid, tenant_id, meeting_id,
                    room_incarnation, participant_id, connection_state,
                    provider_joined_at, last_provider_event_id, last_provider_event_at)
                VALUES (?, ?, ?, ?, ?, ?, 'JOINED', ?, ?, ?)
                ON CONFLICT (provider_code, provider_participant_sid) DO NOTHING
                """, event.provider(), participant.participantSid(),
                event.room().tenantId(), event.room().meetingId(),
                event.room().incarnation(), participant.participantId(),
                participant.joinedAt(), event.eventId(), event.createdAt());
        if (inserted == 0 && !activeConnectionMatches(event)) return false;
        return jdbc.update("""
                UPDATE vm_meeting_participants participant
                   SET attendance_state = 'JOINED',
                       joined_at = COALESCE(participant.joined_at, ?), left_at = NULL,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE participant.tenant_id = ? AND participant.meeting_id = ?
                   AND participant.participant_id = ?
                   AND participant.attendance_state IN ('ADMITTED', 'LEFT')
                   AND EXISTS (
                       SELECT 1 FROM vm_meetings meeting
                        WHERE meeting.tenant_id = participant.tenant_id
                          AND meeting.meeting_id = participant.meeting_id
                          AND meeting.lifecycle_state = 'LIVE'
                          AND meeting.media_access_state = 'ACTIVE'
                          AND meeting.media_incarnation = ?)
                """, participant.joinedAt(), participant.userId(),
                event.room().tenantId(), event.room().meetingId(),
                participant.participantId(), event.room().incarnation()) == 1;
    }

    boolean participantLeft(ProviderEvent event, boolean aborted) {
        var participant = event.participant();
        String terminal = aborted ? "ABORTED" : "LEFT";
        jdbc.update("""
                INSERT INTO vm_meeting_provider_connections (
                    provider_code, provider_participant_sid, tenant_id, meeting_id,
                    room_incarnation, participant_id, connection_state,
                    provider_joined_at, provider_left_at,
                    last_provider_event_id, last_provider_event_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (provider_code, provider_participant_sid) DO UPDATE
                   SET connection_state = EXCLUDED.connection_state,
                       provider_left_at = EXCLUDED.provider_left_at,
                       last_provider_event_id = EXCLUDED.last_provider_event_id,
                       last_provider_event_at = EXCLUDED.last_provider_event_at,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE vm_meeting_provider_connections.tenant_id = EXCLUDED.tenant_id
                   AND vm_meeting_provider_connections.meeting_id = EXCLUDED.meeting_id
                   AND vm_meeting_provider_connections.room_incarnation =
                       EXCLUDED.room_incarnation
                   AND vm_meeting_provider_connections.participant_id =
                       EXCLUDED.participant_id
                   AND vm_meeting_provider_connections.connection_state = 'JOINED'
                   AND vm_meeting_provider_connections.last_provider_event_at
                       <= EXCLUDED.last_provider_event_at
                """, event.provider(), participant.participantSid(),
                event.room().tenantId(), event.room().meetingId(),
                event.room().incarnation(), participant.participantId(), terminal,
                participant.joinedAt(), event.createdAt(), event.eventId(), event.createdAt());
        return jdbc.update("""
                UPDATE vm_meeting_participants participant
                   SET attendance_state = 'LEFT', left_at = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE participant.tenant_id = ? AND participant.meeting_id = ?
                   AND participant.participant_id = ?
                   AND participant.attendance_state = 'JOINED'
                   AND NOT EXISTS (
                       SELECT 1 FROM vm_meeting_provider_connections connection
                        WHERE connection.tenant_id = participant.tenant_id
                          AND connection.meeting_id = participant.meeting_id
                          AND connection.room_incarnation = ?
                          AND connection.participant_id = participant.participant_id
                          AND connection.connection_state = 'JOINED')
                """, event.createdAt(), participant.userId(),
                event.room().tenantId(), event.room().meetingId(),
                participant.participantId(), event.room().incarnation()) == 1;
    }

    boolean roomFinished(ProviderEvent event, MeetingBinding binding) {
        jdbc.update("""
                UPDATE vm_meeting_provider_connections
                   SET connection_state = 'LEFT', provider_left_at = ?,
                       last_provider_event_id = ?, last_provider_event_at = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND meeting_id = ? AND room_incarnation = ?
                   AND connection_state = 'JOINED'
                """, event.createdAt(), event.eventId(), event.createdAt(),
                binding.tenantId(), binding.meetingId(), binding.incarnation());
        jdbc.update("""
                UPDATE vm_meeting_participants participant
                   SET attendance_state = 'LEFT', left_at = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND attendance_state = 'JOINED'
                """, event.createdAt(), binding.organizerUserId(),
                binding.tenantId(), binding.meetingId());
        if ("ENDING".equals(binding.mediaAccessState())) {
            jdbc.update("""
                    UPDATE vm_meetings
                       SET provider_room_sid = ?, provider_room_closed_at = ?,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ? AND meeting_id = ? AND media_incarnation = ?
                    """, event.room().roomSid(), event.createdAt(), binding.tenantId(),
                    binding.meetingId(), binding.incarnation());
            return false;
        }
        return jdbc.update("""
                UPDATE vm_meetings
                   SET lifecycle_state = 'ENDED', ended_at = ?, ended_by = NULL,
                       media_access_state = 'ENDED', provider_room_sid = ?,
                       provider_room_closed_at = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND media_incarnation = ?
                   AND lifecycle_state = 'LIVE' AND media_access_state = 'ACTIVE'
                """, event.createdAt(), event.room().roomSid(), event.createdAt(),
                binding.organizerUserId(), binding.tenantId(), binding.meetingId(),
                binding.incarnation()) == 1;
    }

    void complete(ProviderEvent event, String state, String reason) {
        jdbc.update("""
                UPDATE vm_meeting_provider_events
                   SET processing_state = ?, reason_code = ?, processed_at = CURRENT_TIMESTAMP
                 WHERE provider_code = ? AND provider_event_id = ?
                """, state, reason, event.provider(), event.eventId());
    }

    Optional<CleanupClaim> claimCleanup(
            UUID fence,
            OffsetDateTime now,
            OffsetDateTime leaseUntil,
            int maximumAttempts) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT provider_code, provider_event_id
                      FROM vm_meeting_provider_events
                     WHERE cleanup_attempt_count < ?
                       AND ((processing_state = 'CLEANUP_REQUIRED'
                                AND (next_cleanup_at IS NULL OR next_cleanup_at <= ?))
                        OR (processing_state = 'CLEANUP_FAILED' AND next_cleanup_at <= ?)
                        OR (processing_state = 'CLEANUP_RUNNING'
                                AND cleanup_lease_expires_at <= ?))
                     ORDER BY processed_at
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                )
                UPDATE vm_meeting_provider_events event
                   SET processing_state = 'CLEANUP_RUNNING', cleanup_fence = ?,
                       cleanup_lease_expires_at = ?,
                       cleanup_attempt_count = cleanup_attempt_count + 1,
                       processed_at = CURRENT_TIMESTAMP
                  FROM candidate
                 WHERE event.provider_code = candidate.provider_code
                   AND event.provider_event_id = candidate.provider_event_id
                RETURNING event.provider_code, event.provider_event_id,
                          event.provider_room_name, event.cleanup_fence
                """, (row, index) -> new CleanupClaim(
                        row.getString("provider_code"),
                        row.getString("provider_event_id"),
                        row.getString("provider_room_name"),
                        row.getObject("cleanup_fence", UUID.class)),
                maximumAttempts, now, now, now, fence, leaseUntil).stream().findFirst();
    }

    void completeCleanup(CleanupClaim claim, OffsetDateTime completedAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_provider_events
                   SET processing_state = 'CLEANED', cleanup_fence = NULL,
                       cleanup_lease_expires_at = NULL, next_cleanup_at = NULL,
                       processed_at = ?
                 WHERE provider_code = ? AND provider_event_id = ?
                   AND processing_state = 'CLEANUP_RUNNING' AND cleanup_fence = ?
                   AND cleanup_lease_expires_at > ?
                """, completedAt, claim.provider(), claim.eventId(), claim.fence(),
                completedAt);
        if (updated != 1) throw staleCleanupFence();
    }

    void failCleanup(
            CleanupClaim claim, OffsetDateTime failedAt, OffsetDateTime retryAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_provider_events
                   SET processing_state = 'CLEANUP_FAILED', cleanup_fence = NULL,
                       cleanup_lease_expires_at = NULL, next_cleanup_at = ?,
                       processed_at = ?
                 WHERE provider_code = ? AND provider_event_id = ?
                   AND processing_state = 'CLEANUP_RUNNING' AND cleanup_fence = ?
                   AND cleanup_lease_expires_at > ?
                """, retryAt, failedAt, claim.provider(), claim.eventId(), claim.fence(),
                failedAt);
        if (updated != 1) throw staleCleanupFence();
    }

    private boolean activeConnectionMatches(ProviderEvent event) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_provider_connections
                 WHERE provider_code = ? AND provider_participant_sid = ?
                   AND tenant_id = ? AND meeting_id = ? AND room_incarnation = ?
                   AND participant_id = ? AND connection_state = 'JOINED'
                """, Integer.class, event.provider(), event.participant().participantSid(),
                event.room().tenantId(), event.room().meetingId(),
                event.room().incarnation(), event.participant().participantId());
        return count != null && count == 1;
    }

    private BaseException staleCleanupFence() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The revoked room cleanup lease changed or expired.");
    }

    record MeetingBinding(
            long tenantId,
            UUID meetingId,
            String lifecycleState,
            String provider,
            String roomName,
            UUID incarnation,
            String mediaAccessState,
            long organizerUserId) {
    }

    record ParticipantBinding(
            UUID participantId, long userId, boolean userIdNull, String attendanceState) {
    }

    record CleanupClaim(String provider, String eventId, String roomName, UUID fence) {
    }
}
