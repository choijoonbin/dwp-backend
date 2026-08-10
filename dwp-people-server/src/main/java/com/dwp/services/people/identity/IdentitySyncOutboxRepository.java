package com.dwp.services.people.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class IdentitySyncOutboxRepository {

    private final JdbcTemplate jdbc;

    public IdentitySyncOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<PendingEvent> claim(int batchSize) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT event.event_id
                      FROM sys_people_outbox_events event
                     WHERE event.event_type = 'people.worker-projection.changed'
                       AND event.published_at IS NULL
                       AND event.dead_lettered_at IS NULL
                       AND event.next_attempt_at <= CURRENT_TIMESTAMP
                     ORDER BY event.occurred_at, event.event_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), claimed AS (
                    UPDATE sys_people_outbox_events event
                       SET attempt_count = event.attempt_count + 1,
                           next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                      FROM candidates
                     WHERE event.event_id = candidates.event_id
                    RETURNING event.event_id, event.tenant_id, event.payload,
                              event.correlation_id, event.attempt_count
                )
                SELECT claimed.event_id,
                       tenant.provider_tenant_id,
                       claimed.payload::text AS payload,
                       claimed.correlation_id,
                       claimed.attempt_count
                  FROM claimed
                  JOIN sys_service_tenants tenant ON tenant.tenant_id = claimed.tenant_id
                 ORDER BY claimed.event_id
                """, (result, ignored) -> new PendingEvent(
                result.getObject("event_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("payload"),
                result.getString("correlation_id"),
                result.getInt("attempt_count")), batchSize);
    }

    public void markPublished(UUID eventId) {
        jdbc.update("""
                UPDATE sys_people_outbox_events
                   SET published_at = CURRENT_TIMESTAMP,
                       last_error = NULL
                 WHERE event_id = ? AND published_at IS NULL
                """, eventId);
    }

    public void markFailed(UUID eventId, int attemptCount, int maximumAttempts, String error) {
        long delaySeconds = Math.min(300L, 1L << Math.min(8, Math.max(1, attemptCount)));
        jdbc.update("""
                UPDATE sys_people_outbox_events
                   SET last_error = ?,
                       next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                       dead_lettered_at = CASE WHEN ? >= ? THEN CURRENT_TIMESTAMP ELSE NULL END
                 WHERE event_id = ? AND published_at IS NULL
                """, truncate(error, 1000), delaySeconds, attemptCount, maximumAttempts, eventId);
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null) return "Unknown identity sync failure";
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    public record PendingEvent(
            UUID eventId,
            UUID providerTenantId,
            String payload,
            String correlationId,
            int attemptCount) {
    }
}
