package com.dwp.services.platform.mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Transactional state machine for durable connection synchronization. */
@Repository
class MailSyncJobRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    MailSyncJobRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    void enqueue(
            long tenantId,
            UUID operationId,
            UUID connectionId,
            long actorId,
            long connectionVersion,
            String triggerKind,
            int maximumAttempts) {
        jdbc.update("""
                INSERT INTO mail_sync_jobs (
                    tenant_id, operation_id, connection_id, actor_user_id,
                    trigger_kind, requested_connection_version, maximum_attempts)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (operation_id) DO NOTHING
                """, tenantId, operationId, connectionId, actorId,
                triggerKind, connectionVersion, maximumAttempts);
    }

    @Transactional
    List<MailSyncJob> claim(String workerId, int limit, int leaseSeconds) {
        List<MailSyncJob> jobs = jdbc.query("""
                WITH candidates AS (
                    SELECT job_id
                      FROM mail_sync_jobs
                     WHERE job_state IN ('QUEUED', 'RETRY_WAIT')
                       AND next_attempt_at <= CURRENT_TIMESTAMP
                     ORDER BY next_attempt_at, created_at, job_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), leased AS (
                    UPDATE mail_sync_jobs job
                       SET job_state = 'LEASED', attempt_count = attempt_count + 1,
                           lease_owner = ?,
                           lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                           updated_at = CURRENT_TIMESTAMP
                      FROM candidates
                     WHERE job.job_id = candidates.job_id
                    RETURNING job.*
                )
                SELECT leased.job_id, leased.tenant_id, leased.operation_id,
                       leased.connection_id, leased.actor_user_id,
                       leased.requested_connection_version, leased.trigger_kind,
                       leased.attempt_count,
                       leased.maximum_attempts, operation.correlation_id
                  FROM leased
                  JOIN mail_connection_operations operation
                    ON operation.tenant_id = leased.tenant_id
                   AND operation.operation_id = leased.operation_id
                 ORDER BY leased.created_at, leased.job_id
                """, (result, ignored) -> new MailSyncJob(
                result.getObject("job_id", UUID.class),
                result.getLong("tenant_id"),
                result.getObject("operation_id", UUID.class),
                result.getObject("connection_id", UUID.class),
                result.getLong("actor_user_id"),
                result.getLong("requested_connection_version"),
                result.getString("trigger_kind"),
                result.getInt("attempt_count"),
                result.getInt("maximum_attempts"),
                result.getString("correlation_id")),
                limit, workerId, leaseSeconds);
        for (MailSyncJob job : jobs) {
            jdbc.update("""
                    UPDATE mail_connection_operations
                       SET operation_state = 'RUNNING', error_code = NULL,
                           evidence_generated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ? AND operation_id = ?
                       AND operation_state IN ('ACCEPTED', 'RUNNING')
                    """, job.tenantId(), job.operationId());
        }
        return jobs;
    }

    @Transactional
    MailSyncLeaseRecovery releaseExpiredLeases() {
        List<ExpiredLease> expired = jdbc.query("""
                WITH released AS (
                    UPDATE mail_sync_jobs
                   SET job_state = CASE WHEN attempt_count >= maximum_attempts
                                        THEN 'DEAD_LETTER' ELSE 'RETRY_WAIT' END,
                       next_attempt_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL, lease_expires_at = NULL,
                       last_error_code = 'SYNC_WORKER_LEASE_EXPIRED',
                       completed_at = CASE WHEN attempt_count >= maximum_attempts
                                           THEN CURRENT_TIMESTAMP ELSE NULL END,
                       updated_at = CURRENT_TIMESTAMP
                     WHERE job_state = 'LEASED'
                       AND lease_expires_at <= CURRENT_TIMESTAMP
                    RETURNING job_id, tenant_id, operation_id, connection_id,
                              actor_user_id, requested_connection_version,
                              trigger_kind, attempt_count, maximum_attempts, job_state
                )
                SELECT released.*, operation.correlation_id
                  FROM released
                  JOIN mail_connection_operations operation
                    ON operation.tenant_id = released.tenant_id
                   AND operation.operation_id = released.operation_id
                """, (result, ignored) -> new ExpiredLease(
                new MailSyncJob(
                        result.getObject("job_id", UUID.class),
                        result.getLong("tenant_id"),
                        result.getObject("operation_id", UUID.class),
                        result.getObject("connection_id", UUID.class),
                        result.getLong("actor_user_id"),
                        result.getLong("requested_connection_version"),
                        result.getString("trigger_kind"),
                        result.getInt("attempt_count"),
                        result.getInt("maximum_attempts"),
                        result.getString("correlation_id")),
                result.getString("job_state")));
        jdbc.update("""
                UPDATE mail_connection_operations operation
                   SET operation_state = 'FAILED',
                       error_code = 'SYNC_WORKER_LEASE_EXPIRED',
                       evidence_generated_at = CURRENT_TIMESTAMP,
                       completed_at = CURRENT_TIMESTAMP
                  FROM mail_sync_jobs job
                 WHERE job.tenant_id = operation.tenant_id
                   AND job.operation_id = operation.operation_id
                   AND job.job_state = 'DEAD_LETTER'
                   AND operation.operation_state IN ('ACCEPTED', 'RUNNING')
                """);
        int retryWait = 0;
        int deadLetter = 0;
        for (ExpiredLease lease : expired) {
            if ("DEAD_LETTER".equals(lease.state())) {
                deadLetter++;
                audit(lease.job(), "FAILED", "SYNC_WORKER_LEASE_EXPIRED", Map.of(
                        "attempt", lease.job().attemptCount(),
                        "trigger", lease.job().triggerKind(),
                        "classification", "LEASE_EXPIRED",
                        "outcome", "DEAD_LETTER"));
            } else {
                retryWait++;
            }
        }
        return new MailSyncLeaseRecovery(retryWait, deadLetter);
    }

    @Transactional
    boolean completeSuccess(
            MailSyncJob job,
            String workerId,
            MailSyncExecutionResult result) {
        int connectionUpdated = jdbc.update("""
                UPDATE mail_provider_connections
                   SET connection_state = 'ACTIVE', last_synchronized_at = CURRENT_TIMESTAMP,
                       last_error_code = NULL, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND connection_id = ? AND version = ?
                """, job.actorId(), job.tenantId(), job.connectionId(),
                job.connectionVersion());
        if (connectionUpdated != 1) {
            throw new MailSyncWorkFailure(
                    "CONNECTION_VERSION_CONFLICT", true);
        }
        Map<String, Object> evidence = Map.of(
                "accounts", result.accounts(),
                "batches", result.batches(),
                "insertedMessages", result.insertedMessages(),
                "duplicateMessages", result.duplicateMessages(),
                "blockedAttachments", result.blockedAttachments(),
                "trigger", job.triggerKind(),
                "attempt", job.attemptCount());
        int completed = jdbc.update("""
                UPDATE mail_sync_jobs
                   SET job_state = 'SUCCEEDED', lease_owner = NULL,
                       lease_expires_at = NULL, worker_evidence = ?::jsonb,
                       last_error_code = NULL, completed_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND job_id = ? AND job_state = 'LEASED'
                   AND lease_owner = ? AND lease_expires_at > CURRENT_TIMESTAMP
                """, json(evidence), job.tenantId(), job.jobId(), workerId);
        if (completed != 1) {
            throw new MailSyncWorkFailure("SYNC_LEASE_LOST", false);
        }
        jdbc.update("""
                UPDATE mail_connection_operations
                   SET operation_state = 'SUCCEEDED', error_code = NULL,
                       evidence_generated_at = CURRENT_TIMESTAMP,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND operation_id = ?
                """, job.tenantId(), job.operationId());
        audit(job, "SUCCEEDED", null, evidence);
        return true;
    }

    /**
     * Releases a bounded page run back to the durable queue without consuming a
     * failure attempt. Every materialized page has already committed its fenced
     * cursor, so the next lease resumes from the latest account generation.
     */
    @Transactional
    boolean completeContinuation(
            MailSyncJob job,
            String workerId,
            MailSyncExecutionResult result) {
        if (!result.continuationRequired()) {
            throw new IllegalArgumentException("SYNC_CONTINUATION_NOT_REQUIRED");
        }
        Map<String, Object> evidence = Map.of(
                "accounts", result.accounts(),
                "batches", result.batches(),
                "insertedMessages", result.insertedMessages(),
                "duplicateMessages", result.duplicateMessages(),
                "blockedAttachments", result.blockedAttachments(),
                "trigger", job.triggerKind(),
                "attempt", job.attemptCount(),
                "outcome", "CONTINUATION_SCHEDULED");
        int continued = jdbc.update("""
                UPDATE mail_sync_jobs
                   SET job_state = 'RETRY_WAIT', attempt_count = 0,
                       next_attempt_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL, lease_expires_at = NULL,
                       last_error_code = NULL, worker_evidence = ?::jsonb,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND job_id = ? AND job_state = 'LEASED'
                   AND lease_owner = ? AND lease_expires_at > CURRENT_TIMESTAMP
                """, json(evidence), job.tenantId(), job.jobId(), workerId);
        if (continued != 1) return false;
        jdbc.update("""
                UPDATE mail_connection_operations
                   SET operation_state = 'RUNNING', error_code = NULL,
                       evidence_generated_at = CURRENT_TIMESTAMP,
                       completed_at = NULL
                 WHERE tenant_id = ? AND operation_id = ?
                   AND operation_state IN ('ACCEPTED', 'RUNNING')
                """, job.tenantId(), job.operationId());
        audit(job, "CONTINUED", null, evidence);
        return true;
    }

    @Transactional
    FailureDisposition completeFailure(
            MailSyncJob job,
            String workerId,
            String errorCode,
            boolean terminal,
            String classification,
            OffsetDateTime retryAt,
            Map<String, Object> resetEvidence) {
        boolean cursorReset = "SYNC_CURSOR_RESET_REQUIRED".equals(errorCode);
        if (cursorReset && completedCursorReset(job)) {
            return new FailureDisposition(true, true);
        }
        boolean deadLetter = terminal || cursorReset
                || job.attemptCount() >= job.maximumAttempts();
        String state = deadLetter ? "DEAD_LETTER" : "RETRY_WAIT";
        int retrySeconds = Math.min(3600,
                1 << Math.min(12, Math.max(0, job.attemptCount() - 1)));
        Map<String, Object> evidence = Map.of(
                "attempt", job.attemptCount(),
                "trigger", job.triggerKind(),
                "classification", classification,
                "outcome", deadLetter ? "DEAD_LETTER" : "RETRY_SCHEDULED",
                "errorCode", errorCode);
        String resetReceipt = resetEvidence == null || resetEvidence.isEmpty()
                ? null : json(resetEvidence);
        int updated = jdbc.update("""
                UPDATE mail_sync_jobs
                   SET job_state = ?, next_attempt_at = CASE WHEN ?
                           THEN next_attempt_at
                           ELSE GREATEST(COALESCE(?,
                               CURRENT_TIMESTAMP + (? * INTERVAL '1 second')),
                               CURRENT_TIMESTAMP) END,
                       lease_owner = NULL, lease_expires_at = NULL,
                       last_error_code = ?, worker_evidence = ?::jsonb,
                       cursor_reset_receipt = COALESCE(?::jsonb, cursor_reset_receipt),
                       completed_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND job_id = ? AND job_state = 'LEASED'
                   AND lease_owner = ? AND lease_expires_at > CURRENT_TIMESTAMP
                """, state, deadLetter, retryAt, retrySeconds, errorCode, json(evidence),
                resetReceipt, deadLetter, job.tenantId(), job.jobId(), workerId);
        if (updated != 1) return new FailureDisposition(false, deadLetter);
        jdbc.update("""
                UPDATE mail_connection_operations
                   SET operation_state = ?, error_code = ?,
                       evidence_generated_at = CURRENT_TIMESTAMP,
                       completed_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END
                 WHERE tenant_id = ? AND operation_id = ?
                """, deadLetter ? "FAILED" : "RUNNING", errorCode,
                deadLetter, job.tenantId(), job.operationId());
        if (deadLetter) audit(job, "FAILED", errorCode, evidence);
        return new FailureDisposition(true, deadLetter);
    }

    @Transactional
    WebhookEnqueue enqueueVerifiedWebhook(
            MailWebhookVerificationPort.Verification verification,
            int maximumAttempts) {
        ConnectionIdentity connection = jdbc.query("""
                SELECT tenant_id, connection_id, provider_type, version
                  FROM mail_provider_connections
                 WHERE tenant_id = ? AND connection_id = ?
                   AND connection_state IN ('ACTIVE', 'DEGRADED')
                """, (result, ignored) -> new ConnectionIdentity(
                result.getLong("tenant_id"),
                result.getObject("connection_id", UUID.class),
                result.getString("provider_type"), result.getLong("version")),
                verification.tenantId(), verification.connectionId())
                .stream().findFirst().orElseThrow(() ->
                        new IllegalArgumentException("WEBHOOK_CONNECTION_UNAVAILABLE"));
        if (!connection.providerType().equals(verification.providerType())) {
            throw new IllegalArgumentException("WEBHOOK_PROVIDER_MISMATCH");
        }
        long systemActor = ensureSystemPrincipal(
                verification.tenantId(), "MAIL_WEBHOOK");
        UUID idempotencyKey = UUID.nameUUIDFromBytes(("mail-webhook:"
                + verification.tenantId() + ':' + verification.connectionId() + ':'
                + verification.eventKeyHash()).getBytes(StandardCharsets.UTF_8));
        UUID operationId = jdbc.query("""
                INSERT INTO mail_connection_operations (
                    tenant_id, connection_id, actor_user_id, operation_kind,
                    operation_scope, request_payload, idempotency_key,
                    request_fingerprint, correlation_id)
                VALUES (?, ?, ?, 'SYNC', 'WEBHOOK', ?::jsonb, ?, ?, ?)
                ON CONFLICT (tenant_id, actor_user_id, idempotency_key) DO NOTHING
                RETURNING operation_id
                """, (result, ignored) -> result.getObject("operation_id", UUID.class),
                verification.tenantId(), verification.connectionId(),
                systemActor,
                json(Map.of(
                        "source", "VERIFIED_WEBHOOK",
                        "systemPrincipal", "MAIL_WEBHOOK",
                        "provider", verification.providerType(),
                        "eventKeyHash", verification.eventKeyHash(),
                        "clientStateHash", verification.clientStateHash())),
                idempotencyKey, verification.eventKeyHash(),
                verification.correlationId()).stream().findFirst().orElseGet(() ->
                jdbc.queryForObject("""
                        SELECT operation_id FROM mail_connection_operations
                         WHERE tenant_id = ? AND actor_user_id = ?
                           AND idempotency_key = ?
                        """, UUID.class, verification.tenantId(), systemActor,
                idempotencyKey));
        if (operationId == null) {
            throw new IllegalStateException("WEBHOOK_OPERATION_UNAVAILABLE");
        }
        enqueue(verification.tenantId(), operationId, verification.connectionId(),
                systemActor, connection.version(), "WEBHOOK", maximumAttempts);
        UUID jobId = jdbc.queryForObject("""
                SELECT job_id FROM mail_sync_jobs WHERE operation_id = ?
                """, UUID.class, operationId);
        return new WebhookEnqueue(operationId, jobId);
    }

    @Transactional
    ResetTransition resetCursorAndEnqueueRecovery(
            MailSyncJob source,
            AdminMailCompletionRepository.AccountRow expectedAccount,
            String resetCursor) {
        if (resetCursor == null || resetCursor.isBlank() || resetCursor.length() > 2000) {
            throw new MailSyncWorkFailure(
                    "SYNC_RESET_CURSOR_INVALID", true);
        }
        Optional<ResetTransition> replay = resetTransition(
                source.tenantId(), source.jobId(), expectedAccount.id());
        if (replay.isPresent()) return replay.get();
        int sourceFenced = jdbc.update("""
                UPDATE mail_sync_jobs
                   SET job_state = 'DEAD_LETTER', lease_owner = NULL,
                       lease_expires_at = NULL,
                       last_error_code = 'SYNC_CURSOR_RESET_REQUIRED',
                       worker_evidence = jsonb_build_object(
                           'outcome', 'CURSOR_RESET_TRANSITIONING',
                           'attempt', attempt_count,
                           'trigger', trigger_kind),
                       completed_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND job_id = ? AND job_state = 'LEASED'
                   AND attempt_count = ? AND lease_expires_at > CURRENT_TIMESTAMP
                """, source.tenantId(), source.jobId(), source.attemptCount());
        if (sourceFenced != 1) {
            throw new MailSyncWorkFailure("SYNC_LEASE_LOST", false);
        }
        CursorIdentity current = jdbc.query("""
                SELECT synchronization_cursor, synchronization_cursor_generation
                  FROM mail_accounts
                 WHERE tenant_id = ? AND account_id = ?
                   AND connection_state = 'ACTIVE'
                 FOR UPDATE
                """, (result, ignored) -> new CursorIdentity(
                result.getString("synchronization_cursor"),
                result.getLong("synchronization_cursor_generation")),
                source.tenantId(), expectedAccount.id()).stream().findFirst()
                .orElseThrow(() -> new MailSyncWorkFailure(
                        "SYNC_ACCOUNT_UNAVAILABLE", true));
        if (!java.util.Objects.equals(current.cursor(), expectedAccount.cursor())
                || current.generation() != expectedAccount.cursorGeneration()) {
            throw new MailSyncWorkFailure("SYNC_CURSOR_CONFLICT", true);
        }
        int fenced = jdbc.update("""
                UPDATE mail_accounts
                   SET synchronization_cursor = ?,
                       synchronization_cursor_generation = synchronization_cursor_generation + 1,
                       synchronization_state = 'READY', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND account_id = ?
                   AND synchronization_cursor IS NOT DISTINCT FROM ?
                   AND synchronization_cursor_generation = ?
                """, resetCursor, source.actorId(), source.tenantId(),
                expectedAccount.id(), expectedAccount.cursor(),
                expectedAccount.cursorGeneration());
        if (fenced != 1) {
            throw new MailSyncWorkFailure("SYNC_CURSOR_CONFLICT", true);
        }
        long recoveryActor = ensureSystemPrincipal(
                source.tenantId(), "MAIL_SYNC_RECOVERY");
        UUID idempotencyKey = UUID.nameUUIDFromBytes(("mail-sync-recovery:"
                + source.jobId() + ':' + expectedAccount.id())
                .getBytes(StandardCharsets.UTF_8));
        String fingerprint = sha256((source.jobId() + ":" + expectedAccount.id()
                + ":" + current.generation()).getBytes(StandardCharsets.UTF_8));
        UUID recoveryOperation = jdbc.query("""
                INSERT INTO mail_connection_operations (
                    tenant_id, connection_id, actor_user_id, operation_kind,
                    operation_scope, request_payload, idempotency_key,
                    request_fingerprint, correlation_id)
                VALUES (?, ?, ?, 'SYNC', 'RECOVERY', ?::jsonb, ?, ?, ?)
                ON CONFLICT (tenant_id, actor_user_id, idempotency_key) DO NOTHING
                RETURNING operation_id
                """, (result, ignored) -> result.getObject("operation_id", UUID.class),
                source.tenantId(), source.connectionId(), recoveryActor,
                json(Map.of(
                        "source", "CURSOR_RESET_RECOVERY",
                        "systemPrincipal", "MAIL_SYNC_RECOVERY",
                        "sourceOperationId", source.operationId(),
                        "accountId", expectedAccount.id(),
                        "priorGeneration", current.generation(),
                        "resetGeneration", current.generation() + 1)),
                idempotencyKey, fingerprint, safe(source.correlationId()))
                .stream().findFirst().orElseGet(() -> jdbc.queryForObject("""
                        SELECT operation_id FROM mail_connection_operations
                         WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                        """, UUID.class, source.tenantId(), recoveryActor, idempotencyKey));
        if (recoveryOperation == null) {
            throw new IllegalStateException("SYNC_RECOVERY_OPERATION_UNAVAILABLE");
        }
        enqueue(source.tenantId(), recoveryOperation, source.connectionId(), recoveryActor,
                source.connectionVersion(), "RECOVERY", source.maximumAttempts());
        UUID recoveryJob = jdbc.queryForObject(
                "SELECT job_id FROM mail_sync_jobs WHERE operation_id = ?",
                UUID.class, recoveryOperation);
        if (recoveryJob == null) {
            throw new IllegalStateException("SYNC_RECOVERY_JOB_UNAVAILABLE");
        }
        String priorHash = sha256((current.cursor() == null
                ? "<empty>" : current.cursor()).getBytes(StandardCharsets.UTF_8));
        String resetHash = sha256(resetCursor.getBytes(StandardCharsets.UTF_8));
        UUID resetId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_sync_cursor_reset_receipts (
                    reset_id, tenant_id, source_job_id, source_operation_id,
                    account_id, prior_cursor_hash, reset_cursor_hash,
                    prior_generation, reset_generation,
                    recovery_operation_id, recovery_job_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, resetId, source.tenantId(), source.jobId(), source.operationId(),
                expectedAccount.id(), priorHash, resetHash, current.generation(),
                current.generation() + 1, recoveryOperation, recoveryJob);
        Map<String, Object> receipt = Map.of(
                "resetId", resetId,
                "accountId", expectedAccount.id(),
                "priorCursorHash", priorHash,
                "resetCursorHash", resetHash,
                "priorGeneration", current.generation(),
                "resetGeneration", current.generation() + 1,
                "recoveryOperationId", recoveryOperation,
                "recoveryJobId", recoveryJob);
        Map<String, Object> sourceEvidence = new java.util.LinkedHashMap<>(receipt);
        sourceEvidence.put("attempt", source.attemptCount());
        sourceEvidence.put("trigger", source.triggerKind());
        sourceEvidence.put("classification", "CURSOR_RESET");
        sourceEvidence.put("outcome", "DEAD_LETTER");
        sourceEvidence.put("errorCode", "SYNC_CURSOR_RESET_REQUIRED");
        jdbc.update("""
                UPDATE mail_sync_jobs SET cursor_reset_receipt = ?::jsonb,
                       worker_evidence = ?::jsonb,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND job_id = ? AND job_state = 'DEAD_LETTER'
                """, json(receipt), json(sourceEvidence),
                source.tenantId(), source.jobId());
        jdbc.update("""
                UPDATE mail_connection_operations
                   SET operation_state = 'FAILED',
                       error_code = 'SYNC_CURSOR_RESET_REQUIRED',
                       evidence_generated_at = CURRENT_TIMESTAMP,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND operation_id = ?
                """, source.tenantId(), source.operationId());
        audit(source, "FAILED", "SYNC_CURSOR_RESET_REQUIRED", sourceEvidence);
        return new ResetTransition(
                resetId, recoveryOperation, recoveryJob, Map.copyOf(receipt));
    }

    private boolean completedCursorReset(MailSyncJob job) {
        Boolean completed = jdbc.query("""
                SELECT job_state = 'DEAD_LETTER'
                       AND cursor_reset_receipt IS NOT NULL
                  FROM mail_sync_jobs
                 WHERE tenant_id = ? AND job_id = ?
                """, (result, ignored) -> result.getBoolean(1),
                job.tenantId(), job.jobId()).stream().findFirst().orElse(false);
        return Boolean.TRUE.equals(completed);
    }

    private Optional<ResetTransition> resetTransition(
            long tenantId, UUID sourceJobId, UUID accountId) {
        return jdbc.query("""
                SELECT reset_id, recovery_operation_id, recovery_job_id,
                       account_id, prior_cursor_hash, reset_cursor_hash,
                       prior_generation, reset_generation
                  FROM mail_sync_cursor_reset_receipts
                 WHERE tenant_id = ? AND source_job_id = ? AND account_id = ?
                """, (result, ignored) -> {
            Map<String, Object> evidence = Map.of(
                    "resetId", result.getObject("reset_id", UUID.class),
                    "accountId", result.getObject("account_id", UUID.class),
                    "priorCursorHash", result.getString("prior_cursor_hash"),
                    "resetCursorHash", result.getString("reset_cursor_hash"),
                    "priorGeneration", result.getLong("prior_generation"),
                    "resetGeneration", result.getLong("reset_generation"),
                    "recoveryOperationId",
                    result.getObject("recovery_operation_id", UUID.class),
                    "recoveryJobId", result.getObject("recovery_job_id", UUID.class));
            return new ResetTransition(
                    result.getObject("reset_id", UUID.class),
                    result.getObject("recovery_operation_id", UUID.class),
                    result.getObject("recovery_job_id", UUID.class), evidence);
        }, tenantId, sourceJobId, accountId).stream().findFirst();
    }

    private long ensureSystemPrincipal(long tenantId, String principalKey) {
        jdbc.update("""
                INSERT INTO mail_system_principals (
                    tenant_id, principal_key, principal_evidence)
                VALUES (?, ?, jsonb_build_object(
                    'kind', 'SYSTEM', 'owner', 'dwp-platform-server'))
                ON CONFLICT (tenant_id, principal_key) DO NOTHING
                """, tenantId, principalKey);
        Long actor = jdbc.queryForObject("""
                SELECT actor_id FROM mail_system_principals
                 WHERE tenant_id = ? AND principal_key = ?
                """, Long.class, tenantId, principalKey);
        if (actor == null || actor >= 0) {
            throw new IllegalStateException("MAIL_SYSTEM_PRINCIPAL_UNAVAILABLE");
        }
        return actor;
    }

    Optional<String> state(UUID operationId) {
        return jdbc.query("""
                SELECT job_state FROM mail_sync_jobs WHERE operation_id = ?
                """, (result, ignored) -> result.getString("job_state"), operationId)
                .stream().findFirst();
    }

    private void audit(
            MailSyncJob job,
            String result,
            String errorCode,
            Map<String, Object> evidence) {
        java.util.LinkedHashMap<String, Object> after = new java.util.LinkedHashMap<>(evidence);
        after.put("result", result);
        after.put("operationId", job.operationId());
        if (errorCode != null) after.put("errorCode", errorCode);
        jdbc.update("""
                INSERT INTO mail_audit_events (
                    audit_event_id, tenant_id, actor_user_id, action,
                    target_type, target_id, correlation_id,
                    before_snapshot, after_snapshot)
                VALUES (?, ?, ?, 'mail.connection.sync', 'MAIL_CONNECTION', ?, ?,
                        '{}'::jsonb, ?::jsonb)
                """, UUID.randomUUID(), job.tenantId(), job.actorId(),
                job.connectionId().toString(), safe(job.correlationId()), json(after));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Unable to encode Mail sync evidence", failure);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    record FailureDisposition(boolean persisted, boolean terminal) { }
    record WebhookEnqueue(UUID operationId, UUID jobId) { }
    record ResetTransition(
            UUID resetId,
            UUID recoveryOperationId,
            UUID recoveryJobId,
            Map<String, Object> evidence) { }
    private record ConnectionIdentity(
            long tenantId, UUID connectionId, String providerType, long version) { }
    private record CursorIdentity(String cursor, long generation) { }
    private record ExpiredLease(MailSyncJob job, String state) { }
}
