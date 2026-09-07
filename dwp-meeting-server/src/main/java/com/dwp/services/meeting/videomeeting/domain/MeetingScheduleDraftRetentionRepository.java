package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MeetingScheduleDraftRetentionRepository {

    private static final String HEALTH_KEY = "SCHEDULE_DRAFTS";
    private final JdbcTemplate jdbc;

    public MeetingScheduleDraftRetentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryClaim(
            OffsetDateTime attemptedAt,
            OffsetDateTime leaseExpiresAt,
            UUID fence,
            String workerId) {
        return !jdbc.query("""
                UPDATE vm_meeting_schedule_draft_retention_health
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
        int bounded = Math.max(1, Math.min(1_000, batchSize));
        Integer drafts = jdbc.queryForObject("""
                WITH candidates AS (
                    SELECT draft_id, tenant_id
                      FROM vm_meeting_schedule_drafts
                     WHERE retention_until <= ?
                     ORDER BY retention_until, draft_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), deleted AS (
                    DELETE FROM vm_meeting_schedule_drafts draft
                     USING candidates candidate
                     WHERE draft.tenant_id = candidate.tenant_id
                       AND draft.draft_id = candidate.draft_id
                       AND draft.retention_until <= ?
                    RETURNING draft.draft_id
                ) SELECT COUNT(*)::INTEGER FROM deleted
                """, Integer.class, now, bounded, now);
        Integer receipts = jdbc.queryForObject("""
                WITH candidates AS (
                    SELECT tenant_id, owner_user_id, operation, idempotency_key
                      FROM vm_meeting_schedule_draft_commands
                     WHERE retention_until <= ?
                     ORDER BY retention_until, owner_user_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), deleted AS (
                    DELETE FROM vm_meeting_schedule_draft_commands command
                     USING candidates candidate
                     WHERE command.tenant_id = candidate.tenant_id
                       AND command.owner_user_id = candidate.owner_user_id
                       AND command.operation = candidate.operation
                       AND command.idempotency_key = candidate.idempotency_key
                       AND command.retention_until <= ?
                    RETURNING command.idempotency_key
                ) SELECT COUNT(*)::INTEGER FROM deleted
                """, Integer.class, now, bounded, now);
        boolean overdue = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM vm_meeting_schedule_drafts
                                WHERE retention_until <= ?)
                    OR EXISTS (SELECT 1 FROM vm_meeting_schedule_draft_commands
                                WHERE retention_until <= ?)
                """, Boolean.class, now, now));
        jdbc.update("""
                INSERT INTO vm_meeting_schedule_draft_retention_evidence (
                    execution_id, deleted_draft_count, deleted_receipt_count,
                    fence_token, worker_id, completed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, executionId, drafts == null ? 0 : drafts,
                receipts == null ? 0 : receipts, fence, workerId, now);
        return new PurgeResult(drafts == null ? 0 : drafts,
                receipts == null ? 0 : receipts, overdue);
    }

    public void markSuccess(
            OffsetDateTime succeededAt,
            UUID fence,
            String workerId,
            boolean overdueRemaining) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_schedule_draft_retention_health
                   SET last_success_at = ?, last_failure_at = NULL,
                       last_failure_code = NULL, overdue_remaining = ?,
                       active_fence = NULL, active_worker_id = NULL,
                       active_lease_expires_at = NULL, version = version + 1
                 WHERE health_key = ? AND active_fence = ? AND active_worker_id = ?
                   AND active_lease_expires_at > CURRENT_TIMESTAMP
                """, succeededAt, overdueRemaining, HEALTH_KEY, fence, workerId);
        if (updated != 1) throw new IllegalStateException(
                "Schedule draft retention worker fence was lost.");
    }

    public void markFailure(
            OffsetDateTime failedAt,
            UUID fence,
            String workerId,
            String failureCode) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_schedule_draft_retention_health
                   SET last_failure_at = ?, last_failure_code = ?,
                       overdue_remaining = TRUE, active_fence = NULL,
                       active_worker_id = NULL, active_lease_expires_at = NULL,
                       version = version + 1
                 WHERE health_key = ? AND active_fence = ? AND active_worker_id = ?
                   AND active_lease_expires_at > CURRENT_TIMESTAMP
                """, failedAt, failureCode, HEALTH_KEY, fence, workerId);
        if (updated != 1) throw new IllegalStateException(
                "Schedule draft retention worker fence was lost.");
    }

    public Optional<Health> health() {
        return jdbc.query("""
                SELECT last_attempt_at, last_success_at, last_failure_at,
                       last_failure_code, overdue_remaining, active_fence,
                       active_worker_id, active_lease_expires_at, version
                  FROM vm_meeting_schedule_draft_retention_health
                 WHERE health_key = ?
                """, (row, number) -> new Health(
                        row.getObject("last_attempt_at", OffsetDateTime.class),
                        row.getObject("last_success_at", OffsetDateTime.class),
                        row.getObject("last_failure_at", OffsetDateTime.class),
                        row.getString("last_failure_code"), row.getBoolean("overdue_remaining"),
                        row.getObject("active_fence", UUID.class),
                        row.getString("active_worker_id"),
                        row.getObject("active_lease_expires_at", OffsetDateTime.class),
                        row.getLong("version")), HEALTH_KEY).stream().findFirst();
    }

    private void assertActiveLease(UUID fence, String workerId) {
        if (jdbc.query("""
                SELECT health_key
                  FROM vm_meeting_schedule_draft_retention_health
                 WHERE health_key = ? AND active_fence = ? AND active_worker_id = ?
                   AND active_lease_expires_at > CURRENT_TIMESTAMP
                 FOR UPDATE
                """, (row, number) -> row.getString("health_key"),
                HEALTH_KEY, fence, workerId).isEmpty()) {
            throw new IllegalStateException("Schedule draft retention fence is not active.");
        }
    }

    public record PurgeResult(int deletedDraftCount, int deletedReceiptCount,
                              boolean overdueRemaining) {
        public int deletedCount() { return deletedDraftCount + deletedReceiptCount; }
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
            long version) {
    }
}
