package com.dwp.services.people.hr;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public class HrMailProposalOutcomeOutboxRepository {

    private final JdbcTemplate jdbc;

    public HrMailProposalOutcomeOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            UUID leaveRequestId,
            String resultRef,
            String correlationId) {
        int inserted = jdbc.update("""
                INSERT INTO abs_mail_proposal_outcome_outbox (
                    outcome_id, tenant_id, actor_id, proposal_id, command_id,
                    proposal_version, leave_request_id, result_ref, correlation_id,
                    delivery_state, attempt_count, next_attempt_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, proposal_id) DO NOTHING
                """,
                UUID.randomUUID(), tenantId, actorId,
                binding.proposalId(), binding.commandId(), binding.proposalVersion(),
                leaveRequestId, resultRef, normalizeCorrelation(correlationId));
        if (inserted != 1) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The Mail proposal has already created an HR leave request.");
        }
    }

    @Transactional
    public List<PendingOutcome> claim(int batchSize) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT outcome.outcome_id
                      FROM abs_mail_proposal_outcome_outbox outcome
                     WHERE outcome.delivery_state = 'PENDING'
                       AND outcome.next_attempt_at <= CURRENT_TIMESTAMP
                     ORDER BY outcome.created_at, outcome.outcome_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), claimed AS (
                    UPDATE abs_mail_proposal_outcome_outbox outcome
                       SET attempt_count = outcome.attempt_count + 1,
                           claimed_at = CURRENT_TIMESTAMP,
                           next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                      FROM candidates
                     WHERE outcome.outcome_id = candidates.outcome_id
                    RETURNING outcome.outcome_id, outcome.tenant_id, outcome.actor_id,
                              outcome.proposal_id, outcome.command_id,
                              outcome.proposal_version, outcome.result_ref,
                              outcome.correlation_id, outcome.attempt_count
                )
                SELECT * FROM claimed ORDER BY outcome_id
                """, (result, ignored) -> new PendingOutcome(
                result.getObject("outcome_id", UUID.class),
                result.getLong("tenant_id"),
                result.getLong("actor_id"),
                result.getObject("proposal_id", UUID.class),
                result.getObject("command_id", UUID.class),
                result.getLong("proposal_version"),
                result.getString("result_ref"),
                result.getString("correlation_id"),
                result.getInt("attempt_count")), Math.min(200, Math.max(1, batchSize)));
    }

    public void markPublished(UUID outcomeId) {
        jdbc.update("""
                UPDATE abs_mail_proposal_outcome_outbox
                   SET published_at = CURRENT_TIMESTAMP,
                       delivery_state = 'PUBLISHED',
                       claimed_at = NULL,
                       last_error = NULL
                 WHERE outcome_id = ? AND delivery_state = 'PENDING'
                """, outcomeId);
    }

    public void markFailed(
            UUID outcomeId,
            int attemptCount,
            int maximumAttempts,
            boolean retryable,
            String error) {
        long delaySeconds = Math.min(300L, 1L << Math.min(8, Math.max(1, attemptCount)));
        int boundedMaximumAttempts = Math.max(1, maximumAttempts);
        boolean permanent = !retryable;
        jdbc.update("""
                UPDATE abs_mail_proposal_outcome_outbox
                   SET claimed_at = NULL,
                       last_error = ?,
                       delivery_state = CASE
                           WHEN ? OR attempt_count >= ?
                           THEN 'UNKNOWN_RECONCILE' ELSE 'PENDING'
                       END,
                       terminal_at = CASE
                           WHEN ? OR attempt_count >= ?
                           THEN CURRENT_TIMESTAMP ELSE NULL
                       END,
                       next_attempt_at = CASE
                           WHEN ? OR attempt_count >= ? THEN next_attempt_at
                           ELSE CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
                       END
                 WHERE outcome_id = ? AND delivery_state = 'PENDING'
                """, truncate(error, 2000),
                permanent, boundedMaximumAttempts,
                permanent, boundedMaximumAttempts,
                permanent, boundedMaximumAttempts,
                delaySeconds, outcomeId);
    }

    private static String normalizeCorrelation(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) return null;
        return correlationId.strip();
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null || value.isBlank()) return "Unknown Mail proposal outcome failure";
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    public record PendingOutcome(
            UUID outcomeId,
            long tenantId,
            long actorId,
            UUID proposalId,
            UUID commandId,
            long proposalVersion,
            String resultRef,
            String correlationId,
            int attemptCount) {
    }
}
