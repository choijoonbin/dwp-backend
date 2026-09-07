package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingScheduleDtos.ScheduleStateResponse;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public class VideoMeetingScheduleRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public VideoMeetingScheduleRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void lockSeriesKey(long tenantId, long actorId, String key) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "meeting-series|" + tenantId + "|" + actorId + "|" + key);
    }

    Optional<SeriesReplay> seriesReplay(long tenantId, long actorId, String key) {
        return jdbc.query("""
                SELECT series.series_id, series.request_sha256, occurrence.meeting_id
                  FROM vm_meeting_series series
                  JOIN vm_meeting_occurrences occurrence
                    ON occurrence.tenant_id = series.tenant_id
                   AND occurrence.series_id = series.series_id
                   AND occurrence.occurrence_index = 1
                 WHERE series.tenant_id = ? AND series.organizer_user_id = ?
                   AND series.idempotency_key = ?
                """, (rs, row) -> new SeriesReplay(
                        rs.getObject("series_id", UUID.class), rs.getString("request_sha256"),
                        rs.getObject("meeting_id", UUID.class)), tenantId, actorId, key)
                .stream().findFirst();
    }

    void createSeries(UUID seriesId, long tenantId, long actorId, String frequency,
            int interval, int occurrenceCount, LocalDateTime anchorLocalStart,
            String timeZone, int durationMinutes, String key, String digest) {
        jdbc.update("""
                INSERT INTO vm_meeting_series (
                    series_id, tenant_id, organizer_user_id, frequency,
                    recurrence_interval, occurrence_count, anchor_local_start,
                    time_zone, duration_minutes, idempotency_key, request_sha256,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, seriesId, tenantId, actorId, frequency, interval, occurrenceCount,
                anchorLocalStart, timeZone, durationMinutes, key, digest, actorId, actorId);
    }

    void linkOccurrence(long tenantId, UUID seriesId, int occurrenceIndex,
            UUID meetingId, LocalDateTime originalLocalStart) {
        jdbc.update("""
                INSERT INTO vm_meeting_occurrences (
                    tenant_id, series_id, occurrence_index, meeting_id, original_local_start)
                VALUES (?, ?, ?, ?, ?)
                """, tenantId, seriesId, occurrenceIndex, meetingId, originalLocalStart);
    }

    ScheduleStateResponse state(Meeting meeting) {
        return jdbc.query("""
                SELECT meeting.meeting_id, meeting.lifecycle_state,
                       meeting.scheduled_start_at, meeting.scheduled_end_at, meeting.time_zone,
                       meeting.version AS meeting_version,
                       occurrence.series_id, occurrence.occurrence_index,
                       series.occurrence_count, series.frequency, series.recurrence_interval,
                       series.version AS series_version,
                       COALESCE(occurrence.exception_state, 'NONE') AS exception_state,
                       preparation.invitation_revision,
                       COALESCE((SELECT delivery.delivery_state
                                   FROM vm_meeting_invitation_outbox delivery
                                  WHERE delivery.tenant_id = meeting.tenant_id
                                    AND delivery.meeting_id = meeting.meeting_id
                                  ORDER BY delivery.created_at DESC, delivery.event_id DESC
                                  LIMIT 1), 'NONE') AS delivery_state
                  FROM vm_meetings meeting
                  JOIN vm_meeting_preparations preparation
                    ON preparation.tenant_id = meeting.tenant_id
                   AND preparation.meeting_id = meeting.meeting_id
                  LEFT JOIN vm_meeting_occurrences occurrence
                    ON occurrence.tenant_id = meeting.tenant_id
                   AND occurrence.meeting_id = meeting.meeting_id
                  LEFT JOIN vm_meeting_series series
                    ON series.tenant_id = occurrence.tenant_id
                   AND series.series_id = occurrence.series_id
                 WHERE meeting.tenant_id = ? AND meeting.meeting_id = ?
                """, (rs, row) -> new ScheduleStateResponse(
                        rs.getObject("meeting_id", UUID.class), rs.getString("lifecycle_state"),
                        rs.getObject("scheduled_start_at", OffsetDateTime.class),
                        rs.getObject("scheduled_end_at", OffsetDateTime.class),
                        rs.getString("time_zone"), rs.getLong("meeting_version"),
                        rs.getObject("series_id", UUID.class),
                        rs.getObject("occurrence_index", Integer.class),
                        rs.getObject("occurrence_count", Integer.class),
                        rs.getString("frequency"),
                        rs.getObject("recurrence_interval", Integer.class),
                        rs.getObject("series_version", Long.class),
                        rs.getString("exception_state"), rs.getLong("invitation_revision"),
                        rs.getString("delivery_state")), meeting.tenantId(), meeting.meetingId())
                .stream().findFirst().orElseThrow(() -> notFound("The meeting schedule was not found."));
    }

    Optional<ScheduleStateResponse> replay(Meeting meeting, long actorId, String operation,
            String key, String digest) {
        return jdbc.query("""
                SELECT request_sha256, result_projection FROM vm_meeting_schedule_commands
                 WHERE tenant_id = ? AND meeting_id = ? AND actor_user_id = ?
                   AND operation = ? AND idempotency_key = ?
                """, (rs, row) -> {
                    if (!VideoMeetingCommandPolicy.requestHashesMatch(rs.getString(1), digest))
                        throw conflict("The schedule idempotency key was reused for different input.");
                    try {
                        return mapper.readValue(rs.getString(2), ScheduleStateResponse.class);
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException(
                                "The canonical meeting schedule command result is invalid.", exception);
                    }
                }, meeting.tenantId(), meeting.meetingId(), actorId, operation, key)
                .stream().findFirst();
    }

    void complete(Meeting meeting, long actorId, String operation,
            String key, String digest, ScheduleStateResponse result) {
        try {
            jdbc.update("""
                    INSERT INTO vm_meeting_schedule_commands (
                        command_id, tenant_id, meeting_id, actor_user_id, operation,
                        idempotency_key, request_sha256, result_version, result_projection)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, UUID.randomUUID(), meeting.tenantId(), meeting.meetingId(), actorId,
                    operation, key, digest, result.meetingVersion(),
                    mapper.writeValueAsString(result));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "The canonical meeting schedule command result cannot be persisted.", exception);
        }
    }

    void markOccurrenceException(Meeting meeting, String exceptionState) {
        jdbc.update("""
                UPDATE vm_meeting_occurrences
                   SET exception_state = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND meeting_id = ?
                """, exceptionState, meeting.tenantId(), meeting.meetingId());
    }

    Optional<SeriesContext> seriesContext(Meeting meeting) {
        return seriesContext(meeting, true);
    }

    Optional<SeriesContext> readSeriesContext(Meeting meeting) {
        return seriesContext(meeting, false);
    }

    private Optional<SeriesContext> seriesContext(Meeting meeting, boolean lock) {
        return jdbc.query("""
                SELECT series.series_id, occurrence.occurrence_index,
                       series.occurrence_count, series.frequency,
                       series.recurrence_interval, series.version
                  FROM vm_meeting_occurrences occurrence
                  JOIN vm_meeting_series series
                    ON series.tenant_id = occurrence.tenant_id
                   AND series.series_id = occurrence.series_id
                 WHERE occurrence.tenant_id = ? AND occurrence.meeting_id = ?
                """ + (lock ? " FOR UPDATE OF series" : ""), (rs, row) -> new SeriesContext(
                        rs.getObject("series_id", UUID.class), rs.getInt("occurrence_index"),
                        rs.getInt("occurrence_count"), rs.getString("frequency"),
                        rs.getInt("recurrence_interval"), rs.getLong("version")),
                meeting.tenantId(), meeting.meetingId()).stream().findFirst();
    }

    List<ScheduleMutationCandidate> mutationCandidates(
            Meeting meeting, UUID seriesId, int fromOccurrenceIndex, boolean lock) {
        return jdbc.query("""
                SELECT occurrence.occurrence_index, candidate.meeting_id,
                       candidate.lifecycle_state, candidate.version,
                       preparation.invitation_revision
                  FROM vm_meeting_occurrences occurrence
                  JOIN vm_meetings candidate
                    ON candidate.tenant_id = occurrence.tenant_id
                   AND candidate.meeting_id = occurrence.meeting_id
                  JOIN vm_meeting_preparations preparation
                    ON preparation.tenant_id = candidate.tenant_id
                   AND preparation.meeting_id = candidate.meeting_id
                 WHERE occurrence.tenant_id = ? AND occurrence.series_id = ?
                   AND occurrence.occurrence_index >= ?
                 ORDER BY occurrence.occurrence_index
                """ + (lock ? " FOR UPDATE OF candidate" : ""), (rs, row) ->
                new ScheduleMutationCandidate(
                        rs.getInt("occurrence_index"), rs.getObject("meeting_id", UUID.class),
                        VideoMeetingModels.LifecycleState.valueOf(rs.getString("lifecycle_state")),
                        rs.getLong("version"), rs.getLong("invitation_revision")),
                meeting.tenantId(), seriesId, fromOccurrenceIndex);
    }

    void updateSeriesAnchor(long tenantId, SeriesContext series, long expectedVersion,
            LocalDateTime anchorLocalStart, String timeZone, int durationMinutes, long actorId) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_series
                   SET anchor_local_start = ?, time_zone = ?, duration_minutes = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND series_id = ? AND version = ?
                """, anchorLocalStart, timeZone, durationMinutes, actorId,
                tenantId, series.seriesId(), expectedVersion);
        if (updated != 1) throw conflict("The meeting series changed. Refresh and retry.");
    }

    void bumpSeriesVersion(long tenantId, SeriesContext series,
            long expectedVersion, long actorId) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_series
                   SET version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND series_id = ? AND version = ?
                """, actorId, tenantId, series.seriesId(), expectedVersion);
        if (updated != 1) throw conflict("The meeting series changed. Refresh and retry.");
    }

    public void recordInvitationEvent(Meeting meeting, String eventType, long aggregateVersion) {
        jdbc.update("""
                INSERT INTO vm_meeting_invitation_outbox (
                    event_id, tenant_id, meeting_id, event_type,
                    aggregate_version, invitation_revision)
                SELECT ?, meeting.tenant_id, meeting.meeting_id, ?, ?, preparation.invitation_revision
                  FROM vm_meetings meeting
                  JOIN vm_meeting_preparations preparation
                    ON preparation.tenant_id = meeting.tenant_id
                   AND preparation.meeting_id = meeting.meeting_id
                 WHERE meeting.tenant_id = ? AND meeting.meeting_id = ?
                ON CONFLICT (tenant_id, meeting_id, event_type, aggregate_version) DO NOTHING
                """, UUID.randomUUID(), eventType, aggregateVersion,
                meeting.tenantId(), meeting.meetingId());
    }

    record SeriesReplay(UUID seriesId, String requestDigest, UUID firstMeetingId) { }
    record SeriesContext(UUID seriesId, int occurrenceIndex, int occurrenceCount,
            String frequency, int interval, long version) { }
    record ScheduleMutationCandidate(int occurrenceIndex, UUID meetingId,
            VideoMeetingModels.LifecycleState lifecycleState, long version,
            long invitationRevision) { }

    private BaseException notFound(String message) {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
