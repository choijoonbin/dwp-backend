package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.MediaOperation;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.OperationState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.OperationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
class VideoMeetingLifecycleOperationRepository {

    private final JdbcTemplate jdbc;

    VideoMeetingLifecycleOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<MediaOperation> commandForUpdate(
            long tenantId,
            UUID meetingId,
            long actorUserId,
            OperationType type,
            String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_media_operations
                 WHERE tenant_id = ? AND meeting_id = ? AND actor_user_id = ?
                   AND operation_type = ? AND idempotency_key = ?
                 FOR UPDATE
                """, this::operation,
                tenantId, meetingId, actorUserId, type.name(), idempotencyKey)
                .stream().findFirst();
    }

    Optional<MediaOperation> activeForUpdate(
            long tenantId, UUID meetingId, OperationType type) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_media_operations
                 WHERE tenant_id = ? AND meeting_id = ? AND operation_type = ?
                   AND operation_state = 'RUNNING'
                 FOR UPDATE
                """, this::operation, tenantId, meetingId, type.name())
                .stream().findFirst();
    }

    Optional<MediaOperation> insert(MediaOperation operation, OffsetDateTime requestedAt) {
        return jdbc.query("""
                INSERT INTO vm_meeting_media_operations (
                    operation_id, tenant_id, meeting_id, operation_type, operation_state,
                    actor_user_id, expected_meeting_version, idempotency_key,
                    request_sha256, correlation_id, execution_fence, lease_expires_at,
                    attempt_count, provider_code, provider_room_name, requested_at)
                VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING *
                """, this::operation,
                operation.operationId(), operation.tenantId(), operation.meetingId(),
                operation.operationType().name(), operation.actorUserId(),
                operation.expectedMeetingVersion(), operation.idempotencyKey(),
                operation.requestSha256(), operation.correlationId(),
                operation.executionFence(), operation.leaseExpiresAt(),
                operation.providerCode(), operation.providerRoomName(), requestedAt)
                .stream().findFirst();
    }

    Optional<MediaOperation> reclaim(
            MediaOperation operation,
            UUID fence,
            OffsetDateTime now,
            OffsetDateTime leaseExpiresAt) {
        return jdbc.query("""
                UPDATE vm_meeting_media_operations
                   SET operation_state = 'RUNNING', execution_fence = ?,
                       lease_expires_at = ?, attempt_count = attempt_count + 1,
                       last_failure_code = NULL, completed_at = NULL,
                       requested_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE operation_id = ?
                   AND (operation_state = 'FAILED'
                        OR (operation_state = 'RUNNING' AND lease_expires_at <= ?))
                RETURNING *
                """, this::operation, fence, leaseExpiresAt, now,
                operation.operationId(), now).stream().findFirst();
    }

    boolean expireActive(MediaOperation operation, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE vm_meeting_media_operations
                   SET operation_state = 'FAILED', completed_at = ?,
                       last_failure_code = 'LEASE_EXPIRED', updated_at = CURRENT_TIMESTAMP
                 WHERE operation_id = ? AND operation_state = 'RUNNING'
                   AND execution_fence = ? AND lease_expires_at <= ?
                """, now, operation.operationId(), operation.executionFence(), now) == 1;
    }

    void failProvider(MediaOperation operation, OffsetDateTime failedAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_media_operations
                   SET operation_state = 'FAILED', completed_at = ?,
                       last_failure_code = 'MEDIA_PROVIDER_FAILURE',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE operation_id = ? AND operation_state = 'RUNNING'
                   AND execution_fence = ? AND lease_expires_at > ?
                """, failedAt, operation.operationId(), operation.executionFence(), failedAt);
        if (updated != 1) throw staleFence();
    }

    void succeed(
            MediaOperation operation,
            OffsetDateTime completedAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_media_operations
                   SET operation_state = 'SUCCEEDED', completed_at = ?,
                       last_failure_code = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE operation_id = ? AND operation_state = 'RUNNING'
                   AND execution_fence = ? AND lease_expires_at > ?
                """, completedAt, operation.operationId(), operation.executionFence(),
                completedAt);
        if (updated != 1) throw staleFence();
    }

    private MediaOperation operation(ResultSet row, int index) throws SQLException {
        return new MediaOperation(
                row.getObject("operation_id", UUID.class),
                row.getLong("tenant_id"),
                row.getObject("meeting_id", UUID.class),
                OperationType.valueOf(row.getString("operation_type")),
                OperationState.valueOf(row.getString("operation_state")),
                row.getLong("actor_user_id"),
                row.getLong("expected_meeting_version"),
                row.getString("idempotency_key"),
                row.getString("request_sha256"),
                row.getString("correlation_id"),
                row.getObject("execution_fence", UUID.class),
                row.getObject("lease_expires_at", OffsetDateTime.class),
                row.getInt("attempt_count"),
                row.getString("provider_code"),
                row.getString("provider_room_name"));
    }

    private BaseException staleFence() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The meeting media operation lease changed. Retry the command.");
    }
}
