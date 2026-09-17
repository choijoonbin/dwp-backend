package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
class MailFollowUpDueNotificationRepository {

    private final JdbcTemplate jdbc;

    MailFollowUpDueNotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<DueFollowUp> claimDue(OffsetDateTime now, int limit) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT tracker.tenant_id, tracker.follow_up_id,
                           tracker.owner_user_id, tracker.thread_id,
                           tracker.version AS follow_up_version,
                           tracker.expected_reply_at
                      FROM mail_follow_up_trackers tracker
                     WHERE tracker.tracker_status IN ('WAITING', 'OVERDUE')
                       AND tracker.expected_reply_at <= ?
                       AND NOT EXISTS (
                           SELECT 1
                             FROM mail_follow_up_notification_ledger ledger
                            WHERE ledger.tenant_id = tracker.tenant_id
                              AND ledger.follow_up_id = tracker.follow_up_id
                              AND ledger.follow_up_version = tracker.version
                              AND ledger.expected_reply_at = tracker.expected_reply_at)
                     ORDER BY tracker.expected_reply_at, tracker.follow_up_id
                     FOR UPDATE OF tracker SKIP LOCKED
                     LIMIT ?
                ), claimed AS (
                    INSERT INTO mail_follow_up_notification_ledger (
                        ledger_id, tenant_id, follow_up_id, owner_user_id,
                        thread_id, follow_up_version, expected_reply_at,
                        decision, claimed_at)
                    SELECT gen_random_uuid(), candidate.tenant_id, candidate.follow_up_id,
                           candidate.owner_user_id, candidate.thread_id,
                           candidate.follow_up_version, candidate.expected_reply_at,
                           'CLAIMED', ?
                      FROM candidates candidate
                    ON CONFLICT (tenant_id, follow_up_id, follow_up_version, expected_reply_at)
                    DO NOTHING
                    RETURNING tenant_id, follow_up_id, owner_user_id, thread_id,
                              follow_up_version, expected_reply_at
                )
                SELECT tenant_id, follow_up_id, owner_user_id, thread_id,
                       follow_up_version, expected_reply_at
                  FROM claimed
                 ORDER BY expected_reply_at, follow_up_id
                """, (result, ignored) -> new DueFollowUp(
                        result.getLong("tenant_id"),
                        result.getObject("follow_up_id", UUID.class),
                        result.getLong("owner_user_id"),
                        result.getObject("thread_id", UUID.class),
                        result.getLong("follow_up_version"),
                        result.getObject("expected_reply_at", OffsetDateTime.class)),
                now, limit, now);
    }

    void complete(DueFollowUp followUp, UUID eventId, OffsetDateTime completedAt) {
        String decision = eventId == null ? "SUPPRESSED" : "EMITTED";
        int updated = jdbc.update("""
                UPDATE mail_follow_up_notification_ledger
                   SET decision = ?, domain_event_id = ?, completed_at = ?
                 WHERE tenant_id = ? AND follow_up_id = ?
                   AND follow_up_version = ? AND expected_reply_at = ?
                   AND decision = 'CLAIMED' AND completed_at IS NULL
                """, decision, eventId, completedAt,
                followUp.tenantId(), followUp.followUpId(), followUp.version(),
                followUp.expectedReplyAt());
        if (updated != 1) {
            throw new IllegalStateException("The follow-up notification claim was lost.");
        }
        int observed = jdbc.update("""
                UPDATE mail_follow_up_trackers
                   SET tracker_status = CASE
                           WHEN tracker_status = 'WAITING' THEN 'OVERDUE'
                           ELSE tracker_status
                       END,
                       last_checked_at = ?
                 WHERE tenant_id = ? AND follow_up_id = ?
                   AND owner_user_id = ? AND thread_id = ?
                   AND version = ? AND expected_reply_at = ?
                   AND tracker_status IN ('WAITING', 'OVERDUE')
                """, completedAt, followUp.tenantId(), followUp.followUpId(),
                followUp.ownerUserId(), followUp.threadId(), followUp.version(),
                followUp.expectedReplyAt());
        if (observed != 1) {
            throw new IllegalStateException("The due follow-up changed while publishing its notification.");
        }
    }

    record DueFollowUp(
            long tenantId,
            UUID followUpId,
            long ownerUserId,
            UUID threadId,
            long version,
            OffsetDateTime expectedReplyAt) { }
}
