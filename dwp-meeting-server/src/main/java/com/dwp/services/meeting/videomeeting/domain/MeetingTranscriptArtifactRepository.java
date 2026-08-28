package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MeetingTranscriptArtifactRepository {

    private final JdbcTemplate jdbc;

    public MeetingTranscriptArtifactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TranscriptArtifact> lock(
            long tenantId, UUID meetingId, UUID artifactId) {
        return jdbc.query("""
                SELECT artifact_id, tenant_id, meeting_id, artifact_state,
                       sha256, retention_until, server_side_processing_allowed,
                       processing_region, content_notice_id, consent_snapshot_sha256,
                       finalization_idempotency_key, finalization_request_sha256,
                       finalized_at, finalized_by, version
                  FROM vm_meeting_artifacts
                 WHERE tenant_id = ? AND meeting_id = ? AND artifact_id = ?
                   AND artifact_type = 'TRANSCRIPT'
                 FOR UPDATE
                """, this::artifact, tenantId, meetingId, artifactId)
                .stream().findFirst();
    }

    public TranscriptArtifact finalizeAvailable(
            TranscriptArtifact current,
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
        return jdbc.query("""
                UPDATE vm_meeting_artifacts
                   SET artifact_state = 'AVAILABLE', storage_provider = ?, object_key = ?,
                       content_type = ?, size_bytes = ?, sha256 = ?, retention_until = ?,
                       server_side_processing_allowed = TRUE, processing_region = ?,
                       content_notice_id = ?, consent_snapshot_sha256 = ?,
                       finalization_idempotency_key = ?, finalization_request_sha256 = ?,
                       finalized_at = ?, finalized_by = ?, version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND artifact_id = ?
                   AND artifact_state IN ('NONE', 'PROCESSING', 'UNAVAILABLE', 'FAILED')
                   AND version = ?
                RETURNING artifact_id, tenant_id, meeting_id, artifact_state,
                          sha256, retention_until, server_side_processing_allowed,
                          processing_region, content_notice_id, consent_snapshot_sha256,
                          finalization_idempotency_key, finalization_request_sha256,
                          finalized_at, finalized_by, version
                """, this::artifact,
                storageProvider, objectKey, contentType, sizeBytes, sourceSha256,
                retentionUntil, processingRegion, noticeId, consentSnapshotSha256,
                idempotencyKey, requestSha256, finalizedAt, actorUserId,
                finalizedAt, actorUserId, current.tenantId(), current.meetingId(),
                current.artifactId(), current.version())
                .stream().findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The transcript artifact changed. Refresh and retry."));
    }

    public void consumeAssertion(
            UUID jti,
            long tenantId,
            UUID meetingId,
            UUID artifactId,
            OffsetDateTime expiresAt,
            OffsetDateTime consumedAt) {
        jdbc.update("""
                DELETE FROM vm_meeting_transcript_finalization_assertion_replay
                 WHERE expires_at <= ?
                """, consumedAt);
        try {
            jdbc.update("""
                    INSERT INTO vm_meeting_transcript_finalization_assertion_replay (
                        jti, tenant_id, meeting_id, artifact_id, expires_at, consumed_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, jti, tenantId, meetingId, artifactId, expiresAt, consumedAt);
        } catch (DuplicateKeyException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The transcript artifact producer assertion was already used.");
        }
    }

    private TranscriptArtifact artifact(ResultSet rs, int row) throws SQLException {
        long finalizedBy = rs.getLong("finalized_by");
        Long finalizedByValue = rs.wasNull() ? null : finalizedBy;
        return new TranscriptArtifact(
                rs.getObject("artifact_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("meeting_id", UUID.class), rs.getString("artifact_state"),
                rs.getString("sha256"),
                rs.getObject("retention_until", OffsetDateTime.class),
                rs.getBoolean("server_side_processing_allowed"),
                rs.getString("processing_region"),
                rs.getObject("content_notice_id", UUID.class),
                rs.getString("consent_snapshot_sha256"),
                rs.getString("finalization_idempotency_key"),
                rs.getString("finalization_request_sha256"),
                rs.getObject("finalized_at", OffsetDateTime.class),
                finalizedByValue, rs.getLong("version"));
    }

    public record TranscriptArtifact(
            UUID artifactId,
            long tenantId,
            UUID meetingId,
            String state,
            String sourceSha256,
            OffsetDateTime retentionUntil,
            boolean serverSideProcessingAllowed,
            String processingRegion,
            UUID contentNoticeId,
            String consentSnapshotSha256,
            String idempotencyKey,
            String requestSha256,
            OffsetDateTime finalizedAt,
            Long finalizedBy,
            long version) {
    }
}
