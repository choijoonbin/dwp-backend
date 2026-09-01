package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingCommandModels.CommandState;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingCommandModels.CommandType;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingCommandModels.ProviderCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
class MeetingRecordingCommandRepository {

    private final JdbcTemplate jdbc;

    MeetingRecordingCommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ProviderCommand> commandForUpdate(
            long tenantId,
            UUID meetingId,
            UUID recordingSessionId,
            CommandType type) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_recording_provider_commands
                 WHERE tenant_id = ? AND meeting_id = ? AND recording_session_id = ?
                   AND command_type = ?
                 FOR UPDATE
                """, this::command,
                tenantId, meetingId, recordingSessionId, type.name())
                .stream().findFirst();
    }

    Optional<ProviderCommand> insert(ProviderCommand command, OffsetDateTime requestedAt) {
        return jdbc.query("""
                INSERT INTO vm_meeting_recording_provider_commands (
                    command_id, tenant_id, meeting_id, recording_session_id,
                    command_type, command_state, actor_user_id, idempotency_key,
                    request_sha256, correlation_id, execution_fence, lease_expires_at,
                    attempt_count, provider_code, requested_at)
                VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING *
                """, this::command,
                command.commandId(), command.tenantId(), command.meetingId(),
                command.recordingSessionId(), command.commandType().name(),
                command.actorUserId(), command.idempotencyKey(), command.requestSha256(),
                command.correlationId(), command.executionFence(), command.leaseExpiresAt(),
                command.providerCode(), requestedAt).stream().findFirst();
    }

    Optional<ProviderCommand> reclaim(
            ProviderCommand command,
            UUID fence,
            OffsetDateTime now,
            OffsetDateTime leaseExpiresAt) {
        return jdbc.query("""
                UPDATE vm_meeting_recording_provider_commands
                   SET command_state = 'RUNNING', execution_fence = ?, lease_expires_at = ?,
                       attempt_count = attempt_count + 1, provider_command_id = NULL,
                       failure_code = NULL, completed_at = NULL, requested_at = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE command_id = ?
                   AND (command_state = 'FAILED'
                        OR (command_state = 'RUNNING' AND lease_expires_at <= ?))
                RETURNING *
                """, this::command, fence, leaseExpiresAt, now, command.commandId(), now)
                .stream().findFirst();
    }

    void succeed(
            ProviderCommand command,
            String providerCommandId,
            OffsetDateTime completedAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_recording_provider_commands
                   SET command_state = 'SUCCEEDED', execution_fence = NULL,
                       lease_expires_at = NULL, provider_command_id = ?, failure_code = NULL,
                       completed_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE command_id = ? AND command_state = 'RUNNING'
                   AND execution_fence = ? AND lease_expires_at > ?
                """, providerCommandId, completedAt, command.commandId(),
                command.executionFence(), completedAt);
        if (updated != 1) throw staleFence();
    }

    void fail(
            ProviderCommand command,
            String failureCode,
            OffsetDateTime failedAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_recording_provider_commands
                   SET command_state = 'FAILED', execution_fence = NULL,
                       lease_expires_at = NULL, provider_command_id = NULL,
                       failure_code = ?, completed_at = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE command_id = ? AND command_state = 'RUNNING'
                   AND execution_fence = ? AND lease_expires_at > ?
                """, failureCode, failedAt, command.commandId(),
                command.executionFence(), failedAt);
        if (updated != 1) throw staleFence();
    }

    private ProviderCommand command(ResultSet row, int index) throws SQLException {
        return new ProviderCommand(
                row.getObject("command_id", UUID.class),
                row.getLong("tenant_id"),
                row.getObject("meeting_id", UUID.class),
                row.getObject("recording_session_id", UUID.class),
                CommandType.valueOf(row.getString("command_type")),
                CommandState.valueOf(row.getString("command_state")),
                row.getLong("actor_user_id"),
                row.getString("idempotency_key"),
                row.getString("request_sha256"),
                row.getString("correlation_id"),
                row.getObject("execution_fence", UUID.class),
                row.getObject("lease_expires_at", OffsetDateTime.class),
                row.getInt("attempt_count"),
                row.getString("provider_code"),
                row.getString("provider_command_id"),
                row.getString("failure_code"));
    }

    private BaseException staleFence() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The governed recording command lease changed. Retry the command.");
    }
}
