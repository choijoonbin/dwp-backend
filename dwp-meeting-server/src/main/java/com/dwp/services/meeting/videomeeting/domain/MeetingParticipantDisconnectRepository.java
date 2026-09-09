package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Repository
class MeetingParticipantDisconnectRepository {
    private final JdbcTemplate jdbc;
    MeetingParticipantDisconnectRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    record Command(UUID commandId, long tenantId, UUID meetingId, UUID participantId,
                   UUID incarnation, String roomName, long participantUserId, long actorUserId,
                   long expectedVersion, String idempotencyKey, String state, UUID leaseToken) { }
    private final RowMapper<Command> mapper = (row, index) -> new Command(
            row.getObject("command_id", UUID.class), row.getLong("tenant_id"),
            row.getObject("meeting_id", UUID.class), row.getObject("participant_id", UUID.class),
            row.getObject("room_incarnation", UUID.class), row.getString("provider_room_name"),
            row.getLong("participant_user_id"), row.getLong("requested_by"),
            row.getLong("expected_participant_version"), row.getString("idempotency_key"),
            row.getString("command_state"), row.getObject("lease_token", UUID.class));
    Optional<Command> find(long tenantId, UUID meetingId, UUID participantId, UUID incarnation) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_participant_disconnects
                 WHERE tenant_id=? AND meeting_id=? AND participant_id=? AND room_incarnation=?
                """, mapper, tenantId, meetingId, participantId, incarnation).stream().findFirst();
    }
    Command insert(Command command) {
        jdbc.update("""
                INSERT INTO vm_meeting_participant_disconnects
                  (command_id,tenant_id,meeting_id,participant_id,room_incarnation,
                   provider_room_name,participant_user_id,requested_by,
                   expected_participant_version,idempotency_key)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, command.commandId(), command.tenantId(), command.meetingId(),
                command.participantId(), command.incarnation(), command.roomName(),
                command.participantUserId(), command.actorUserId(), command.expectedVersion(),
                command.idempotencyKey());
        return command;
    }
    Optional<Command> byKey(long tenantId, String key) {
        return jdbc.query("SELECT * FROM vm_meeting_participant_disconnects WHERE tenant_id=? AND idempotency_key=?",
                mapper, tenantId, key).stream().findFirst();
    }
    boolean deny(Command command) {
        return jdbc.update("""
                UPDATE vm_meeting_participants SET attendance_state='DENIED',
                       version=version+1, updated_at=CURRENT_TIMESTAMP, updated_by=?
                 WHERE tenant_id=? AND meeting_id=? AND participant_id=? AND version=?
                   AND attendance_state IN ('ADMITTED','JOINED','LEFT')
                """, command.actorUserId(), command.tenantId(), command.meetingId(),
                command.participantId(), command.expectedVersion()) == 1;
    }
    Optional<Command> claim(UUID commandId, int maximumAttempts) {
        return jdbc.query("""
                UPDATE vm_meeting_participant_disconnects SET command_state='RUNNING',
                       lease_token=?, lease_expires_at=CURRENT_TIMESTAMP+INTERVAL '30 seconds',
                       attempt_count=attempt_count+1
                 WHERE command_id=? AND attempt_count<?
                   AND (command_state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP
                      OR command_state='RUNNING' AND lease_expires_at<CURRENT_TIMESTAMP)
                RETURNING *
                """, mapper, UUID.randomUUID(), commandId, maximumAttempts).stream().findFirst();
    }
    Optional<Command> claimNext(int maximumAttempts) {
        return jdbc.query("""
                WITH candidate AS (
                  SELECT command_id FROM vm_meeting_participant_disconnects
                   WHERE attempt_count<? AND ((command_state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP)
                      OR (command_state='RUNNING' AND lease_expires_at<CURRENT_TIMESTAMP)
                   )
                   ORDER BY requested_at FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE vm_meeting_participant_disconnects target SET command_state='RUNNING',
                       lease_token=?, lease_expires_at=CURRENT_TIMESTAMP+INTERVAL '30 seconds',
                       attempt_count=attempt_count+1
                  FROM candidate WHERE target.command_id=candidate.command_id RETURNING target.*
                """, mapper, maximumAttempts, UUID.randomUUID()).stream().findFirst();
    }
    boolean complete(Command command) {
        return jdbc.update("""
                UPDATE vm_meeting_participant_disconnects SET command_state='DISCONNECTED',
                       completed_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_expires_at=NULL
                 WHERE command_id=? AND command_state='RUNNING' AND lease_token=?
                """, command.commandId(), command.leaseToken()) == 1;
    }
    void failed(Command command, Duration retryDelay) {
        jdbc.update("""
                UPDATE vm_meeting_participant_disconnects SET command_state='PENDING',
                       next_attempt_at=CURRENT_TIMESTAMP+(? * INTERVAL '1 millisecond'),
                       lease_token=NULL,lease_expires_at=NULL
                 WHERE command_id=? AND command_state='RUNNING' AND lease_token=?
                """, retryDelay.toMillis(), command.commandId(), command.leaseToken());
    }
}
