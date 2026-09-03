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
                       finalized_at, finalized_by, version,
                       registration_idempotency_key, registration_request_sha256,
                       registered_at, registered_by, transcript_plan_version,
                       transcript_provider_code, transcript_storage_provider_code,
                       transcript_deletion_command_id, storage_provider, object_key
                  FROM vm_meeting_artifacts
                 WHERE tenant_id = ? AND meeting_id = ? AND artifact_id = ?
                   AND artifact_type = 'TRANSCRIPT'
                 FOR UPDATE
                """, this::artifact, tenantId, meetingId, artifactId)
                .stream().findFirst();
    }

    public Optional<TranscriptArtifact> lockTranscript(long tenantId, UUID meetingId) {
        return jdbc.query("""
                SELECT artifact_id, tenant_id, meeting_id, artifact_state,
                       sha256, retention_until, server_side_processing_allowed,
                       processing_region, content_notice_id, consent_snapshot_sha256,
                       finalization_idempotency_key, finalization_request_sha256,
                       finalized_at, finalized_by, version,
                       registration_idempotency_key, registration_request_sha256,
                       registered_at, registered_by, transcript_plan_version,
                       transcript_provider_code, transcript_storage_provider_code,
                       transcript_deletion_command_id, storage_provider, object_key
                  FROM vm_meeting_artifacts
                 WHERE tenant_id = ? AND meeting_id = ? AND artifact_type = 'TRANSCRIPT'
                 FOR UPDATE
                """, this::artifact, tenantId, meetingId).stream().findFirst();
    }

    public TranscriptArtifact registerProcessing(
            TranscriptArtifact current,
            UUID artifactId,
            long tenantId,
            UUID meetingId,
            String sourceSha256,
            OffsetDateTime retentionUntil,
            String processingRegion,
            UUID noticeId,
            String consentSnapshotSha256,
            long contentPlanVersion,
            String providerCode,
            String storageProviderCode,
            String idempotencyKey,
            String requestSha256,
            long actorUserId,
            OffsetDateTime registeredAt) {
        if (current == null) {
            return jdbc.query("""
                    INSERT INTO vm_meeting_artifacts (
                        artifact_id, tenant_id, meeting_id, artifact_type, artifact_state,
                        sha256, retention_until, server_side_processing_allowed,
                        processing_region, content_notice_id, consent_snapshot_sha256,
                        registration_idempotency_key, registration_request_sha256,
                        transcript_plan_version, transcript_provider_code,
                        transcript_storage_provider_code,
                        registered_at, registered_by, created_at, created_by,
                        updated_at, updated_by)
                    VALUES (?, ?, ?, 'TRANSCRIPT', 'PROCESSING', ?, ?, TRUE,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING artifact_id, tenant_id, meeting_id, artifact_state,
                              sha256, retention_until, server_side_processing_allowed,
                              processing_region, content_notice_id, consent_snapshot_sha256,
                              finalization_idempotency_key, finalization_request_sha256,
                              finalized_at, finalized_by, version,
                              registration_idempotency_key, registration_request_sha256,
                              registered_at, registered_by, transcript_plan_version,
                              transcript_provider_code, transcript_storage_provider_code,
                              transcript_deletion_command_id, storage_provider, object_key
                    """, this::artifact,
                    artifactId, tenantId, meetingId, sourceSha256, retentionUntil,
                    processingRegion, noticeId, consentSnapshotSha256,
                    contentPlanVersion, providerCode, storageProviderCode,
                    idempotencyKey, requestSha256, registeredAt, actorUserId,
                    registeredAt, actorUserId, registeredAt, actorUserId)
                    .stream().findFirst().orElseThrow(() -> new BaseException(
                            ErrorCode.RESOURCE_CONFLICT,
                            "The transcript artifact registration conflicted."));
        }
        return jdbc.query("""
                UPDATE vm_meeting_artifacts
                   SET artifact_state = 'PROCESSING', sha256 = ?, retention_until = ?,
                       server_side_processing_allowed = TRUE, processing_region = ?,
                       content_notice_id = ?, consent_snapshot_sha256 = ?,
                       transcript_plan_version = ?, transcript_provider_code = ?,
                       transcript_storage_provider_code = ?,
                       registration_idempotency_key = ?, registration_request_sha256 = ?,
                       registered_at = ?, registered_by = ?,
                       storage_provider = NULL, object_key = NULL, content_type = NULL,
                       size_bytes = NULL, finalization_idempotency_key = NULL,
                       finalization_request_sha256 = NULL, finalized_at = NULL,
                       finalized_by = NULL, transcript_deletion_command_id = NULL,
                       transcript_deleted_at = NULL,
                       transcript_deletion_provider_code = NULL,
                       version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND artifact_id = ?
                   AND artifact_type = 'TRANSCRIPT'
                   AND artifact_state IN ('NONE', 'UNAVAILABLE', 'FAILED')
                   AND registration_idempotency_key IS NULL AND version = ?
                   AND storage_provider IS NULL AND object_key IS NULL
                   AND transcript_deletion_command_id IS NULL
                RETURNING artifact_id, tenant_id, meeting_id, artifact_state,
                          sha256, retention_until, server_side_processing_allowed,
                          processing_region, content_notice_id, consent_snapshot_sha256,
                          finalization_idempotency_key, finalization_request_sha256,
                          finalized_at, finalized_by, version,
                          registration_idempotency_key, registration_request_sha256,
                          registered_at, registered_by, transcript_plan_version,
                          transcript_provider_code, transcript_storage_provider_code,
                          transcript_deletion_command_id, storage_provider, object_key
                """, this::artifact,
                sourceSha256, retentionUntil, processingRegion, noticeId,
                consentSnapshotSha256, contentPlanVersion, providerCode,
                storageProviderCode, idempotencyKey, requestSha256,
                registeredAt, actorUserId, registeredAt, actorUserId,
                tenantId, meetingId, current.artifactId(), current.version())
                .stream().findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The transcript artifact changed. Refresh and retry."));
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
                   AND artifact_state IN ('PROCESSING', 'FAILED')
                   AND registration_idempotency_key IS NOT NULL
                   AND transcript_deletion_command_id IS NULL
                   AND storage_provider IS NULL AND object_key IS NULL
                   AND sha256 = ?
                   AND version = ?
                RETURNING artifact_id, tenant_id, meeting_id, artifact_state,
                          sha256, retention_until, server_side_processing_allowed,
                          processing_region, content_notice_id, consent_snapshot_sha256,
                          finalization_idempotency_key, finalization_request_sha256,
                          finalized_at, finalized_by, version,
                          registration_idempotency_key, registration_request_sha256,
                          registered_at, registered_by, transcript_plan_version,
                          transcript_provider_code, transcript_storage_provider_code,
                          transcript_deletion_command_id, storage_provider, object_key
                """, this::artifact,
                storageProvider, objectKey, contentType, sizeBytes, sourceSha256,
                retentionUntil, processingRegion, noticeId, consentSnapshotSha256,
                idempotencyKey, requestSha256, finalizedAt, actorUserId,
                finalizedAt, actorUserId, current.tenantId(), current.meetingId(),
                current.artifactId(), sourceSha256, current.version())
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
                finalizedByValue, rs.getLong("version"),
                rs.getString("registration_idempotency_key"),
                rs.getString("registration_request_sha256"),
                rs.getObject("registered_at", OffsetDateTime.class),
                rs.getObject("registered_by", Long.class),
                rs.getObject("transcript_plan_version", Long.class),
                rs.getString("transcript_provider_code"),
                rs.getString("transcript_storage_provider_code"),
                rs.getObject("transcript_deletion_command_id", UUID.class),
                rs.getString("storage_provider"), rs.getString("object_key"));
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
            long version,
            String registrationIdempotencyKey,
            String registrationRequestSha256,
            OffsetDateTime registeredAt,
            Long registeredBy,
            Long contentPlanVersion,
            String providerCode,
            String storageProviderCode,
            UUID deletionCommandId,
            String storageProvider,
            String objectKey) {
    }
}
