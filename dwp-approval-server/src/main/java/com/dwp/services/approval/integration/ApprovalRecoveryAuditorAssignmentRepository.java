package com.dwp.services.approval.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public class ApprovalRecoveryAuditorAssignmentRepository {

    private final JdbcTemplate jdbc;

    public ApprovalRecoveryAuditorAssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<AssignmentTarget> claim(
            int batchSize,
            String workerId,
            int leaseSeconds,
            int maximumAttempts,
            long probeCooldownSeconds) {
        int boundedBatchSize = Math.max(1, Math.min(batchSize, 200));
        int boundedLeaseSeconds = Math.max(5, Math.min(leaseSeconds, 300));
        int boundedMaximumAttempts = Math.max(1, Math.min(maximumAttempts, 100));
        long boundedProbeCooldownSeconds = Math.max(
                3600L, Math.min(probeCooldownSeconds, 604800L));
        closePublishedWithoutRecovery();
        exhaustAttemptBudget(
                boundedMaximumAttempts, boundedProbeCooldownSeconds, workerId);
        openDueProbeEpochs(boundedBatchSize, workerId);
        return jdbc.query("""
                WITH candidates AS (
                    SELECT outbox_id
                      FROM apr_integration_outbox
                     WHERE event_originator_user_id IS NOT NULL
                       AND recovery_auditor_assignment_attempt_count < ?
                       AND recovery_auditor_assignment_available_at <= CURRENT_TIMESTAMP
                       AND (recovery_auditor_assignment_state IN ('PENDING', 'RETRY')
                            OR (recovery_auditor_assignment_state = 'ASSIGNING'
                                AND recovery_auditor_assignment_locked_until
                                    < CURRENT_TIMESTAMP))
                     ORDER BY recovery_auditor_assignment_available_at,
                              created_at, outbox_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), claimed AS (
                    UPDATE apr_integration_outbox event
                       SET recovery_auditor_assignment_state = 'ASSIGNING',
                           recovery_auditor_assignment_attempt_count =
                               recovery_auditor_assignment_attempt_count + 1,
                           recovery_auditor_assignment_locked_by = ?,
                           recovery_auditor_assignment_locked_until =
                               CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                           recovery_auditor_assignment_last_error = NULL,
                           updated_at = CURRENT_TIMESTAMP
                      FROM candidates
                     WHERE event.outbox_id = candidates.outbox_id
                    RETURNING event.outbox_id, event.tenant_id,
                              event.event_originator_user_id,
                              event.management_resource_set_key,
                              event.recovery_auditor_assignment_attempt_count,
                              event.recovery_auditor_assignment_epoch
                )
                SELECT * FROM claimed ORDER BY outbox_id
                """, (result, ignored) -> new AssignmentTarget(
                result.getObject("outbox_id", UUID.class),
                result.getLong("tenant_id"),
                result.getLong("event_originator_user_id"),
                result.getString("management_resource_set_key"),
                result.getInt("recovery_auditor_assignment_attempt_count"),
                result.getInt("recovery_auditor_assignment_epoch")),
                boundedMaximumAttempts, boundedBatchSize, workerId, boundedLeaseSeconds);
    }

    public boolean markAssigned(
            AssignmentTarget target,
            String workerId,
            ApprovalRecoveryAuditorResolver.Assignment assignment) {
        if (assignment.selectedUserId() <= 0
                || assignment.selectedUserId() == target.originatorUserId()
                || target.managementResourceSetKey() == null
                || !target.managementResourceSetKey().equals(assignment.resourceSetKey())
                || assignment.assignmentRevision() == null
                || assignment.assignmentRevision().isBlank()
                || assignment.assignmentRevision().length() > 240) {
            return false;
        }
        return jdbc.update("""
                UPDATE apr_integration_outbox
                   SET recovery_auditor_assignment_state = 'ASSIGNED',
                       assigned_auditor_user_id = ?,
                       recovery_auditor_resource_set_key = ?,
                       recovery_auditor_assignment_revision = ?,
                       recovery_auditor_assigned_at = CURRENT_TIMESTAMP,
                       recovery_auditor_assignment_locked_by = NULL,
                       recovery_auditor_assignment_locked_until = NULL,
                       recovery_auditor_assignment_last_error = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE outbox_id = ?
                   AND tenant_id = ?
                   AND management_resource_set_key = ?
                   AND event_originator_user_id = ?
                   AND event_originator_user_id <> ?
                   AND assigned_auditor_user_id IS NULL
                   AND recovery_auditor_assignment_state = 'ASSIGNING'
                   AND recovery_auditor_assignment_locked_by = ?
                """, assignment.selectedUserId(), assignment.resourceSetKey(),
                assignment.assignmentRevision(), target.outboxId(), target.tenantId(),
                target.managementResourceSetKey(),
                target.originatorUserId(), assignment.selectedUserId(), workerId) == 1;
    }

    public boolean markUnavailable(
            AssignmentTarget target,
            String workerId,
            int maximumAttempts,
            long probeCooldownSeconds,
            long maximumProbeCooldownSeconds,
            String error) {
        int boundedMaximumAttempts = Math.max(1, Math.min(maximumAttempts, 100));
        long boundedProbeCooldownSeconds = Math.max(
                3600L, Math.min(probeCooldownSeconds, 604800L));
        long boundedMaximumProbeCooldownSeconds = Math.max(
                boundedProbeCooldownSeconds,
                Math.min(maximumProbeCooldownSeconds, 2592000L));
        if (target.attemptCount() >= boundedMaximumAttempts) {
            long cooldownSeconds = probeCooldown(
                    target.assignmentEpoch(),
                    boundedProbeCooldownSeconds,
                    boundedMaximumProbeCooldownSeconds);
            return markExhausted(target, workerId, cooldownSeconds, error);
        }
        long delaySeconds = Math.min(
                900L,
                1L << Math.min(9, Math.max(1, target.attemptCount())));
        return jdbc.update("""
                UPDATE apr_integration_outbox
                   SET recovery_auditor_assignment_state = 'RETRY',
                       recovery_auditor_assignment_available_at =
                           CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                       recovery_auditor_assignment_locked_by = NULL,
                       recovery_auditor_assignment_locked_until = NULL,
                       recovery_auditor_assignment_last_error = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE outbox_id = ?
                   AND tenant_id = ?
                   AND management_resource_set_key = ?
                   AND recovery_auditor_assignment_state = 'ASSIGNING'
                   AND recovery_auditor_assignment_locked_by = ?
                """, delaySeconds, truncate(error, 1000),
                target.outboxId(), target.tenantId(),
                target.managementResourceSetKey(), workerId) == 1;
    }

    private boolean markExhausted(
            AssignmentTarget target,
            String workerId,
            long cooldownSeconds,
            String error) {
        Integer transitioned = jdbc.queryForObject("""
                WITH exhausted AS (
                    UPDATE apr_integration_outbox
                       SET recovery_auditor_assignment_state = 'EXHAUSTED',
                           recovery_auditor_assignment_locked_by = NULL,
                           recovery_auditor_assignment_locked_until = NULL,
                           recovery_auditor_assignment_exhausted_at = CURRENT_TIMESTAMP,
                           recovery_auditor_assignment_next_probe_at =
                               CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                           recovery_auditor_assignment_last_error = ?,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE outbox_id = ?
                       AND tenant_id = ?
                       AND management_resource_set_key = ?
                       AND recovery_auditor_assignment_state = 'ASSIGNING'
                       AND recovery_auditor_assignment_locked_by = ?
                    RETURNING outbox_id, tenant_id,
                              recovery_auditor_assignment_epoch,
                              recovery_auditor_assignment_attempt_count
                ), recorded AS (
                    INSERT INTO apr_recovery_auditor_assignment_events (
                        assignment_event_id, outbox_id, tenant_id,
                        assignment_epoch, event_type, attempt_count,
                        reason_code, worker_id)
                    SELECT gen_random_uuid(), outbox_id, tenant_id,
                           recovery_auditor_assignment_epoch, 'EPOCH_EXHAUSTED',
                           recovery_auditor_assignment_attempt_count,
                           'AUTH_RESOLUTION_UNAVAILABLE', ?
                      FROM exhausted
                    RETURNING assignment_event_id
                )
                SELECT (SELECT COUNT(*) FROM exhausted)
                  FROM (SELECT COUNT(*) FROM recorded) evidence
                """, Integer.class, cooldownSeconds, truncate(error, 1000),
                target.outboxId(), target.tenantId(),
                target.managementResourceSetKey(), workerId, workerId);
        return transitioned != null && transitioned == 1;
    }

    private void exhaustAttemptBudget(
            int maximumAttempts,
            long probeCooldownSeconds,
            String workerId) {
        jdbc.queryForObject("""
                WITH exhausted AS (
                    UPDATE apr_integration_outbox
                       SET recovery_auditor_assignment_state = 'EXHAUSTED',
                           recovery_auditor_assignment_locked_by = NULL,
                           recovery_auditor_assignment_locked_until = NULL,
                           recovery_auditor_assignment_exhausted_at = CURRENT_TIMESTAMP,
                           recovery_auditor_assignment_next_probe_at =
                               CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                           recovery_auditor_assignment_last_error = COALESCE(
                               recovery_auditor_assignment_last_error,
                               'Approval recovery auditor attempt budget exhausted'),
                           updated_at = CURRENT_TIMESTAMP
                     WHERE recovery_auditor_assignment_attempt_count >= ?
                       AND (recovery_auditor_assignment_state IN ('PENDING', 'RETRY')
                            OR (recovery_auditor_assignment_state = 'ASSIGNING'
                                AND recovery_auditor_assignment_locked_until
                                    < CURRENT_TIMESTAMP))
                    RETURNING outbox_id, tenant_id,
                              recovery_auditor_assignment_epoch,
                              recovery_auditor_assignment_attempt_count
                ), recorded AS (
                    INSERT INTO apr_recovery_auditor_assignment_events (
                        assignment_event_id, outbox_id, tenant_id,
                        assignment_epoch, event_type, attempt_count,
                        reason_code, worker_id)
                    SELECT gen_random_uuid(), outbox_id, tenant_id,
                           recovery_auditor_assignment_epoch, 'EPOCH_EXHAUSTED',
                           recovery_auditor_assignment_attempt_count,
                           'ATTEMPT_BUDGET_RECONFIGURED', ?
                      FROM exhausted
                    RETURNING assignment_event_id
                )
                SELECT (SELECT COUNT(*) FROM exhausted)
                  FROM (SELECT COUNT(*) FROM recorded) evidence
                """, Integer.class, probeCooldownSeconds, maximumAttempts, workerId);
    }

    private void openDueProbeEpochs(int batchSize, String workerId) {
        jdbc.queryForObject("""
                WITH candidates AS (
                    SELECT outbox_id
                      FROM apr_integration_outbox
                     WHERE recovery_auditor_assignment_state = 'EXHAUSTED'
                       AND status IN ('FAILED', 'DEAD')
                       AND recovery_auditor_assignment_next_probe_at
                           <= CURRENT_TIMESTAMP
                     ORDER BY recovery_auditor_assignment_next_probe_at,
                              created_at, outbox_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), opened AS (
                    UPDATE apr_integration_outbox event
                       SET recovery_auditor_assignment_state = 'PENDING',
                           recovery_auditor_assignment_epoch =
                               recovery_auditor_assignment_epoch + 1,
                           recovery_auditor_assignment_attempt_count = 0,
                           recovery_auditor_assignment_available_at = CURRENT_TIMESTAMP,
                           recovery_auditor_assignment_exhausted_at = NULL,
                           recovery_auditor_assignment_next_probe_at = NULL,
                           recovery_auditor_assignment_last_error = NULL,
                           updated_at = CURRENT_TIMESTAMP
                      FROM candidates
                     WHERE event.outbox_id = candidates.outbox_id
                    RETURNING event.outbox_id, event.tenant_id,
                              event.recovery_auditor_assignment_epoch
                ), recorded AS (
                    INSERT INTO apr_recovery_auditor_assignment_events (
                        assignment_event_id, outbox_id, tenant_id,
                        assignment_epoch, event_type, attempt_count,
                        reason_code, worker_id)
                    SELECT gen_random_uuid(), outbox_id, tenant_id,
                           recovery_auditor_assignment_epoch,
                           'AUTOMATIC_PROBE_EPOCH_OPENED', 0,
                           'COOLDOWN_ELAPSED', ?
                      FROM opened
                    RETURNING assignment_event_id
                )
                SELECT (SELECT COUNT(*) FROM opened)
                  FROM (SELECT COUNT(*) FROM recorded) evidence
                """, Integer.class, batchSize, workerId);
    }

    private void closePublishedWithoutRecovery() {
        jdbc.update("""
                UPDATE apr_integration_outbox
                   SET recovery_auditor_assignment_state = 'NOT_REQUIRED',
                       recovery_auditor_assignment_locked_by = NULL,
                       recovery_auditor_assignment_locked_until = NULL,
                       recovery_auditor_assignment_exhausted_at = NULL,
                       recovery_auditor_assignment_next_probe_at = NULL,
                       recovery_auditor_assignment_last_error = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE status = 'PUBLISHED'
                   AND recovery_auditor_assignment_state IN (
                       'PENDING', 'ASSIGNING', 'RETRY', 'EXHAUSTED')
                """);
    }

    private long probeCooldown(
            int assignmentEpoch,
            long initialCooldownSeconds,
            long maximumCooldownSeconds) {
        long value = initialCooldownSeconds;
        for (int epoch = 1; epoch < assignmentEpoch && value < maximumCooldownSeconds; epoch++) {
            value = Math.min(maximumCooldownSeconds, Math.multiplyExact(value, 2L));
        }
        return value;
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "Approval recovery auditor resolution unavailable";
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    public record AssignmentTarget(
            UUID outboxId,
            long tenantId,
            long originatorUserId,
            String managementResourceSetKey,
            int attemptCount,
            int assignmentEpoch) {

        public AssignmentTarget(
                UUID outboxId,
                long tenantId,
                long originatorUserId,
                int attemptCount,
                int assignmentEpoch) {
            this(outboxId, tenantId, originatorUserId,
                    "RS_APPROVALS", attemptCount, assignmentEpoch);
        }
    }
}
