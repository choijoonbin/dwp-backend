package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.RecordingState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MeetingRecordingArtifactRepository {

    private static final String ARTIFACT_COLUMNS = """
            artifact_id, tenant_id, meeting_id, artifact_state,
            storage_provider, object_key, content_type, size_bytes, sha256,
            retention_until, processing_region, content_notice_id,
            consent_snapshot_sha256, recording_session_id,
            recording_plan_version, recording_provider_code,
            recording_finalization_idempotency_key,
            recording_finalization_request_sha256,
            recording_finalized_at, recording_finalized_by,
            recording_deletion_command_id, version
            """;

    private final JdbcTemplate jdbc;

    public MeetingRecordingArtifactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RecordingArtifact> lock(
            long tenantId, UUID meetingId, UUID artifactId) {
        return jdbc.query("""
                SELECT %s
                  FROM vm_meeting_artifacts
                 WHERE tenant_id = ? AND meeting_id = ? AND artifact_id = ?
                   AND artifact_type = 'RECORDING'
                 FOR UPDATE
                """.formatted(ARTIFACT_COLUMNS), this::artifact,
                tenantId, meetingId, artifactId).stream().findFirst();
    }

    public Optional<RecordingArtifact> lockBySession(
            long tenantId, UUID meetingId, UUID recordingSessionId) {
        return jdbc.query("""
                SELECT %s
                  FROM vm_meeting_artifacts
                 WHERE tenant_id = ? AND meeting_id = ?
                   AND recording_session_id = ? AND artifact_type = 'RECORDING'
                 FOR UPDATE
                """.formatted(ARTIFACT_COLUMNS), this::artifact,
                tenantId, meetingId, recordingSessionId).stream().findFirst();
    }

    public Optional<RecordingProvenance> stoppedProvenanceForUpdate(
            long tenantId, UUID meetingId, UUID recordingSessionId) {
        return jdbc.query("""
                SELECT session.recording_session_id, session.plan_version,
                       session.notice_id, session.recording_state, session.stopped_at,
                       session.artifact_retention_days,
                       session.recording_provider_code,
                       session.recording_processing_region,
                       session.stop_consent_snapshot_sha256,
                       command.provider_code, command.provider_command_id
                  FROM vm_meeting_recording_sessions session
                  JOIN vm_meeting_recording_provider_commands command
                    ON command.tenant_id = session.tenant_id
                   AND command.meeting_id = session.meeting_id
                   AND command.recording_session_id = session.recording_session_id
                   AND command.command_type = 'STOP'
                   AND command.provider_code = session.recording_provider_code
                 WHERE session.tenant_id = ? AND session.meeting_id = ?
                   AND session.recording_session_id = ?
                   AND session.recording_state = 'STOPPED'
                   AND session.stopped_at IS NOT NULL
                   AND command.command_state = 'SUCCEEDED'
                   AND command.provider_command_id IS NOT NULL
                 FOR UPDATE OF session, command
                """, (row, index) -> new RecordingProvenance(
                        row.getObject("recording_session_id", UUID.class),
                        row.getLong("plan_version"),
                        row.getObject("notice_id", UUID.class),
                        RecordingState.valueOf(row.getString("recording_state")),
                        row.getObject("stopped_at", OffsetDateTime.class),
                        row.getObject("artifact_retention_days", Integer.class),
                        row.getString("recording_provider_code"),
                        row.getString("recording_processing_region"),
                        row.getString("stop_consent_snapshot_sha256"),
                        row.getString("provider_command_id")),
                tenantId, meetingId, recordingSessionId).stream().findFirst();
    }

    public RecordingArtifact finalizeAvailable(
            RecordingArtifact current,
            UUID artifactId,
            long tenantId,
            UUID meetingId,
            RecordingProvenance provenance,
            String storageProvider,
            String objectKey,
            String contentType,
            long sizeBytes,
            String sourceSha256,
            OffsetDateTime retentionUntil,
            String processingRegion,
            UUID noticeId,
            String consentSnapshotSha256,
            String idempotencyKey,
            String requestSha256,
            long actorUserId,
            OffsetDateTime finalizedAt) {
        if (current == null) {
            return jdbc.query("""
                    INSERT INTO vm_meeting_artifacts (
                        artifact_id, tenant_id, meeting_id, artifact_type, artifact_state,
                        storage_provider, object_key, content_type, size_bytes, sha256,
                        retention_until, metadata, server_side_processing_allowed,
                        processing_region, content_notice_id, consent_snapshot_sha256,
                        recording_session_id, recording_plan_version,
                        recording_provider_code,
                        recording_finalization_idempotency_key,
                        recording_finalization_request_sha256,
                        recording_finalized_at, recording_finalized_by,
                        created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, 'RECORDING', 'AVAILABLE',
                            ?, ?, ?, ?, ?, ?, '{}'::jsonb, FALSE, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    RETURNING %s
                    """.formatted(ARTIFACT_COLUMNS), this::artifact,
                    artifactId, tenantId, meetingId,
                    storageProvider, objectKey, contentType, sizeBytes, sourceSha256,
                    retentionUntil, processingRegion, noticeId, consentSnapshotSha256,
                    provenance.recordingSessionId(), provenance.planVersion(),
                    provenance.providerCode(), idempotencyKey, requestSha256,
                    finalizedAt, actorUserId, finalizedAt, actorUserId,
                    finalizedAt, actorUserId).stream().findFirst()
                    .orElseThrow(this::versionConflict);
        }
        return jdbc.query("""
                UPDATE vm_meeting_artifacts
                   SET artifact_state = 'AVAILABLE', storage_provider = ?, object_key = ?,
                       content_type = ?, size_bytes = ?, sha256 = ?, retention_until = ?,
                       metadata = '{}'::jsonb, server_side_processing_allowed = FALSE,
                       processing_region = ?, content_notice_id = ?,
                       consent_snapshot_sha256 = ?, recording_session_id = ?,
                       recording_plan_version = ?, recording_provider_code = ?,
                       recording_finalization_idempotency_key = ?,
                       recording_finalization_request_sha256 = ?,
                       recording_finalized_at = ?, recording_finalized_by = ?,
                       version = version + 1, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND artifact_id = ?
                   AND artifact_type = 'RECORDING'
                   AND artifact_state IN ('NONE', 'UNAVAILABLE', 'FAILED')
                   AND recording_finalization_idempotency_key IS NULL
                   AND recording_deletion_command_id IS NULL
                   AND storage_provider IS NULL AND object_key IS NULL
                   AND version = ?
                RETURNING %s
                """.formatted(ARTIFACT_COLUMNS), this::artifact,
                storageProvider, objectKey, contentType, sizeBytes, sourceSha256,
                retentionUntil, processingRegion, noticeId, consentSnapshotSha256,
                provenance.recordingSessionId(), provenance.planVersion(),
                provenance.providerCode(), idempotencyKey, requestSha256,
                finalizedAt, actorUserId, finalizedAt, actorUserId,
                tenantId, meetingId, artifactId, current.version())
                .stream().findFirst().orElseThrow(this::versionConflict);
    }

    public void consumeAssertion(
            UUID jti,
            long tenantId,
            UUID meetingId,
            UUID recordingSessionId,
            UUID artifactId,
            OffsetDateTime expiresAt,
            OffsetDateTime consumedAt) {
        jdbc.update("""
                DELETE FROM vm_meeting_recording_artifact_assertion_replay
                 WHERE expires_at <= ?
                """, consumedAt);
        try {
            jdbc.update("""
                    INSERT INTO vm_meeting_recording_artifact_assertion_replay (
                        jti, tenant_id, meeting_id, recording_session_id,
                        artifact_id, expires_at, consumed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, jti, tenantId, meetingId, recordingSessionId,
                    artifactId, expiresAt, consumedAt);
        } catch (DuplicateKeyException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The recording artifact producer assertion was already used.");
        }
    }

    private RecordingArtifact artifact(ResultSet row, int index) throws SQLException {
        long sizeBytes = row.getLong("size_bytes");
        Long nullableSizeBytes = row.wasNull() ? null : sizeBytes;
        long finalizedBy = row.getLong("recording_finalized_by");
        Long nullableFinalizedBy = row.wasNull() ? null : finalizedBy;
        return new RecordingArtifact(
                row.getObject("artifact_id", UUID.class), row.getLong("tenant_id"),
                row.getObject("meeting_id", UUID.class), row.getString("artifact_state"),
                row.getString("storage_provider"), row.getString("object_key"),
                row.getString("content_type"), nullableSizeBytes, row.getString("sha256"),
                row.getObject("retention_until", OffsetDateTime.class),
                row.getString("processing_region"),
                row.getObject("content_notice_id", UUID.class),
                row.getString("consent_snapshot_sha256"),
                row.getObject("recording_session_id", UUID.class),
                row.getObject("recording_plan_version", Long.class),
                row.getString("recording_provider_code"),
                row.getString("recording_finalization_idempotency_key"),
                row.getString("recording_finalization_request_sha256"),
                row.getObject("recording_finalized_at", OffsetDateTime.class),
                nullableFinalizedBy,
                row.getObject("recording_deletion_command_id", UUID.class),
                row.getLong("version"));
    }

    private BaseException versionConflict() {
        return new BaseException(
                ErrorCode.OBJECT_VERSION_CONFLICT,
                "The recording artifact changed. Refresh and retry.");
    }

    public record RecordingArtifact(
            UUID artifactId,
            long tenantId,
            UUID meetingId,
            String state,
            String storageProvider,
            String objectKey,
            String contentType,
            Long sizeBytes,
            String sourceSha256,
            OffsetDateTime retentionUntil,
            String processingRegion,
            UUID contentNoticeId,
            String consentSnapshotSha256,
            UUID recordingSessionId,
            Long recordingPlanVersion,
            String recordingProviderCode,
            String finalizationIdempotencyKey,
            String finalizationRequestSha256,
            OffsetDateTime recordingFinalizedAt,
            Long recordingFinalizedBy,
            UUID recordingDeletionCommandId,
            long version) {
    }

    public record RecordingProvenance(
            UUID recordingSessionId,
            long planVersion,
            UUID noticeId,
            RecordingState state,
            OffsetDateTime stoppedAt,
            Integer artifactRetentionDays,
            String providerCode,
            String processingRegion,
            String stopConsentSnapshotSha256,
            String providerCommandId) {
    }
}
