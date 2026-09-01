package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.CommandState;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.DeletionArtifact;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.DeletionCommand;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionModels.Health;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
class MeetingRecordingDeletionRepository {

    private final JdbcTemplate jdbc;

    MeetingRecordingDeletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Health> claimCycle(
            String workerId,
            UUID fence,
            OffsetDateTime now,
            OffsetDateTime leaseExpiresAt) {
        return jdbc.query("""
                UPDATE vm_meeting_recording_deletion_health
                   SET active_fence = ?, active_lease_expires_at = ?,
                       active_worker_id = ?, last_attempt_at = ?, updated_at = ?
                 WHERE health_key = 'RECORDING_RETENTION'
                   AND (active_fence IS NULL OR active_lease_expires_at <= ?)
                RETURNING *
                """, this::health,
                fence, leaseExpiresAt, workerId, now, now, now)
                .stream().findFirst();
    }

    Optional<Health> health() {
        return jdbc.query("""
                SELECT * FROM vm_meeting_recording_deletion_health
                 WHERE health_key = 'RECORDING_RETENTION'
                """, this::health).stream().findFirst();
    }

    Optional<Health> renewCycle(
            UUID fence,
            String workerId,
            OffsetDateTime previousLeaseExpiresAt,
            OffsetDateTime now,
            OffsetDateTime renewedLeaseExpiresAt) {
        return jdbc.query("""
                UPDATE vm_meeting_recording_deletion_health
                   SET active_lease_expires_at = ?, last_attempt_at = ?, updated_at = ?
                 WHERE health_key = 'RECORDING_RETENTION'
                   AND active_fence = ? AND active_worker_id = ?
                   AND active_lease_expires_at = ? AND active_lease_expires_at > ?
                RETURNING *
                """, this::health,
                renewedLeaseExpiresAt, now, now, fence, workerId,
                previousLeaseExpiresAt, now).stream().findFirst();
    }

    Health healthForUpdate() {
        return jdbc.query("""
                SELECT * FROM vm_meeting_recording_deletion_health
                 WHERE health_key = 'RECORDING_RETENTION'
                 FOR UPDATE
                """, this::health).stream().findFirst()
                .orElseThrow(() -> unavailable("Recording retention health is unavailable."));
    }

    void completeCycle(UUID fence, OffsetDateTime completedAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_recording_deletion_health
                   SET last_success_at = ?, last_failure_at = NULL,
                       last_failure_code = NULL, active_fence = NULL,
                       active_lease_expires_at = NULL, active_worker_id = NULL,
                       updated_at = ?
                 WHERE health_key = 'RECORDING_RETENTION'
                   AND active_fence = ? AND active_lease_expires_at > ?
                """, completedAt, completedAt, fence, completedAt);
        if (updated != 1) throw staleFence();
    }

    void failCycle(UUID fence, String failureCode, OffsetDateTime failedAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_recording_deletion_health
                   SET last_failure_at = ?, last_failure_code = ?,
                       active_fence = NULL, active_lease_expires_at = NULL,
                       active_worker_id = NULL, updated_at = ?
                 WHERE health_key = 'RECORDING_RETENTION'
                   AND active_fence = ? AND active_lease_expires_at > ?
                """, failedAt, failureCode, failedAt, fence, failedAt);
        if (updated != 1) throw staleFence();
    }

    Optional<DeletionArtifact> expiredCandidateForUpdate(OffsetDateTime now) {
        return jdbc.query("""
                SELECT artifact.artifact_id, artifact.tenant_id, artifact.meeting_id,
                       artifact.artifact_state, artifact.storage_provider,
                       artifact.object_key, artifact.content_type, artifact.size_bytes,
                       encode(digest(
                           artifact.storage_provider || ':'
                           || char_length(artifact.object_key)::text || ':'
                           || artifact.object_key, 'sha256'), 'hex')
                           AS deletion_binding_sha256,
                       artifact.retention_until, artifact.version
                  FROM vm_meeting_artifacts artifact
                  LEFT JOIN vm_meeting_recording_deletion_commands command
                    ON command.tenant_id = artifact.tenant_id
                   AND command.meeting_id = artifact.meeting_id
                   AND command.artifact_id = artifact.artifact_id
                 WHERE artifact.artifact_type = 'RECORDING'
                   AND artifact.artifact_state IN (
                       'AVAILABLE', 'UNAVAILABLE', 'FAILED', 'DELETED')
                   AND artifact.storage_provider IS NOT NULL
                   AND artifact.object_key IS NOT NULL
                   AND (artifact.retention_until IS NULL
                        OR artifact.retention_until <= ?)
                   AND (command.deletion_command_id IS NULL
                        OR command.command_state = 'FAILED'
                        OR (command.command_state = 'RUNNING'
                            AND command.lease_expires_at <= ?))
                 ORDER BY artifact.retention_until NULLS FIRST,
                          artifact.updated_at, artifact.artifact_id
                 FOR UPDATE OF artifact SKIP LOCKED
                 LIMIT 1
                """, this::artifact, now, now).stream().findFirst();
    }

    Optional<DeletionArtifact> artifactForUpdate(
            long tenantId, UUID meetingId, UUID artifactId) {
        return jdbc.query("""
                SELECT artifact_id, tenant_id, meeting_id, artifact_state,
                       storage_provider, object_key, content_type, size_bytes,
                       encode(digest(
                           storage_provider || ':' || char_length(object_key)::text
                           || ':' || object_key, 'sha256'), 'hex')
                           AS deletion_binding_sha256,
                       retention_until, version
                  FROM vm_meeting_artifacts
                 WHERE tenant_id = ? AND meeting_id = ? AND artifact_id = ?
                   AND artifact_type = 'RECORDING'
                 FOR UPDATE
                """, this::artifact, tenantId, meetingId, artifactId)
                .stream().findFirst();
    }

    Optional<DeletionCommand> commandForUpdate(
            long tenantId, UUID meetingId, UUID artifactId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_recording_deletion_commands
                 WHERE tenant_id = ? AND meeting_id = ? AND artifact_id = ?
                 FOR UPDATE
                """, this::command, tenantId, meetingId, artifactId)
                .stream().findFirst();
    }

    DeletionCommand insertCommand(
            DeletionArtifact artifact,
            String requestSha256,
            UUID fence,
            String workerId,
            String providerCode,
            OffsetDateTime now,
            OffsetDateTime leaseExpiresAt) {
        return jdbc.query("""
                INSERT INTO vm_meeting_recording_deletion_commands (
                    deletion_command_id, tenant_id, meeting_id, artifact_id,
                    artifact_version, request_sha256, command_state,
                    execution_fence, lease_expires_at, attempt_count,
                    worker_id, provider_code, requested_at)
                VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?, 1, ?, ?, ?)
                RETURNING *
                """, this::command,
                UUID.randomUUID(), artifact.tenantId(), artifact.meetingId(),
                artifact.artifactId(), artifact.version(), requestSha256,
                fence, leaseExpiresAt, workerId, providerCode, now)
                .stream().findFirst().orElseThrow(this::staleFence);
    }

    DeletionCommand reclaim(
            DeletionCommand current,
            UUID fence,
            String workerId,
            OffsetDateTime now,
            OffsetDateTime leaseExpiresAt) {
        return jdbc.query("""
                UPDATE vm_meeting_recording_deletion_commands
                   SET command_state = 'RUNNING', execution_fence = ?,
                       lease_expires_at = ?, attempt_count = attempt_count + 1,
                       worker_id = ?, provider_deletion_id = NULL,
                       failure_code = NULL, completed_at = NULL,
                       requested_at = ?, updated_at = ?
                 WHERE deletion_command_id = ?
                   AND (command_state = 'FAILED'
                        OR (command_state = 'RUNNING' AND lease_expires_at <= ?))
                RETURNING *
                """, this::command,
                fence, leaseExpiresAt, workerId, now, now,
                current.commandId(), now).stream().findFirst()
                .orElseThrow(this::staleFence);
    }

    void markArtifactDeleted(
            DeletionArtifact artifact,
            DeletionCommand command,
            OffsetDateTime deletedAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_artifacts
                   SET artifact_state = 'DELETED', storage_provider = NULL,
                       object_key = NULL, recording_deletion_command_id = ?,
                       recording_deleted_at = ?, recording_deletion_provider_code = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND artifact_id = ?
                   AND artifact_type = 'RECORDING' AND version = ?
                   AND artifact_state IN (
                       'AVAILABLE', 'UNAVAILABLE', 'FAILED', 'DELETED')
                   AND storage_provider = ? AND object_key = ?
                """, command.commandId(), deletedAt, command.providerCode(), deletedAt,
                artifact.tenantId(), artifact.meetingId(), artifact.artifactId(),
                artifact.version(), artifact.storageProvider(), artifact.objectKey());
        if (updated != 1) throw staleFence();
    }

    void succeedCommand(
            DeletionCommand command,
            String providerDeletionId,
            OffsetDateTime completedAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_recording_deletion_commands
                   SET command_state = 'SUCCEEDED', execution_fence = NULL,
                       lease_expires_at = NULL, provider_deletion_id = ?,
                       failure_code = NULL, completed_at = ?, updated_at = ?
                 WHERE deletion_command_id = ? AND command_state = 'RUNNING'
                   AND execution_fence = ? AND lease_expires_at > ?
                """, providerDeletionId, completedAt, completedAt,
                command.commandId(), command.executionFence(), completedAt);
        if (updated != 1) throw staleFence();
    }

    void failCommand(
            DeletionCommand command,
            String failureCode,
            OffsetDateTime failedAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_recording_deletion_commands
                   SET command_state = 'FAILED', execution_fence = NULL,
                       lease_expires_at = NULL, provider_deletion_id = NULL,
                       failure_code = ?, completed_at = ?, updated_at = ?
                 WHERE deletion_command_id = ? AND command_state = 'RUNNING'
                   AND execution_fence = ? AND lease_expires_at > ?
                """, failureCode, failedAt, failedAt,
                command.commandId(), command.executionFence(), failedAt);
        if (updated != 1) throw staleFence();
    }

    int overdueLocatorCount(OffsetDateTime now) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meeting_artifacts
                 WHERE artifact_type = 'RECORDING'
                   AND storage_provider IS NOT NULL AND object_key IS NOT NULL
                   AND (retention_until IS NULL OR retention_until <= ?)
                """, Integer.class, now);
        return count == null ? Integer.MAX_VALUE : count;
    }

    private DeletionArtifact artifact(ResultSet row, int index) throws SQLException {
        long sizeBytes = row.getLong("size_bytes");
        Long nullableSizeBytes = row.wasNull() ? null : sizeBytes;
        return new DeletionArtifact(
                row.getObject("artifact_id", UUID.class), row.getLong("tenant_id"),
                row.getObject("meeting_id", UUID.class), row.getString("artifact_state"),
                row.getString("storage_provider"), row.getString("object_key"),
                row.getString("content_type"), nullableSizeBytes,
                row.getString("deletion_binding_sha256"),
                row.getObject("retention_until", OffsetDateTime.class),
                row.getLong("version"));
    }

    private DeletionCommand command(ResultSet row, int index) throws SQLException {
        return new DeletionCommand(
                row.getObject("deletion_command_id", UUID.class),
                row.getLong("tenant_id"), row.getObject("meeting_id", UUID.class),
                row.getObject("artifact_id", UUID.class), row.getLong("artifact_version"),
                row.getString("request_sha256"),
                CommandState.valueOf(row.getString("command_state")),
                row.getObject("execution_fence", UUID.class),
                row.getObject("lease_expires_at", OffsetDateTime.class),
                row.getInt("attempt_count"), row.getString("worker_id"),
                row.getString("provider_code"), row.getString("provider_deletion_id"),
                row.getString("failure_code"));
    }

    private Health health(ResultSet row, int index) throws SQLException {
        return new Health(
                row.getObject("last_success_at", OffsetDateTime.class),
                row.getObject("last_attempt_at", OffsetDateTime.class),
                row.getObject("last_failure_at", OffsetDateTime.class),
                row.getString("last_failure_code"),
                row.getObject("active_fence", UUID.class),
                row.getObject("active_lease_expires_at", OffsetDateTime.class),
                row.getString("active_worker_id"));
    }

    private BaseException staleFence() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The recording deletion lease changed or expired.");
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }
}
