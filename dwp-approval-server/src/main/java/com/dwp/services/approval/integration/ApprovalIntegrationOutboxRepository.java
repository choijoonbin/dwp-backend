package com.dwp.services.approval.integration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public class ApprovalIntegrationOutboxRepository {

    private final JdbcTemplate jdbc;

    public ApprovalIntegrationOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<PendingEvent> claim(int batchSize, String workerId) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT outbox_id
                      FROM apr_integration_outbox
                     WHERE available_at <= CURRENT_TIMESTAMP
                       AND (status IN ('PENDING', 'FAILED')
                            OR (status = 'SENDING' AND locked_until < CURRENT_TIMESTAMP))
                     ORDER BY created_at, outbox_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), claimed AS (
                    UPDATE apr_integration_outbox event
                       SET status = 'SENDING', attempt_count = attempt_count + 1,
                           locked_by = ?, locked_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                           updated_at = CURRENT_TIMESTAMP
                      FROM candidates
                     WHERE event.outbox_id = candidates.outbox_id
                    RETURNING event.outbox_id, event.event_id, event.tenant_id,
                              event.request_id, event.event_type, event.payload::text,
                              event.attempt_count
                )
                SELECT * FROM claimed ORDER BY outbox_id
                """, (result, ignored) -> new PendingEvent(
                result.getObject("outbox_id", UUID.class),
                result.getObject("event_id", UUID.class),
                result.getLong("tenant_id"),
                result.getObject("request_id", UUID.class),
                result.getString("event_type"),
                result.getString("payload"),
                result.getInt("attempt_count")), batchSize, workerId);
    }

    public void markPublished(UUID outboxId, String workerId) {
        jdbc.update("""
                UPDATE apr_integration_outbox
                   SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, locked_until = NULL, last_error = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE outbox_id = ? AND status = 'SENDING' AND locked_by = ?
                """, outboxId, workerId);
    }

    public void markFailed(
            UUID outboxId,
            String workerId,
            int attemptCount,
            int maximumAttempts,
            String error) {
        long delaySeconds = Math.min(900L, 1L << Math.min(9, Math.max(1, attemptCount)));
        jdbc.update("""
                UPDATE apr_integration_outbox
                   SET status = CASE WHEN ? >= ? THEN 'DEAD' ELSE 'FAILED' END,
                       available_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                       locked_by = NULL, locked_until = NULL, last_error = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE outbox_id = ? AND status = 'SENDING' AND locked_by = ?
                """, attemptCount, maximumAttempts, delaySeconds,
                truncate(error, 1000), outboxId, workerId);
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.isBlank()) return "Unknown approval event delivery failure";
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    public record PendingEvent(
            UUID outboxId,
            UUID eventId,
            long tenantId,
            UUID requestId,
            String eventType,
            String payload,
            int attemptCount) { }
}
