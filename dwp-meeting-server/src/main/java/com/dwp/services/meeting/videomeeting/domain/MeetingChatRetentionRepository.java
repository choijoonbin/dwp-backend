package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MeetingChatRetentionRepository {

    private static final String HEALTH_KEY = "CHAT_RETENTION";
    private final JdbcTemplate jdbc;

    public MeetingChatRetentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryClaim(
            OffsetDateTime attemptedAt,
            OffsetDateTime leaseExpiresAt,
            UUID fence,
            String workerId) {
        return !jdbc.query("""
                UPDATE vm_meeting_chat_retention_health
                   SET last_failure_at = CASE
                           WHEN active_fence IS NOT NULL THEN ? ELSE last_failure_at END,
                       last_failure_code = CASE
                           WHEN active_fence IS NOT NULL
                           THEN 'RETENTION_LEASE_EXPIRED' ELSE last_failure_code END,
                       last_attempt_at = ?, active_fence = ?, active_worker_id = ?,
                       active_lease_expires_at = ?, version = version + 1
                 WHERE health_key = ?
                   AND (active_fence IS NULL OR active_lease_expires_at <= ?)
                RETURNING health_key
                """, (row, number) -> row.getString("health_key"),
                attemptedAt, attemptedAt, fence, workerId, leaseExpiresAt,
                HEALTH_KEY, attemptedAt).isEmpty();
    }

    public PurgeResult purgeExpired(
            OffsetDateTime now,
            int batchSize,
            UUID executionId,
            UUID fence,
            String workerId) {
        assertActiveLease(fence, workerId);
        List<PurgedMessage> candidates = jdbc.query("""
                SELECT message.tenant_id, message.meeting_id, message.message_id
                  FROM vm_meeting_chat_messages message
                  JOIN vm_meetings meeting
                    ON meeting.tenant_id = message.tenant_id
                   AND meeting.meeting_id = message.meeting_id
                 WHERE message.retention_until <= ?
                   AND meeting.lifecycle_state <> 'LIVE'
                   AND NOT EXISTS (
                       SELECT 1 FROM vm_meeting_chat_retention_evidence evidence
                        WHERE evidence.tenant_id = message.tenant_id
                          AND evidence.meeting_id = message.meeting_id
                          AND evidence.message_id = message.message_id)
                 ORDER BY message.retention_until, message.message_id
                 FOR UPDATE OF message SKIP LOCKED
                 LIMIT ?
                """, (row, number) -> new PurgedMessage(
                        row.getLong("tenant_id"),
                        row.getObject("meeting_id", UUID.class),
                        row.getObject("message_id", UUID.class)),
                now, Math.max(1, Math.min(1_000, batchSize)));
        List<PurgedMessage> purged = new ArrayList<>();
        for (PurgedMessage candidate : candidates) {
            int updated = jdbc.update("""
                    UPDATE vm_meeting_chat_messages message
                       SET message_state = 'DELETED', message_text = NULL,
                           deleted_at = COALESCE(deleted_at, ?), deleted_by = 0,
                           deletion_reason = 'RETENTION_EXPIRED', updated_at = ?
                     WHERE message.tenant_id = ? AND message.meeting_id = ?
                       AND message.message_id = ?
                       AND message.retention_until <= ?
                       AND NOT EXISTS (
                           SELECT 1 FROM vm_meeting_chat_retention_evidence evidence
                            WHERE evidence.tenant_id = message.tenant_id
                              AND evidence.meeting_id = message.meeting_id
                              AND evidence.message_id = message.message_id)
                       AND EXISTS (
                           SELECT 1 FROM vm_meetings meeting
                            WHERE meeting.tenant_id = message.tenant_id
                              AND meeting.meeting_id = message.meeting_id
                              AND meeting.lifecycle_state <> 'LIVE')
                    """, now, now, candidate.tenantId(), candidate.meetingId(),
                    candidate.messageId(), now);
            if (updated != 1) continue;
            jdbc.update("""
                    DELETE FROM vm_meeting_collaboration_commands
                     WHERE tenant_id = ? AND meeting_id = ?
                       AND result_resource_id = ?
                       AND command_type IN ('CHAT_SEND', 'CHAT_DELETE')
                    """, candidate.tenantId(), candidate.meetingId(), candidate.messageId());
            jdbc.update("""
                    INSERT INTO vm_meeting_chat_retention_evidence (
                        deletion_id, execution_id, tenant_id, meeting_id, message_id,
                        deletion_reason, fence_token, worker_id, deleted_at)
                    VALUES (?, ?, ?, ?, ?, 'RETENTION_EXPIRED', ?, ?, ?)
                    """, UUID.randomUUID(), executionId, candidate.tenantId(),
                    candidate.meetingId(), candidate.messageId(), fence, workerId, now);
            purged.add(candidate);
        }
        Boolean overdueRemaining = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM vm_meeting_chat_messages message
                      JOIN vm_meetings meeting
                        ON meeting.tenant_id = message.tenant_id
                       AND meeting.meeting_id = message.meeting_id
                     WHERE message.retention_until <= ?
                       AND meeting.lifecycle_state <> 'LIVE'
                       AND NOT EXISTS (
                           SELECT 1 FROM vm_meeting_chat_retention_evidence evidence
                            WHERE evidence.tenant_id = message.tenant_id
                              AND evidence.meeting_id = message.meeting_id
                              AND evidence.message_id = message.message_id))
                """, Boolean.class, now);
        return new PurgeResult(List.copyOf(purged), Boolean.TRUE.equals(overdueRemaining));
    }

    public void markSuccess(
            OffsetDateTime succeededAt,
            UUID fence,
            String workerId,
            boolean overdueRemaining) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_chat_retention_health
                   SET last_success_at = ?,
                       last_failure_at = NULL, last_failure_code = NULL,
                       overdue_remaining = ?, active_fence = NULL,
                       active_worker_id = NULL, active_lease_expires_at = NULL,
                       version = version + 1
                 WHERE health_key = ? AND active_fence = ? AND active_worker_id = ?
                   AND active_lease_expires_at > CURRENT_TIMESTAMP
                """, succeededAt, overdueRemaining, HEALTH_KEY, fence, workerId);
        if (updated != 1) throw new IllegalStateException("Chat retention worker fence was lost.");
    }

    public void markFailure(
            OffsetDateTime failedAt,
            UUID fence,
            String workerId,
            String failureCode) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_chat_retention_health
                   SET last_failure_at = ?, last_failure_code = ?,
                       overdue_remaining = TRUE, active_fence = NULL,
                       active_worker_id = NULL, active_lease_expires_at = NULL,
                       version = version + 1
                 WHERE health_key = ? AND active_fence = ? AND active_worker_id = ?
                   AND active_lease_expires_at > CURRENT_TIMESTAMP
                """, failedAt, failureCode, HEALTH_KEY, fence, workerId);
        if (updated != 1) throw new IllegalStateException("Chat retention worker fence was lost.");
    }

    public Optional<Health> health() {
        return jdbc.query("""
                SELECT last_attempt_at, last_success_at, last_failure_at,
                       last_failure_code, overdue_remaining, active_fence,
                       active_worker_id, active_lease_expires_at, version
                  FROM vm_meeting_chat_retention_health
                 WHERE health_key = ?
                """, (row, number) -> new Health(
                        row.getObject("last_attempt_at", OffsetDateTime.class),
                        row.getObject("last_success_at", OffsetDateTime.class),
                        row.getObject("last_failure_at", OffsetDateTime.class),
                        row.getString("last_failure_code"),
                        row.getBoolean("overdue_remaining"),
                        row.getObject("active_fence", UUID.class),
                        row.getString("active_worker_id"),
                        row.getObject("active_lease_expires_at", OffsetDateTime.class),
                        row.getLong("version")), HEALTH_KEY).stream().findFirst();
    }

    private void assertActiveLease(UUID fence, String workerId) {
        List<String> lease = jdbc.query("""
                SELECT health_key
                  FROM vm_meeting_chat_retention_health
                 WHERE health_key = ? AND active_fence = ? AND active_worker_id = ?
                   AND active_lease_expires_at > CURRENT_TIMESTAMP
                 FOR UPDATE
                """, (row, number) -> row.getString("health_key"),
                HEALTH_KEY, fence, workerId);
        if (lease.isEmpty()) throw new IllegalStateException("Chat retention worker fence is not active.");
    }

    public record PurgedMessage(long tenantId, UUID meetingId, UUID messageId) {}

    public record PurgeResult(List<PurgedMessage> messages, boolean overdueRemaining) {
        public int deletedCount() { return messages.size(); }
    }

    public record Health(
            OffsetDateTime lastAttemptAt,
            OffsetDateTime lastSuccessAt,
            OffsetDateTime lastFailureAt,
            String lastFailureCode,
            boolean overdueRemaining,
            UUID activeFence,
            String activeWorkerId,
            OffsetDateTime activeLeaseExpiresAt,
            long version) {}
}
