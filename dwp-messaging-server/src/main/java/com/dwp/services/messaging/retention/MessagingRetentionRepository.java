package com.dwp.services.messaging.retention;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class MessagingRetentionRepository {

    private final JdbcTemplate jdbc;

    MessagingRetentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<TenantRetentionPolicy> tenantPolicies() {
        return jdbc.query("""
                SELECT tenant_id, retention_days
                  FROM msg_tenant_policies
                 ORDER BY tenant_id
                """, (row, ignored) -> new TenantRetentionPolicy(
                row.getLong("tenant_id"), row.getInt("retention_days")));
    }

    Optional<UUID> tryStartRun(
            long tenantId,
            String executionMode,
            OffsetDateTime cutoff,
            UUID owner,
            Duration leaseDuration) {
        jdbc.update("""
                UPDATE msg_retention_runs
                   SET state = 'FAILED', failure_code = 'LEASE_EXPIRED',
                       finished_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND state = 'RUNNING'
                   AND lease_expires_at <= CURRENT_TIMESTAMP
                """, tenantId);
        UUID runId = UUID.randomUUID();
        try {
            int inserted = jdbc.update("""
                    INSERT INTO msg_retention_runs (
                        run_id, tenant_id, execution_mode, cutoff_at,
                        lease_owner, lease_expires_at)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'))
                    """, runId, tenantId, executionMode, cutoff, owner,
                    leaseDuration.toMillis());
            return inserted == 1 ? Optional.of(runId) : Optional.empty();
        } catch (DuplicateKeyException duplicateActiveRun) {
            return Optional.empty();
        }
    }

    boolean renewLease(UUID runId, UUID owner, Duration leaseDuration) {
        return jdbc.update("""
                UPDATE msg_retention_runs
                   SET lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond')
                 WHERE run_id = ? AND lease_owner = ? AND state = 'RUNNING'
                """, leaseDuration.toMillis(), runId, owner) == 1;
    }

    CandidateStats candidateStats(long tenantId, OffsetDateTime cutoff) {
        return jdbc.queryForObject("""
                WITH candidates AS (
                    SELECT message.conversation_id
                      FROM msg_messages message
                     WHERE message.tenant_id = ?
                       AND message.created_at < ?
                       AND message.retention_purged_at IS NULL
                    UNION ALL
                    SELECT draft.conversation_id
                      FROM msg_message_drafts draft
                     WHERE draft.tenant_id = ? AND draft.updated_at < ?
                    UNION ALL
                    SELECT scheduled.conversation_id
                      FROM msg_scheduled_messages scheduled
                     WHERE scheduled.tenant_id = ? AND scheduled.updated_at < ?
                       AND scheduled.status IN ('SENT', 'FAILED', 'CANCELLED')
                       AND scheduled.payload_purged_at IS NULL
                )
                SELECT COUNT(*) AS candidate_count,
                       COUNT(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM msg_retention_holds hold
                            WHERE hold.tenant_id = ? AND hold.state = 'ACTIVE'
                              AND (hold.conversation_id IS NULL
                                   OR hold.conversation_id = candidates.conversation_id)
                       )) AS held_count
                  FROM candidates
                """, (row, ignored) -> new CandidateStats(
                row.getLong("candidate_count"), row.getLong("held_count")),
                tenantId, cutoff, tenantId, cutoff, tenantId, cutoff, tenantId);
    }

    ContinuityPurgeResult purgeContinuityContent(
            long tenantId, OffsetDateTime cutoff, int limit) {
        jdbc.execute("LOCK TABLE msg_retention_holds IN SHARE MODE");
        int deletedDrafts = jdbc.update("""
                WITH candidates AS (
                    SELECT draft.draft_id
                      FROM msg_message_drafts draft
                     WHERE draft.tenant_id = ? AND draft.updated_at < ?
                       AND NOT EXISTS (
                           SELECT 1 FROM msg_retention_holds hold
                            WHERE hold.tenant_id = draft.tenant_id
                              AND hold.state = 'ACTIVE'
                              AND (hold.conversation_id IS NULL
                                   OR hold.conversation_id = draft.conversation_id))
                     ORDER BY draft.updated_at, draft.draft_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                DELETE FROM msg_message_drafts draft
                 USING candidates
                 WHERE draft.draft_id = candidates.draft_id
                """, tenantId, cutoff, limit);
        int redactedSchedules = jdbc.update("""
                WITH candidates AS (
                    SELECT scheduled.scheduled_message_id
                      FROM msg_scheduled_messages scheduled
                     WHERE scheduled.tenant_id = ? AND scheduled.updated_at < ?
                       AND scheduled.status IN ('SENT', 'FAILED', 'CANCELLED')
                       AND scheduled.payload_purged_at IS NULL
                       AND NOT EXISTS (
                           SELECT 1 FROM msg_retention_holds hold
                            WHERE hold.tenant_id = scheduled.tenant_id
                              AND hold.state = 'ACTIVE'
                              AND (hold.conversation_id IS NULL
                                   OR hold.conversation_id = scheduled.conversation_id))
                     ORDER BY scheduled.updated_at, scheduled.scheduled_message_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                UPDATE msg_scheduled_messages scheduled
                   SET body = '', mentioned_user_ids = '{}'::BIGINT[],
                       failure_detail = NULL,
                       request_hash = encode(digest(
                           'msg-scheduled-payload-purged:'
                               || scheduled.scheduled_message_id::text,
                           'sha256'), 'hex'),
                       payload_purged_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                  FROM candidates
                 WHERE scheduled.scheduled_message_id = candidates.scheduled_message_id
                """, tenantId, cutoff, limit);
        return new ContinuityPurgeResult(deletedDrafts, redactedSchedules);
    }

    List<UUID> nextCandidates(long tenantId, OffsetDateTime cutoff, int limit) {
        return jdbc.query("""
                SELECT message.message_id
                  FROM msg_messages message
                 WHERE message.tenant_id = ?
                   AND message.created_at < ?
                   AND message.retention_purged_at IS NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM msg_retention_holds hold
                        WHERE hold.tenant_id = message.tenant_id
                          AND hold.state = 'ACTIVE'
                          AND (hold.conversation_id IS NULL
                               OR hold.conversation_id = message.conversation_id)
                   )
                 ORDER BY message.created_at, message.message_id
                 LIMIT ?
                """, (row, ignored) -> row.getObject("message_id", UUID.class),
                tenantId, cutoff, limit);
    }

    Optional<LockedMessage> lockEligibleMessage(
            long tenantId, UUID messageId, OffsetDateTime cutoff) {
        jdbc.execute("LOCK TABLE msg_retention_holds IN SHARE MODE");
        return jdbc.query("""
                SELECT message.message_id, message.conversation_id
                  FROM msg_messages message
                 WHERE message.tenant_id = ? AND message.message_id = ?
                   AND message.created_at < ?
                   AND message.retention_purged_at IS NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM msg_retention_holds hold
                        WHERE hold.tenant_id = message.tenant_id
                          AND hold.state = 'ACTIVE'
                          AND (hold.conversation_id IS NULL
                               OR hold.conversation_id = message.conversation_id)
                   )
                 FOR UPDATE
                """, (row, ignored) -> new LockedMessage(
                row.getObject("message_id", UUID.class),
                row.getObject("conversation_id", UUID.class)),
                tenantId, messageId, cutoff).stream().findFirst();
    }

    List<PendingAttachmentDeletion> pendingAttachmentDeletions(long tenantId, int limit) {
        return jdbc.query("""
                SELECT deletion_id, object_key
                  FROM msg_retention_attachment_deletions
                 WHERE tenant_id = ? AND state = 'PENDING'
                 ORDER BY created_at, deletion_id
                 LIMIT ?
                """, (row, ignored) -> new PendingAttachmentDeletion(
                row.getObject("deletion_id", UUID.class), row.getString("object_key")),
                tenantId, limit);
    }

    long pendingAttachmentDeletionCount(long tenantId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM msg_retention_attachment_deletions
                 WHERE tenant_id = ? AND state = 'PENDING'
                """, Long.class, tenantId);
        return count == null ? 0 : count;
    }

    RetentionHealthSummary retentionHealth(OffsetDateTime staleBefore) {
        return jdbc.queryForObject("""
                WITH policy_evidence AS (
                    SELECT policy.tenant_id,
                           latest.state AS latest_state,
                           success.finished_at AS last_success_at,
                           COALESCE(backlog.pending_count, 0) AS pending_count
                      FROM msg_tenant_policies policy
                      LEFT JOIN LATERAL (
                          SELECT run.state
                            FROM msg_retention_runs run
                           WHERE run.tenant_id = policy.tenant_id
                           ORDER BY run.started_at DESC, run.run_id DESC
                           LIMIT 1
                      ) latest ON TRUE
                      LEFT JOIN LATERAL (
                          SELECT run.finished_at
                            FROM msg_retention_runs run
                           WHERE run.tenant_id = policy.tenant_id
                             AND run.execution_mode = 'ENFORCING'
                             AND run.state = 'COMPLETED'
                           ORDER BY run.finished_at DESC, run.run_id DESC
                           LIMIT 1
                      ) success ON TRUE
                      LEFT JOIN LATERAL (
                          SELECT COUNT(*) AS pending_count
                            FROM msg_retention_attachment_deletions deletion
                           WHERE deletion.tenant_id = policy.tenant_id
                             AND deletion.state = 'PENDING'
                      ) backlog ON TRUE
                )
                SELECT COUNT(*) AS tenant_count,
                       COUNT(*) FILTER (
                           WHERE latest_state IN ('FAILED', 'BLOCKED')) AS failed_tenant_count,
                       COUNT(*) FILTER (WHERE pending_count > 0) AS backlog_tenant_count,
                       COALESCE(SUM(pending_count), 0) AS pending_attachment_count,
                       COUNT(*) FILTER (WHERE last_success_at IS NULL) AS missing_success_count,
                       COUNT(*) FILTER (
                           WHERE last_success_at IS NOT NULL AND last_success_at < ?)
                           AS stale_success_count,
                       MAX(last_success_at) AS last_success_at
                  FROM policy_evidence
                """, (row, ignored) -> new RetentionHealthSummary(
                row.getLong("tenant_count"),
                row.getLong("failed_tenant_count"),
                row.getLong("backlog_tenant_count"),
                row.getLong("pending_attachment_count"),
                row.getLong("missing_success_count"),
                row.getLong("stale_success_count"),
                row.getObject("last_success_at", OffsetDateTime.class)), staleBefore);
    }

    void completeAttachmentDeletion(UUID deletionId) {
        jdbc.update("""
                UPDATE msg_retention_attachment_deletions
                   SET state = 'COMPLETED', attempt_count = attempt_count + 1,
                       object_key = 'deleted/' || deletion_id::text,
                       last_error_code = NULL, completed_at = CURRENT_TIMESTAMP
                 WHERE deletion_id = ? AND state = 'PENDING'
                """, deletionId);
    }

    void failAttachmentDeletion(UUID deletionId, String failureCode) {
        jdbc.update("""
                UPDATE msg_retention_attachment_deletions
                   SET attempt_count = attempt_count + 1, last_error_code = ?
                 WHERE deletion_id = ? AND state = 'PENDING'
                """, failureCode, deletionId);
    }

    int purgeMessageContent(long tenantId, UUID runId, UUID messageId) {
        jdbc.update("""
                INSERT INTO msg_retention_attachment_deletions (
                    run_id, tenant_id, message_id, object_key)
                SELECT ?, tenant_id, message_id, object_key
                  FROM msg_attachments
                 WHERE tenant_id = ? AND message_id = ?
                ON CONFLICT (tenant_id, object_key) DO NOTHING
                """, runId, tenantId, messageId);
        jdbc.update("DELETE FROM msg_realtime_events WHERE tenant_id = ? AND message_id = ?",
                tenantId, messageId);
        jdbc.update("DELETE FROM msg_message_reactions WHERE tenant_id = ? AND message_id = ?",
                tenantId, messageId);
        jdbc.update("DELETE FROM msg_saved_items WHERE tenant_id = ? AND message_id = ?",
                tenantId, messageId);
        jdbc.update("DELETE FROM msg_message_mentions WHERE tenant_id = ? AND message_id = ?",
                tenantId, messageId);
        jdbc.update("DELETE FROM msg_message_read_observations WHERE tenant_id = ? AND message_id = ?",
                tenantId, messageId);
        jdbc.update("DELETE FROM msg_idempotency_keys WHERE tenant_id = ? AND result_message_id = ?",
                tenantId, messageId);
        jdbc.update("DELETE FROM msg_attachments WHERE tenant_id = ? AND message_id = ?",
                tenantId, messageId);
        return jdbc.update("""
                UPDATE msg_messages
                   SET body = '', sender_name = 'Retained message',
                       sender_person_public_id = NULL, edited_at = NULL,
                       deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP),
                       retention_purged_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE tenant_id = ? AND message_id = ?
                   AND retention_purged_at IS NULL
                """, tenantId, messageId);
    }

    boolean finishRun(
            UUID runId,
            UUID owner,
            String state,
            long candidateCount,
            long purgedCount,
            long skippedHoldCount,
            long pendingAttachmentDeletionCount,
            String failureCode) {
        return jdbc.update("""
                UPDATE msg_retention_runs
                   SET state = ?, candidate_count = ?, purged_count = ?,
                       skipped_hold_count = ?, attachment_deletion_pending_count = ?,
                       failure_code = ?,
                       finished_at = CURRENT_TIMESTAMP
                 WHERE run_id = ? AND lease_owner = ? AND state = 'RUNNING'
                """, state, candidateCount, purgedCount, skippedHoldCount,
                pendingAttachmentDeletionCount, failureCode, runId, owner) == 1;
    }

    void recordAudit(
            long tenantId,
            UUID runId,
            String state,
            long candidateCount,
            long purgedCount,
            long skippedHoldCount,
            long pendingAttachmentDeletionCount,
            String failureCode) {
        jdbc.update("""
                INSERT INTO msg_audit_events (
                    tenant_id, actor_user_id, event_type, object_type, object_id,
                    after_state, correlation_id)
                VALUES (?, NULL, 'messaging.retention.run.finished',
                        'MSG_RETENTION_RUN', ?,
                        jsonb_build_object(
                            'state', ?, 'candidateCount', ?, 'purgedCount', ?,
                            'skippedHoldCount', ?,
                            'attachmentDeletionPendingCount', ?,
                            'failureCode', CAST(? AS VARCHAR)), ?)
                """, tenantId, runId.toString(), state, candidateCount, purgedCount,
                skippedHoldCount, pendingAttachmentDeletionCount, failureCode,
                "retention:" + runId);
    }

    RetentionRun latestRun(long tenantId) {
        return jdbc.query("""
                SELECT run_id, execution_mode, state, cutoff_at,
                       candidate_count, purged_count, skipped_hold_count,
                       attachment_deletion_pending_count, failure_code,
                       started_at, finished_at
                  FROM msg_retention_runs
                 WHERE tenant_id = ?
                 ORDER BY started_at DESC, run_id DESC
                 LIMIT 1
                """, (row, ignored) -> new RetentionRun(
                row.getObject("run_id", UUID.class),
                row.getString("execution_mode"), row.getString("state"),
                row.getObject("cutoff_at", OffsetDateTime.class),
                row.getLong("candidate_count"), row.getLong("purged_count"),
                row.getLong("skipped_hold_count"),
                row.getLong("attachment_deletion_pending_count"),
                row.getString("failure_code"),
                row.getObject("started_at", OffsetDateTime.class),
                row.getObject("finished_at", OffsetDateTime.class)), tenantId)
                .stream().findFirst().orElse(null);
    }

    record TenantRetentionPolicy(long tenantId, int retentionDays) { }

    record CandidateStats(long candidateCount, long heldCount) { }

    record ContinuityPurgeResult(int deletedDrafts, int redactedSchedules) {
        int total() {
            return deletedDrafts + redactedSchedules;
        }
    }

    record RetentionHealthSummary(
            long tenantCount,
            long failedTenantCount,
            long backlogTenantCount,
            long pendingAttachmentCount,
            long missingSuccessCount,
            long staleSuccessCount,
            OffsetDateTime lastSuccessAt) { }

    record LockedMessage(UUID messageId, UUID conversationId) { }

    record PendingAttachmentDeletion(UUID deletionId, String objectKey) { }

    record RetentionRun(
            UUID runId,
            String executionMode,
            String state,
            OffsetDateTime cutoffAt,
            long candidateCount,
            long purgedCount,
            long skippedHoldCount,
            long pendingAttachmentDeletionCount,
            String failureCode,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) { }
}
