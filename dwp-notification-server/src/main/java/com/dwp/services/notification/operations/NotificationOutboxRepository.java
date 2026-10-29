package com.dwp.services.notification.operations;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationOutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<OutboxEvent> lease(
            long tenantId,
            String owner,
            Instant now,
            Instant leaseUntil,
            int batchSize) {
        return jdbc.query("""
                WITH due AS (
                    SELECT outbox_id
                      FROM ntf_outbox_events
                     WHERE tenant_id = :tenantId
                       AND published_at IS NULL
                       AND dead_at IS NULL
                       AND next_attempt_at <= :now
                       AND (lease_until IS NULL OR lease_until <= :now)
                     ORDER BY next_attempt_at, created_at, outbox_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT :batchSize
                )
                UPDATE ntf_outbox_events event
                   SET lease_owner = :owner,
                       lease_until = :leaseUntil,
                       attempt_count = event.attempt_count + 1,
                       last_error = NULL
                  FROM due
                 WHERE event.outbox_id = due.outbox_id
                RETURNING event.outbox_id, event.tenant_id,
                          event.aggregate_type, event.aggregate_id,
                          event.event_type, event.event_key,
                          event.payload::text AS payload,
                          event.occurred_at, event.attempt_count
                """, tenant(tenantId)
                .addValue("owner", owner)
                .addValue("now", Timestamp.from(now))
                .addValue("leaseUntil", Timestamp.from(leaseUntil))
                .addValue("batchSize", batchSize), this::map);
    }

    public boolean markPublished(
            long tenantId,
            UUID outboxId,
            String owner,
            Instant publishedAt) {
        return jdbc.update("""
                UPDATE ntf_outbox_events
                   SET published_at = :publishedAt,
                       lease_owner = NULL,
                       lease_until = NULL,
                       last_error = NULL
                 WHERE tenant_id = :tenantId
                   AND outbox_id = :outboxId
                   AND lease_owner = :owner
                   AND published_at IS NULL
                """, identity(tenantId, outboxId, owner)
                .addValue("publishedAt", Timestamp.from(publishedAt))) == 1;
    }

    public boolean markFailed(
            long tenantId,
            UUID outboxId,
            String owner,
            int attemptCount,
            int maximumAttempts,
            Instant nextAttemptAt,
            String error) {
        return jdbc.update("""
                UPDATE ntf_outbox_events
                   SET lease_owner = NULL,
                       lease_until = NULL,
                       next_attempt_at = :nextAttemptAt,
                       last_error = :lastError,
                       dead_at = CASE
                           WHEN :attemptCount >= :maximumAttempts
                               THEN CURRENT_TIMESTAMP
                           ELSE NULL
                       END
                 WHERE tenant_id = :tenantId
                   AND outbox_id = :outboxId
                   AND lease_owner = :owner
                   AND published_at IS NULL
                """, identity(tenantId, outboxId, owner)
                .addValue("attemptCount", attemptCount)
                .addValue("maximumAttempts", maximumAttempts)
                .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .addValue("lastError", bounded(error, 1000))) == 1;
    }

    public int cleanupPublished(
            long tenantId,
            Instant cutoff,
            int batchSize) {
        return jdbc.update("""
                DELETE FROM ntf_outbox_events
                 WHERE outbox_id IN (
                     SELECT outbox_id
                       FROM ntf_outbox_events
                      WHERE tenant_id = :tenantId
                        AND published_at IS NOT NULL
                        AND published_at < :cutoff
                      ORDER BY published_at, outbox_id
                      LIMIT :batchSize
                 )
                """, tenant(tenantId)
                .addValue("cutoff", Timestamp.from(cutoff))
                .addValue("batchSize", batchSize));
    }

    public OutboxStatus status(long tenantId) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (
                           WHERE published_at IS NULL AND dead_at IS NULL) AS backlog,
                       count(*) FILTER (
                           WHERE published_at IS NULL AND dead_at IS NULL
                             AND attempt_count > 0) AS retries,
                       count(*) FILTER (
                           WHERE published_at IS NULL AND dead_at IS NOT NULL) AS dead,
                       min(created_at) FILTER (
                           WHERE published_at IS NULL AND dead_at IS NULL) AS oldest_created_at
                  FROM ntf_outbox_events
                 WHERE tenant_id = :tenantId
                """, tenant(tenantId), (resultSet, ignored) -> {
                    Timestamp oldest = resultSet.getTimestamp("oldest_created_at");
                    return new OutboxStatus(
                            resultSet.getLong("backlog"),
                            resultSet.getLong("retries"),
                            resultSet.getLong("dead"),
                            oldest == null ? null : oldest.toInstant());
                });
    }

    private MapSqlParameterSource identity(
            long tenantId,
            UUID outboxId,
            String owner) {
        return tenant(tenantId)
                .addValue("outboxId", outboxId)
                .addValue("owner", owner);
    }

    private MapSqlParameterSource tenant(long tenantId) {
        return new MapSqlParameterSource("tenantId", tenantId);
    }

    private OutboxEvent map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new OutboxEvent(
                resultSet.getObject("outbox_id", UUID.class),
                resultSet.getLong("tenant_id"),
                resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"),
                resultSet.getString("event_type"),
                resultSet.getString("event_key"),
                resultSet.getString("payload"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getInt("attempt_count"));
    }

    private String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) return "Unknown outbox relay failure";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    public record OutboxEvent(
            UUID outboxId,
            long tenantId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String eventKey,
            String payload,
            Instant occurredAt,
            int attemptCount) {
    }

    public record OutboxStatus(
            long backlog,
            long retries,
            long dead,
            Instant oldestCreatedAt) {
    }
}
