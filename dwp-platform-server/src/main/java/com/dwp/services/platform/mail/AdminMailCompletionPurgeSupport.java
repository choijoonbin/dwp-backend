package com.dwp.services.platform.mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.net.URI;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class AdminMailCompletionPurgeSupport extends AdminMailCompletionRepositoryDeliverySupport {

    AdminMailCompletionPurgeSupport(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    CandidateSet purgeCandidates(long tenantId, OffsetDateTime before) {
        List<CandidateRow> rows = jdbc.query("""
                SELECT thread.thread_id, thread.account_id,
                       (SELECT count(*) FROM mail_messages message
                         WHERE message.tenant_id = thread.tenant_id
                           AND message.thread_id = thread.thread_id) message_count,
                       (SELECT count(*) FROM mail_compose_attachments attachment
                         WHERE attachment.tenant_id = thread.tenant_id
                           AND attachment.thread_id = thread.thread_id) attachment_count,
                       (SELECT count(*) FROM mail_draft_options draft
                         WHERE draft.tenant_id = thread.tenant_id
                           AND draft.thread_id = thread.thread_id) draft_count,
                       connection.provider_type,
                       (%s) immutable_evidence_blocked
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                 WHERE thread.tenant_id = ? AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                 ORDER BY thread.thread_id
                """.formatted(PURGE_IMMUTABLE_EVIDENCE_EXISTS),
                (rs, ignored) -> new CandidateRow(
                uuid(rs, "thread_id"), uuid(rs, "account_id"),
                rs.getInt("message_count"), rs.getInt("attachment_count"),
                rs.getInt("draft_count"),
                rs.getString("provider_type"),
                rs.getBoolean("immutable_evidence_blocked")), tenantId, before);
        return new CandidateSet(rows);
    }

    Optional<PurgePreviewRow> purgePreviewByCommand(long tenantId, long actorId, UUID key) {
        return one(PURGE_PREVIEW_BY + " AND actor_user_id = ? AND idempotency_key = ?",
                PURGE_PREVIEW, tenantId, actorId, key);
    }

    Optional<PurgePreviewRow> purgePreview(long tenantId, UUID snapshotId) {
        return one(PURGE_PREVIEW_BY + " AND candidate_snapshot_id = ?",
                PURGE_PREVIEW, tenantId, snapshotId);
    }

    List<PurgePreviewRow> activePurgePreviews(long tenantId, int limit) {
        return jdbc.query(PURGE_PREVIEW_BY + """
                 AND policy_version = (
                     SELECT policy.version
                       FROM mail_tenant_policies policy
                      WHERE policy.tenant_id = mail_purge_previews.tenant_id)
                 AND expires_at > CURRENT_TIMESTAMP
                 AND NOT EXISTS (
                     SELECT 1
                       FROM mail_purge_jobs job
                      WHERE job.tenant_id = mail_purge_previews.tenant_id
                        AND job.candidate_snapshot_id = mail_purge_previews.candidate_snapshot_id)
                 ORDER BY created_at DESC, candidate_snapshot_id DESC
                 LIMIT ?
                """, PURGE_PREVIEW, tenantId, limit);
    }

    UUID insertPurgePreview(
            long tenantId, long actorId, Map<String, Object> scope,
            List<String> resourceTypes, OffsetDateTime before, String fingerprint,
            int total, int held, int eligible, List<String> partialSources,
            long policyVersion, UUID key, OffsetDateTime expiresAt) {
        return jdbc.queryForObject("""
                INSERT INTO mail_purge_previews (
                    tenant_id, actor_user_id, purge_scope, resource_types, before_at,
                    snapshot_fingerprint, total_candidates, held_count, eligible_count,
                    partial_sources, policy_version, idempotency_key, expires_at)
                VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                RETURNING candidate_snapshot_id
                """, UUID.class, tenantId, actorId, json(scope), json(resourceTypes),
                before, fingerprint, total, held, eligible, json(partialSources),
                policyVersion, key, expiresAt);
    }

    void insertPurgeCandidateRows(
            long tenantId, UUID snapshotId, List<PurgeCandidateSnapshotRow> rows) {
        for (PurgeCandidateSnapshotRow row : rows) {
            jdbc.update("""
                    INSERT INTO mail_purge_candidate_snapshot_rows (
                        candidate_snapshot_id, tenant_id, thread_id, account_id,
                        message_count, attachment_count, draft_count, provider_type,
                        held, hold_ids, evidence)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                    """, snapshotId, tenantId, row.threadId(), row.accountId(),
                    row.messageCount(), row.attachmentCount(), row.draftCount(),
                    row.providerType(), row.held(),
                    json(row.holdIds().stream().map(UUID::toString).toList()),
                    json(row.evidence()));
        }
    }

    List<PurgeCandidateSnapshotRow> purgeCandidateRows(long tenantId, UUID snapshotId) {
        return jdbc.query("""
                SELECT thread_id, account_id, message_count, attachment_count,
                       draft_count, provider_type, held, hold_ids, evidence
                  FROM mail_purge_candidate_snapshot_rows
                 WHERE tenant_id = ? AND candidate_snapshot_id = ?
                 ORDER BY thread_id
                """, (rs, ignored) -> new PurgeCandidateSnapshotRow(
                uuid(rs, "thread_id"), uuid(rs, "account_id"),
                rs.getInt("message_count"), rs.getInt("attachment_count"),
                rs.getInt("draft_count"), rs.getString("provider_type"),
                rs.getBoolean("held"), strings(rs.getString("hold_ids")).stream()
                        .map(UUID::fromString).toList(),
                map(rs.getString("evidence"))), tenantId, snapshotId);
    }

    Optional<PurgeApprovalRow> purgeApprovalByCommand(long tenantId, long actorId, UUID key) {
        return one("""
                SELECT approval_id, candidate_snapshot_id, approver_user_id,
                       policy_version, approved_at
                  FROM mail_purge_approvals
                 WHERE tenant_id = ? AND approver_user_id = ? AND idempotency_key = ?
                """, PURGE_APPROVAL, tenantId, actorId, key);
    }

    UUID insertPurgeApproval(
            long tenantId, UUID snapshotId, long actorId, long policyVersion, UUID key) {
        return jdbc.queryForObject("""
                INSERT INTO mail_purge_approvals (
                    tenant_id, candidate_snapshot_id, approver_user_id,
                    policy_version, idempotency_key)
                VALUES (?, ?, ?, ?, ?)
                RETURNING approval_id
                """, UUID.class, tenantId, snapshotId, actorId, policyVersion, key);
    }

    int distinctApprovals(long tenantId, UUID snapshotId, long policyVersion) {
        Integer count = jdbc.queryForObject("""
                SELECT count(DISTINCT approver_user_id)
                  FROM mail_purge_approvals
                 WHERE tenant_id = ? AND candidate_snapshot_id = ? AND policy_version = ?
                """, Integer.class, tenantId, snapshotId, policyVersion);
        return count == null ? 0 : count;
    }

    Optional<PurgeJobRow> purgeJobByCommand(long tenantId, long actorId, UUID key) {
        return one(PURGE_JOB_BY + " AND actor_user_id = ? AND idempotency_key = ?",
                PURGE_JOB, tenantId, actorId, key);
    }

    Optional<PurgeJobRow> purgeJobBySnapshot(long tenantId, UUID snapshotId) {
        return one(PURGE_JOB_BY + " AND candidate_snapshot_id = ?",
                PURGE_JOB, tenantId, snapshotId);
    }

    Optional<PurgeJobRow> purgeJob(long tenantId, UUID jobId) {
        return one(PURGE_JOB_BY + " AND job_id = ?", PURGE_JOB, tenantId, jobId);
    }

    List<PurgeJobRow> purgeJobs(long tenantId, int limit) {
        return jdbc.query(PURGE_JOB_BY + " ORDER BY started_at DESC LIMIT ?",
                PURGE_JOB, tenantId, limit);
    }

    UUID insertPurgeJob(long tenantId, UUID snapshotId, long actorId, UUID key) {
        return insertPurgeJob(tenantId, snapshotId, actorId, key, List.of());
    }

    UUID insertPurgeJob(
            long tenantId, UUID snapshotId, long actorId, UUID key,
            List<UUID> candidateThreadIds) {
        return insertPurgeJob(
                tenantId, snapshotId, actorId, key, candidateThreadIds, List.of());
    }

    UUID insertPurgeJob(
            long tenantId, UUID snapshotId, long actorId, UUID key,
            List<UUID> candidateThreadIds, List<Map<String, Object>> initialSteps) {
        return jdbc.queryForObject("""
                INSERT INTO mail_purge_jobs (
                    tenant_id, candidate_snapshot_id, actor_user_id,
                    job_state, verification_state, idempotency_key, candidate_thread_ids,
                    step_results, current_step)
                VALUES (?, ?, ?, 'ACCEPTED', 'PENDING', ?, ?::jsonb, ?::jsonb, 'ACCEPTED')
                RETURNING job_id
                """, UUID.class, tenantId, snapshotId, actorId, key,
                json(candidateThreadIds.stream().map(UUID::toString).toList()),
                json(initialSteps));
    }

    int completeLeasedPurgeJob(
            long tenantId, UUID jobId, String workerId, String state,
            DeleteCounts counts, List<Map<String, Object>> steps,
            String verification, String errorCode) {
        return jdbc.update("""
                UPDATE mail_purge_jobs
                   SET job_state = ?, deleted_threads = ?, deleted_messages = ?,
                       step_results = ?::jsonb, verification_state = ?, error_code = ?,
                       completed_at = CASE WHEN ? IN ('SUCCEEDED', 'FAILED')
                                           THEN CURRENT_TIMESTAMP ELSE NULL END,
                       current_step = CASE WHEN ? IN ('SUCCEEDED', 'FAILED')
                                           THEN 'COMPLETED' ELSE 'RETRY_PENDING' END,
                       lease_owner = NULL,
                       lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND job_id = ? AND job_state = 'RUNNING'
                   AND lease_owner = ? AND lease_expires_at > CURRENT_TIMESTAMP
                """, state, counts.threads(), counts.messages(), json(steps), verification,
                errorCode, state, state, tenantId, jobId, workerId);
    }

    int releaseExpiredPurgeLeases(int maximumAttempts) {
        return jdbc.update("""
                UPDATE mail_purge_jobs
                   SET job_state = CASE WHEN attempt_count >= ? THEN 'FAILED' ELSE 'UNKNOWN' END,
                       verification_state = 'UNKNOWN',
                       error_code = 'PURGE_WORKER_LEASE_EXPIRED',
                       current_step = 'LEASE_EXPIRED',
                       step_results = step_results || jsonb_build_array(jsonb_build_object(
                           'step', current_step, 'state', 'UNKNOWN',
                           'errorCode', 'PURGE_WORKER_LEASE_EXPIRED',
                           'at', CURRENT_TIMESTAMP)),
                       lease_owner = NULL, lease_expires_at = NULL,
                       completed_at = CASE WHEN attempt_count >= ?
                                           THEN CURRENT_TIMESTAMP ELSE NULL END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE job_state = 'RUNNING' AND lease_expires_at < CURRENT_TIMESTAMP
                """, maximumAttempts, maximumAttempts);
    }

    List<PurgeLeaseRow> claimPurgeJobs(
            String workerId, int limit, int leaseSeconds, int maximumAttempts) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT job_id, current_step previous_step,
                           deleted_threads previous_deleted_threads,
                           deleted_messages previous_deleted_messages,
                           started_at
                      FROM mail_purge_jobs
                     WHERE job_state IN ('ACCEPTED', 'PARTIAL', 'UNKNOWN')
                       AND attempt_count < ?
                     ORDER BY started_at, job_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                UPDATE mail_purge_jobs job
                   SET job_state = 'RUNNING', lease_owner = ?,
                       lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                       attempt_count = attempt_count + 1,
                       current_step = 'LEASED', error_code = NULL,
                       completed_at = NULL, updated_at = CURRENT_TIMESTAMP
                  FROM candidates
                 WHERE job.job_id = candidates.job_id
                RETURNING job.job_id, job.tenant_id, job.candidate_snapshot_id,
                          job.actor_user_id, job.candidate_thread_ids,
                          job.step_results, job.attempt_count,
                          candidates.previous_step,
                          candidates.previous_deleted_threads,
                          candidates.previous_deleted_messages,
                          candidates.started_at
                """, (rs, ignored) -> new PurgeLeaseRow(
                uuid(rs, "job_id"), rs.getLong("tenant_id"),
                uuid(rs, "candidate_snapshot_id"), rs.getLong("actor_user_id"),
                strings(rs.getString("candidate_thread_ids")).stream()
                        .map(UUID::fromString).toList(),
                maps(rs.getString("step_results")), rs.getInt("attempt_count"),
                rs.getString("previous_step"),
                rs.getInt("previous_deleted_threads"),
                rs.getInt("previous_deleted_messages"),
                offset(rs, "started_at")),
                maximumAttempts, limit, workerId, leaseSeconds);
    }

    int checkpointPurgeJob(
            long tenantId, UUID jobId, String workerId, String step,
            List<Map<String, Object>> steps, DeleteCounts counts) {
        return jdbc.update("""
                UPDATE mail_purge_jobs
                   SET current_step = ?, step_results = ?::jsonb,
                       deleted_threads = ?, deleted_messages = ?,
                       updated_at = CURRENT_TIMESTAMP,
                       lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                 WHERE tenant_id = ? AND job_id = ? AND job_state = 'RUNNING'
                   AND lease_owner = ? AND lease_expires_at > CURRENT_TIMESTAMP
                """, step, json(steps), counts.threads(), counts.messages(),
                tenantId, jobId, workerId);
    }

    PurgeEventEvidence ensurePurgeCompletionEvent(
            long tenantId, UUID jobId, long actorId, DeleteCounts counts) {
        UUID eventId = UUID.nameUUIDFromBytes(
                ("mail-purge:" + tenantId + ':' + jobId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return ensurePurgeCompletionEvent(tenantId, jobId, actorId, counts, eventId);
    }

    PurgeEventEvidence ensurePurgeCompletionEvent(
            long tenantId, UUID jobId, long actorId, DeleteCounts counts, UUID eventId) {
        jdbc.update("""
                INSERT INTO mail_domain_events (
                    domain_event_id, tenant_id, aggregate_type, aggregate_id,
                    event_type, payload)
                VALUES (?, ?, 'MAIL_PURGE_JOB', ?, 'mail.purge.completed', ?::jsonb)
                ON CONFLICT (tenant_id, aggregate_id, event_type)
                    WHERE aggregate_type = 'MAIL_PURGE_JOB'
                      AND event_type = 'mail.purge.completed'
                DO NOTHING
                """, eventId, tenantId, jobId, json(Map.of(
                "jobId", jobId, "actorId", actorId,
                "deletedThreads", counts.threads(), "deletedMessages", counts.messages())));
        jdbc.update("""
                UPDATE mail_domain_events event
                   SET published_at = outbox.published_at,
                       publish_attempts = outbox.attempt_count
                  FROM sys_domain_event_outbox outbox
                 WHERE event.tenant_id = ?
                   AND event.aggregate_type = 'MAIL_PURGE_JOB'
                   AND event.aggregate_id = ?
                   AND event.event_type = 'mail.purge.completed'
                   AND outbox.event_id = event.domain_event_id
                """, tenantId, jobId);
        return jdbc.queryForObject("""
                SELECT event.domain_event_id, event.occurred_at,
                       outbox.published_at, outbox.attempt_count publish_attempts,
                       outbox.status, outbox.last_error
                  FROM mail_domain_events event
                  JOIN sys_domain_event_outbox outbox
                    ON outbox.event_id = event.domain_event_id
                 WHERE event.tenant_id = ? AND event.aggregate_type = 'MAIL_PURGE_JOB'
                   AND event.aggregate_id = ? AND event.event_type = 'mail.purge.completed'
                """, (rs, ignored) -> new PurgeEventEvidence(
                uuid(rs, "domain_event_id"), offset(rs, "occurred_at"),
                offset(rs, "published_at"), rs.getInt("publish_attempts"),
                rs.getString("status"), rs.getString("last_error")),
                tenantId, jobId);
    }

    DeleteCounts deletePurgeCandidates(long tenantId, OffsetDateTime before) {
        List<String> attachmentStorageReferences = jdbc.queryForList("""
                SELECT DISTINCT attachment.storage_reference
                  FROM mail_compose_attachments attachment
                  JOIN mail_threads thread
                    ON thread.tenant_id = attachment.tenant_id
                   AND thread.thread_id = attachment.thread_id
                 WHERE thread.tenant_id = ?
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                   AND NOT (%s)
                """.formatted(PURGE_IMMUTABLE_EVIDENCE_EXISTS),
                String.class, tenantId, before);
        Integer messages = jdbc.queryForObject("""
                SELECT count(*) FROM mail_messages message
                 WHERE message.tenant_id = ? AND EXISTS (
                    SELECT 1 FROM mail_threads thread
                     WHERE thread.tenant_id = message.tenant_id
                       AND thread.thread_id = message.thread_id
                       AND thread.workflow_state = 'TRASHED'
                       AND thread.updated_at < ?
                       AND NOT (%s))
                """.formatted(PURGE_IMMUTABLE_EVIDENCE_EXISTS),
                Integer.class, tenantId, before);
        int threads = jdbc.update("""
                DELETE FROM mail_threads thread
                 WHERE thread.tenant_id = ?
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                   AND NOT (%s)
                """.formatted(PURGE_IMMUTABLE_EVIDENCE_EXISTS), tenantId, before);
        int cleanupCommands = 0;
        for (String storageReference : attachmentStorageReferences) {
            cleanupCommands += jdbc.update("""
                    INSERT INTO sys_tenant_media_cleanup_outbox (
                        tenant_id, storage_key, cleanup_reason)
                    SELECT ?, ?, 'MAIL_RETENTION_PURGE'
                     WHERE NOT EXISTS (
                         SELECT 1
                           FROM mail_compose_attachments attachment
                          WHERE attachment.tenant_id = ?
                            AND attachment.storage_reference = ?)
                    ON CONFLICT DO NOTHING
                    """, tenantId, storageReference, tenantId, storageReference);
        }
        return new DeleteCounts(threads, messages == null ? 0 : messages, cleanupCommands);
    }

    DeleteCounts deletePurgeCandidates(long tenantId, List<UUID> threadIds) {
        if (threadIds.isEmpty()) return new DeleteCounts(0, 0);
        String placeholders = String.join(", ", java.util.Collections.nCopies(threadIds.size(), "?"));
        Object[] parameters = new Object[threadIds.size() + 1];
        parameters[0] = tenantId;
        for (int index = 0; index < threadIds.size(); index++) {
            parameters[index + 1] = threadIds.get(index);
        }
        List<String> attachmentStorageReferences = jdbc.queryForList("""
                SELECT DISTINCT storage_reference
                  FROM mail_compose_attachments
                 WHERE tenant_id = ? AND thread_id IN (%s)
                """.formatted(placeholders), String.class, parameters);
        Integer messages = jdbc.queryForObject("""
                SELECT count(*) FROM mail_messages
                 WHERE tenant_id = ? AND thread_id IN (%s)
                """.formatted(placeholders), Integer.class, parameters);
        int threads = jdbc.update("""
                DELETE FROM mail_threads
                 WHERE tenant_id = ? AND thread_id IN (%s)
                """.formatted(placeholders), parameters);
        int cleanupCommands = 0;
        for (String storageReference : attachmentStorageReferences) {
            cleanupCommands += jdbc.update("""
                    INSERT INTO sys_tenant_media_cleanup_outbox (
                        tenant_id, storage_key, cleanup_reason)
                    SELECT ?, ?, 'MAIL_RETENTION_PURGE'
                     WHERE NOT EXISTS (
                         SELECT 1 FROM mail_compose_attachments
                          WHERE tenant_id = ? AND storage_reference = ?)
                    ON CONFLICT DO NOTHING
                    """, tenantId, storageReference, tenantId, storageReference);
        }
        return new DeleteCounts(threads, messages == null ? 0 : messages, cleanupCommands);
    }

    java.util.Set<UUID> purgeDeletionReceiptIds(long tenantId, UUID jobId) {
        return java.util.Set.copyOf(jdbc.queryForList("""
                SELECT thread_id
                  FROM mail_purge_deleted_candidate_receipts
                 WHERE tenant_id = ? AND job_id = ?
                """, UUID.class, tenantId, jobId));
    }

    DeleteCounts purgeDeletionReceiptCounts(long tenantId, UUID jobId) {
        return jdbc.queryForObject("""
                SELECT count(*) deleted_threads,
                       COALESCE(sum(message_count), 0) deleted_messages
                  FROM mail_purge_deleted_candidate_receipts
                 WHERE tenant_id = ? AND job_id = ?
                """, (rs, ignored) -> new DeleteCounts(
                rs.getInt("deleted_threads"), rs.getInt("deleted_messages")),
                tenantId, jobId);
    }

    DeleteCounts deleteEligiblePurgeCandidates(
            PurgeLeaseRow job, OffsetDateTime before) {
        java.util.Set<UUID> completed = purgeDeletionReceiptIds(job.tenantId(), job.id());
        List<UUID> pending = job.candidateThreadIds().stream()
                .filter(id -> !completed.contains(id)).toList();
        if (pending.isEmpty()) return purgeDeletionReceiptCounts(job.tenantId(), job.id());

        String placeholders = String.join(", ",
                java.util.Collections.nCopies(pending.size(), "?"));
        Object[] parameters = new Object[pending.size() + 2];
        parameters[0] = job.tenantId();
        for (int index = 0; index < pending.size(); index++) {
            parameters[index + 1] = pending.get(index);
        }
        parameters[parameters.length - 1] = before;
        List<UUID> lockedEligible = jdbc.queryForList("""
                SELECT thread.thread_id
                  FROM mail_threads thread
                 WHERE thread.tenant_id = ?
                   AND thread.thread_id IN (%s)
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                   AND NOT (%s)
                 ORDER BY thread.thread_id
                 FOR UPDATE
                """.formatted(placeholders, PURGE_IMMUTABLE_EVIDENCE_EXISTS),
                UUID.class, parameters);
        if (!java.util.Set.copyOf(lockedEligible).equals(java.util.Set.copyOf(pending))) {
            throw new AdminMailPurgeBlockedException(
                    "PURGE_CANDIDATE_NO_LONGER_ELIGIBLE");
        }

        Object[] lockedParameters = new Object[lockedEligible.size() + 2];
        lockedParameters[0] = job.tenantId();
        for (int index = 0; index < lockedEligible.size(); index++) {
            lockedParameters[index + 1] = lockedEligible.get(index);
        }
        lockedParameters[lockedParameters.length - 1] = before;
        List<String> attachmentStorageReferences = jdbc.queryForList("""
                SELECT DISTINCT attachment.storage_reference
                  FROM mail_compose_attachments attachment
                  JOIN mail_threads thread
                    ON thread.tenant_id = attachment.tenant_id
                   AND thread.thread_id = attachment.thread_id
                 WHERE thread.tenant_id = ? AND thread.thread_id IN (%s)
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                """.formatted(placeholders), String.class, lockedParameters);
        List<UUID> deleted = jdbc.queryForList("""
                DELETE FROM mail_threads thread
                 WHERE thread.tenant_id = ? AND thread.thread_id IN (%s)
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                   AND NOT (%s)
                RETURNING thread.thread_id
                """.formatted(placeholders, PURGE_IMMUTABLE_EVIDENCE_EXISTS),
                UUID.class, lockedParameters);
        if (!java.util.Set.copyOf(deleted).equals(java.util.Set.copyOf(pending))) {
            throw new AdminMailPurgeBlockedException(
                    "PURGE_DELETE_ELIGIBILITY_RACE");
        }
        for (UUID threadId : deleted) {
            if (jdbc.update("""
                    INSERT INTO mail_purge_deleted_candidate_receipts (
                        job_id, candidate_snapshot_id, tenant_id, thread_id,
                        message_count, attachment_count, draft_count)
                    SELECT ?, candidate_snapshot_id, tenant_id, thread_id,
                           message_count, attachment_count, draft_count
                      FROM mail_purge_candidate_snapshot_rows
                     WHERE tenant_id = ? AND candidate_snapshot_id = ? AND thread_id = ?
                    ON CONFLICT (job_id, thread_id) DO NOTHING
                    """, job.id(), job.tenantId(), job.snapshotId(), threadId) != 1) {
                throw new AdminMailPurgeBlockedException(
                        "PURGE_DELETE_RECEIPT_NOT_RECORDED");
            }
        }
        int cleanupCommands = 0;
        for (String storageReference : attachmentStorageReferences) {
            cleanupCommands += jdbc.update("""
                    INSERT INTO sys_tenant_media_cleanup_outbox (
                        tenant_id, storage_key, cleanup_reason)
                    SELECT ?, ?, 'MAIL_RETENTION_PURGE'
                     WHERE NOT EXISTS (
                         SELECT 1 FROM mail_compose_attachments
                          WHERE tenant_id = ? AND storage_reference = ?)
                    ON CONFLICT DO NOTHING
                    """, job.tenantId(), storageReference,
                    job.tenantId(), storageReference);
        }
        DeleteCounts cumulative = purgeDeletionReceiptCounts(job.tenantId(), job.id());
        return new DeleteCounts(
                cumulative.threads(), cumulative.messages(), cleanupCommands);
    }

    void completePurgeJob(
            long tenantId, UUID jobId, String state, DeleteCounts counts,
            List<Map<String, Object>> steps, String verification, String errorCode) {
        jdbc.update("""
                UPDATE mail_purge_jobs
                   SET job_state = ?, deleted_threads = ?, deleted_messages = ?,
                       step_results = ?::jsonb, verification_state = ?, error_code = ?,
                       completed_at = CURRENT_TIMESTAMP, current_step = 'COMPLETED',
                       lease_owner = NULL, lease_expires_at = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND job_id = ?
                """, state, counts.threads(), counts.messages(), json(steps), verification,
                errorCode, tenantId, jobId);
    }

    long remainingPurgeCandidates(long tenantId, OffsetDateTime before) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM mail_threads thread
                 WHERE thread.tenant_id = ?
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                   AND NOT (%s)
                """.formatted(PURGE_IMMUTABLE_EVIDENCE_EXISTS),
                Long.class, tenantId, before);
        return count == null ? 0 : count;
    }

    long remainingPurgeCandidates(long tenantId, List<UUID> threadIds) {
        if (threadIds.isEmpty()) return 0;
        String placeholders = String.join(", ", java.util.Collections.nCopies(threadIds.size(), "?"));
        Object[] parameters = new Object[threadIds.size() + 1];
        parameters[0] = tenantId;
        for (int index = 0; index < threadIds.size(); index++) {
            parameters[index + 1] = threadIds.get(index);
        }
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM mail_threads
                 WHERE tenant_id = ? AND thread_id IN (%s)
                """.formatted(placeholders), Long.class, parameters);
        return count == null ? 0 : count;
    }

}
