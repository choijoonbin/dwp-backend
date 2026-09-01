package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.BlockerCode;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ConsentCounts;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentNotice;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentPlan;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.NoticeState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.NoticeAcknowledgement;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.PlanState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingSession;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.StoredCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class VideoMeetingContentRepository {

    private final JdbcTemplate jdbc;

    public VideoMeetingContentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ContentPlan ensurePlan(long tenantId, UUID meetingId, long actorUserId) {
        jdbc.update("""
                INSERT INTO vm_meeting_content_plans (
                    plan_id, tenant_id, meeting_id, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, meeting_id) DO NOTHING
                """, UUID.randomUUID(), tenantId, meetingId, actorUserId, actorUserId);
        return plan(tenantId, meetingId).orElseThrow(() -> new BaseException(
                ErrorCode.ENTITY_NOT_FOUND, "The meeting content plan was not found."));
    }

    public Optional<ContentPlan> plan(long tenantId, UUID meetingId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_content_plans
                 WHERE tenant_id = ? AND meeting_id = ?
                """, this::plan, tenantId, meetingId).stream().findFirst();
    }

    public ContentPlan updatePlan(
            ContentPlan current,
            boolean recordingRequested,
            boolean transcriptionRequested,
            boolean aiSummaryRequested,
            boolean e2eeEnabled,
            PlanState state,
            UUID noticeId,
            int noticeRevision,
            long actorUserId,
            OffsetDateTime occurredAt) {
        if (current.currentNoticeId() != null) {
            jdbc.update("""
                    UPDATE vm_meeting_content_notices
                       SET notice_state = 'SUPERSEDED', superseded_at = ?
                     WHERE tenant_id = ? AND meeting_id = ? AND notice_id = ?
                       AND notice_state = 'PUBLISHED'
                    """, occurredAt, current.tenantId(), current.meetingId(),
                    current.currentNoticeId());
        }
        if (noticeId != null) {
            jdbc.update("""
                    INSERT INTO vm_meeting_content_notices (
                        notice_id, tenant_id, meeting_id, notice_revision,
                        recording_disclosed, transcription_disclosed,
                        ai_summary_disclosed, published_at, published_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, noticeId, current.tenantId(), current.meetingId(), noticeRevision,
                    recordingRequested, transcriptionRequested, aiSummaryRequested,
                    occurredAt, actorUserId);
        }
        return jdbc.query("""
                UPDATE vm_meeting_content_plans
                   SET recording_requested = ?, transcription_requested = ?,
                       ai_summary_requested = ?, e2ee_enabled = ?, plan_state = ?,
                       current_notice_id = ?, notice_revision = ?, version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND version = ?
                RETURNING *
                """, this::plan,
                recordingRequested, transcriptionRequested, aiSummaryRequested,
                e2eeEnabled, state.name(), noticeId, noticeRevision, occurredAt, actorUserId,
                current.tenantId(), current.meetingId(), current.version())
                .stream().findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The meeting content plan changed. Refresh and retry."));
    }

    /**
     * Operational readiness is a server-derived projection, not a user intent edit. Reconcile it
     * without changing the optimistic intent version used by clients and intelligence fences.
     */
    public ContentPlan reconcilePlanState(
            ContentPlan current,
            PlanState state,
            long actorUserId,
            OffsetDateTime occurredAt) {
        if (current.state() == state) return current;
        return jdbc.query("""
                UPDATE vm_meeting_content_plans
                   SET plan_state = ?, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND version = ?
                   AND plan_state = ?
                RETURNING *
                """, this::plan,
                state.name(), occurredAt, actorUserId,
                current.tenantId(), current.meetingId(), current.version(),
                current.state().name())
                .stream().findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The meeting content readiness changed. Refresh and retry."));
    }

    public Optional<ContentNotice> currentNotice(long tenantId, UUID meetingId) {
        return jdbc.query("""
                SELECT notice.*
                  FROM vm_meeting_content_plans plan
                  JOIN vm_meeting_content_notices notice
                    ON notice.tenant_id = plan.tenant_id
                   AND notice.meeting_id = plan.meeting_id
                   AND notice.notice_id = plan.current_notice_id
                 WHERE plan.tenant_id = ? AND plan.meeting_id = ?
                   AND notice.notice_state = 'PUBLISHED'
                """, this::notice, tenantId, meetingId).stream().findFirst();
    }

    public Optional<ContentNotice> notice(
            long tenantId, UUID meetingId, UUID noticeId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_content_notices
                 WHERE tenant_id = ? AND meeting_id = ? AND notice_id = ?
                """, this::notice, tenantId, meetingId, noticeId).stream().findFirst();
    }

    public NoticeAcknowledgement acknowledge(
            long tenantId,
            UUID meetingId,
            UUID noticeId,
            UUID participantId,
            long actorUserId,
            OffsetDateTime acknowledgedAt) {
        Optional<NoticeAcknowledgement> inserted = jdbc.query("""
                INSERT INTO vm_meeting_content_notice_acknowledgements (
                    acknowledgement_id, tenant_id, meeting_id, notice_id,
                    participant_id, acknowledged_by, acknowledged_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, meeting_id, notice_id, participant_id) DO NOTHING
                RETURNING acknowledgement_id, notice_id, participant_id, acknowledged_at
                """, this::acknowledgement,
                UUID.randomUUID(), tenantId, meetingId, noticeId,
                participantId, actorUserId, acknowledgedAt).stream().findFirst();
        return inserted.orElseGet(() -> jdbc.query("""
                SELECT acknowledgement_id, notice_id, participant_id, acknowledged_at
                  FROM vm_meeting_content_notice_acknowledgements
                 WHERE tenant_id = ? AND meeting_id = ? AND notice_id = ?
                   AND participant_id = ?
                """, this::acknowledgement,
                tenantId, meetingId, noticeId, participantId).stream().findFirst()
                .orElseThrow());
    }

    public boolean acknowledgedBy(
            long tenantId, UUID meetingId, UUID noticeId, UUID participantId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM vm_meeting_content_notice_acknowledgements
                 WHERE tenant_id = ? AND meeting_id = ? AND notice_id = ?
                   AND participant_id = ?
                """, Integer.class, tenantId, meetingId, noticeId, participantId);
        return count != null && count > 0;
    }

    public Optional<NoticeAcknowledgement> acknowledgement(
            long tenantId, UUID meetingId, UUID acknowledgementId) {
        return jdbc.query("""
                SELECT acknowledgement_id, notice_id, participant_id, acknowledged_at
                  FROM vm_meeting_content_notice_acknowledgements
                 WHERE tenant_id = ? AND meeting_id = ? AND acknowledgement_id = ?
                """, this::acknowledgement,
                tenantId, meetingId, acknowledgementId).stream().findFirst();
    }

    public ConsentCounts consentCounts(long tenantId, UUID meetingId, UUID noticeId) {
        return jdbc.query("""
                SELECT COUNT(*)::INTEGER AS required_count,
                       COUNT(ack.acknowledgement_id)::INTEGER AS acknowledged_count
                  FROM vm_meeting_participants participant
                  LEFT JOIN vm_meeting_content_notice_acknowledgements ack
                    ON ack.tenant_id = participant.tenant_id
                   AND ack.meeting_id = participant.meeting_id
                   AND ack.participant_id = participant.participant_id
                   AND ack.notice_id = ?
                 WHERE participant.tenant_id = ? AND participant.meeting_id = ?
                   AND participant.attendance_state IN ('ADMITTED', 'JOINED')
                """, (resultSet, rowNumber) -> new ConsentCounts(
                        resultSet.getInt("required_count"),
                        resultSet.getInt("acknowledged_count")),
                noticeId, tenantId, meetingId).stream().findFirst()
                .orElse(new ConsentCounts(0, 0));
    }

    public Optional<RecordingSession> activeSession(long tenantId, UUID meetingId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_recording_sessions
                 WHERE tenant_id = ? AND meeting_id = ?
                   AND recording_state IN (
                       'REQUESTED', 'STARTING', 'RECORDING', 'STOP_REQUESTED')
                 ORDER BY requested_at DESC
                 LIMIT 1
                """, this::recordingSession, tenantId, meetingId).stream().findFirst();
    }

    public Optional<RecordingSession> recordingSession(
            long tenantId, UUID meetingId, UUID sessionId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_recording_sessions
                 WHERE tenant_id = ? AND meeting_id = ? AND recording_session_id = ?
                """, this::recordingSession, tenantId, meetingId, sessionId)
                .stream().findFirst();
    }

    public RecordingSession requestRecording(
            ContentPlan plan,
            ContentNotice notice,
            long actorUserId,
            OffsetDateTime requestedAt) {
        return jdbc.query("""
                INSERT INTO vm_meeting_recording_sessions (
                    recording_session_id, tenant_id, meeting_id, plan_version,
                    notice_id, recording_state, requested_at, requested_by, updated_at)
                VALUES (?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?)
                RETURNING *
                """, this::recordingSession,
                UUID.randomUUID(), plan.tenantId(), plan.meetingId(), plan.version(),
                notice.noticeId(), requestedAt, actorUserId, requestedAt)
                .stream().findFirst().orElseThrow();
    }

    public RecordingSession startRecordingCommand(
            ContentPlan plan,
            ContentNotice notice,
            int artifactRetentionDays,
            String recordingProviderCode,
            String recordingProcessingRegion,
            long actorUserId,
            OffsetDateTime requestedAt) {
        return jdbc.query("""
                INSERT INTO vm_meeting_recording_sessions (
                    recording_session_id, tenant_id, meeting_id, plan_version,
                    notice_id, recording_state, artifact_retention_days,
                    recording_provider_code, recording_processing_region,
                    requested_at, requested_by, updated_at)
                VALUES (?, ?, ?, ?, ?, 'STARTING', ?, ?, ?, ?, ?, ?)
                RETURNING *
                """, this::recordingSession,
                UUID.randomUUID(), plan.tenantId(), plan.meetingId(), plan.version(),
                notice.noticeId(), artifactRetentionDays,
                recordingProviderCode, recordingProcessingRegion,
                requestedAt, actorUserId, requestedAt)
                .stream().findFirst().orElseThrow();
    }

    public RecordingSession resumeRecordingStart(
            RecordingSession session, OffsetDateTime resumedAt) {
        return jdbc.query("""
                UPDATE vm_meeting_recording_sessions
                   SET recording_state = 'STARTING', failed_at = NULL, failure_code = NULL,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND recording_session_id = ?
                   AND recording_state = 'FAILED' AND version = ?
                RETURNING *
                """, this::recordingSession,
                resumedAt, session.tenantId(), session.meetingId(),
                session.recordingSessionId(), session.version())
                .stream().findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The recording session changed. Refresh and retry."));
    }

    public RecordingSession markRecording(
            RecordingSession session, OffsetDateTime startedAt) {
        return jdbc.query("""
                UPDATE vm_meeting_recording_sessions
                   SET recording_state = 'RECORDING', started_at = COALESCE(started_at, ?),
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND recording_session_id = ?
                   AND recording_state = 'STARTING' AND version = ?
                RETURNING *
                """, this::recordingSession,
                startedAt, startedAt, session.tenantId(), session.meetingId(),
                session.recordingSessionId(), session.version())
                .stream().findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The recording start completion is stale."));
    }

    public RecordingSession failRecordingStart(
            RecordingSession session, String failureCode, OffsetDateTime failedAt) {
        return jdbc.query("""
                UPDATE vm_meeting_recording_sessions
                   SET recording_state = 'FAILED', failed_at = ?, failure_code = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND recording_session_id = ?
                   AND recording_state = 'STARTING' AND version = ?
                RETURNING *
                """, this::recordingSession,
                failedAt, failureCode, failedAt, session.tenantId(), session.meetingId(),
                session.recordingSessionId(), session.version())
                .stream().findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The recording failure completion is stale."));
    }

    public RecordingSession requestStop(
            RecordingSession session, long actorUserId, OffsetDateTime requestedAt) {
        return requestStop(session, null, actorUserId, requestedAt);
    }

    public RecordingSession requestStop(
            RecordingSession session,
            String consentSnapshotSha256,
            long actorUserId,
            OffsetDateTime requestedAt) {
        return jdbc.query("""
                UPDATE vm_meeting_recording_sessions
                   SET recording_state = 'STOP_REQUESTED', stop_requested_at = ?,
                       stop_requested_by = ?, stop_consent_snapshot_sha256 = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND recording_session_id = ?
                   AND recording_state IN ('REQUESTED', 'STARTING', 'RECORDING')
                RETURNING *
                """, this::recordingSession,
                requestedAt, actorUserId, consentSnapshotSha256, requestedAt,
                session.tenantId(), session.meetingId(), session.recordingSessionId())
                .stream().findFirst()
                .orElseGet(() -> recordingSession(
                        session.tenantId(), session.meetingId(),
                        session.recordingSessionId()).orElseThrow());
    }

    public RecordingSession markStopped(
            RecordingSession session, OffsetDateTime stoppedAt) {
        return jdbc.query("""
                UPDATE vm_meeting_recording_sessions
                   SET recording_state = 'STOPPED', stopped_at = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND recording_session_id = ?
                   AND recording_state = 'STOP_REQUESTED' AND version = ?
                RETURNING *
                """, this::recordingSession,
                stoppedAt, stoppedAt, session.tenantId(), session.meetingId(),
                session.recordingSessionId(), session.version())
                .stream().findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The recording stop completion is stale."));
    }

    public Optional<StoredCommand> command(
            long tenantId,
            UUID meetingId,
            long actorUserId,
            String commandType,
            String idempotencyKey) {
        return jdbc.query("""
                SELECT request_hash, command_outcome, http_status, blocker_codes,
                       result_resource_id, result_version
                  FROM vm_meeting_content_commands
                 WHERE tenant_id = ? AND meeting_id = ? AND actor_user_id = ?
                   AND command_type = ? AND idempotency_key = ?
                """, (resultSet, rowNumber) -> new StoredCommand(
                        resultSet.getString("request_hash"),
                        "ACCEPTED".equals(resultSet.getString("command_outcome")),
                        resultSet.getInt("http_status"),
                        blockerCodes(resultSet.getString("blocker_codes")),
                        resultSet.getObject("result_resource_id", UUID.class),
                        resultSet.getLong("result_version")),
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
            boolean accepted,
            int httpStatus,
            List<BlockerCode> blockers,
            UUID resultResourceId,
            long resultVersion) {
        jdbc.update("""
                INSERT INTO vm_meeting_content_commands (
                    tenant_id, meeting_id, actor_user_id, command_type, idempotency_key,
                    request_hash, command_outcome, http_status, blocker_codes,
                    result_resource_id, result_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, meetingId, actorUserId, commandType, idempotencyKey,
                requestHash, accepted ? "ACCEPTED" : "BLOCKED", httpStatus,
                blockers.stream().map(Enum::name).collect(
                        java.util.stream.Collectors.joining(",")),
                resultResourceId, resultVersion);
    }

    private ContentPlan plan(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ContentPlan(
                resultSet.getObject("plan_id", UUID.class), resultSet.getLong("tenant_id"),
                resultSet.getObject("meeting_id", UUID.class),
                resultSet.getBoolean("recording_requested"),
                resultSet.getBoolean("transcription_requested"),
                resultSet.getBoolean("ai_summary_requested"),
                resultSet.getBoolean("e2ee_enabled"),
                PlanState.valueOf(resultSet.getString("plan_state")),
                resultSet.getObject("current_notice_id", UUID.class),
                resultSet.getInt("notice_revision"), resultSet.getLong("version"),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    private ContentNotice notice(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ContentNotice(
                resultSet.getObject("notice_id", UUID.class), resultSet.getLong("tenant_id"),
                resultSet.getObject("meeting_id", UUID.class),
                resultSet.getInt("notice_revision"),
                NoticeState.valueOf(resultSet.getString("notice_state")),
                resultSet.getString("disclosure_code"),
                resultSet.getBoolean("recording_disclosed"),
                resultSet.getBoolean("transcription_disclosed"),
                resultSet.getBoolean("ai_summary_disclosed"),
                resultSet.getObject("published_at", OffsetDateTime.class));
    }

    private RecordingSession recordingSession(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new RecordingSession(
                resultSet.getObject("recording_session_id", UUID.class),
                resultSet.getLong("tenant_id"),
                resultSet.getObject("meeting_id", UUID.class),
                resultSet.getLong("plan_version"),
                resultSet.getObject("notice_id", UUID.class),
                RecordingState.valueOf(resultSet.getString("recording_state")),
                resultSet.getObject("requested_at", OffsetDateTime.class),
                resultSet.getObject("requested_by", Long.class),
                resultSet.getObject("stop_requested_at", OffsetDateTime.class),
                resultSet.getObject("stop_requested_by", Long.class),
                resultSet.getObject("started_at", OffsetDateTime.class),
                resultSet.getObject("stopped_at", OffsetDateTime.class),
                resultSet.getObject("failed_at", OffsetDateTime.class),
                resultSet.getString("failure_code"),
                resultSet.getObject("artifact_retention_days", Integer.class),
                resultSet.getString("recording_provider_code"),
                resultSet.getString("recording_processing_region"),
                resultSet.getString("stop_consent_snapshot_sha256"),
                resultSet.getLong("version"));
    }

    private NoticeAcknowledgement acknowledgement(
            ResultSet resultSet, int rowNumber) throws SQLException {
        return new NoticeAcknowledgement(
                resultSet.getObject("acknowledgement_id", UUID.class),
                resultSet.getObject("notice_id", UUID.class),
                resultSet.getObject("participant_id", UUID.class),
                resultSet.getObject("acknowledged_at", OffsetDateTime.class));
    }

    private List<BlockerCode> blockerCodes(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(BlockerCode::valueOf).toList();
    }
}
