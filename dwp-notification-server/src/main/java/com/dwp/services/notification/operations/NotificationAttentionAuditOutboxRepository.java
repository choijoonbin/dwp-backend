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
public class NotificationAttentionAuditOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationAttentionAuditOutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AttentionAuditEvent> lease(
            long tenantId,
            String owner,
            Instant now,
            Instant leaseUntil,
            int batchSize) {
        return jdbc.query("""
                WITH due AS (
                    SELECT event_id
                      FROM ntf_attention_rule_audit_outbox
                     WHERE tenant_id = :tenantId
                       AND published_at IS NULL
                       AND dead_at IS NULL
                       AND available_at <= :now
                       AND (lease_until IS NULL OR lease_until <= :now)
                     ORDER BY available_at, occurred_at, event_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT :batchSize
                )
                UPDATE ntf_attention_rule_audit_outbox event
                   SET lease_owner = :owner,
                       lease_until = :leaseUntil,
                       attempt_count = event.attempt_count + 1,
                       last_error = NULL,
                       updated_at = CURRENT_TIMESTAMP
                  FROM due
                 WHERE event.event_id = due.event_id
                RETURNING event.event_id, event.tenant_id, event.user_id,
                          event.subject_type, event.subject_id, event.event_type,
                          event.scope_kind, event.effect, event.subject_version,
                          event.occurred_at, event.attempt_count
                """, params(tenantId)
                .addValue("owner", owner)
                .addValue("now", Timestamp.from(now))
                .addValue("leaseUntil", Timestamp.from(leaseUntil))
                .addValue("batchSize", batchSize), this::map);
    }

    public boolean markPublished(
            long tenantId,
            UUID eventId,
            String owner,
            Instant publishedAt) {
        return jdbc.update("""
                UPDATE ntf_attention_rule_audit_outbox
                   SET published_at = :publishedAt,
                       lease_owner = NULL,
                       lease_until = NULL,
                       last_error = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND event_id = :eventId
                   AND lease_owner = :owner
                   AND published_at IS NULL
                   AND dead_at IS NULL
                """, identity(tenantId, eventId, owner)
                .addValue("publishedAt", Timestamp.from(publishedAt))) == 1;
    }

    public boolean markFailed(
            long tenantId,
            UUID eventId,
            String owner,
            int attemptCount,
            int maximumAttempts,
            Instant nextAttemptAt,
            String error) {
        return jdbc.update("""
                UPDATE ntf_attention_rule_audit_outbox
                   SET lease_owner = NULL,
                       lease_until = NULL,
                       available_at = :nextAttemptAt,
                       last_error = :lastError,
                       dead_at = CASE
                           WHEN :attemptCount >= :maximumAttempts
                               THEN CURRENT_TIMESTAMP
                           ELSE NULL
                       END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND event_id = :eventId
                   AND lease_owner = :owner
                   AND published_at IS NULL
                   AND dead_at IS NULL
                """, identity(tenantId, eventId, owner)
                .addValue("attemptCount", attemptCount)
                .addValue("maximumAttempts", maximumAttempts)
                .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .addValue("lastError", bounded(error, 1000))) == 1;
    }

    public int cleanupPublished(long tenantId, Instant cutoff, int batchSize) {
        Integer removed = jdbc.queryForObject("""
                SELECT ntf_purge_published_attention_audit_outbox(
                    :tenantId, :cutoff, :batchSize)
                """, params(tenantId)
                .addValue("cutoff", Timestamp.from(cutoff))
                .addValue("batchSize", batchSize), Integer.class);
        return removed == null ? 0 : removed;
    }

    public long deadCount(long tenantId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM ntf_attention_rule_audit_outbox
                 WHERE tenant_id = :tenantId
                   AND dead_at IS NOT NULL
                   AND published_at IS NULL
                """, params(tenantId), Long.class);
        return count == null ? 0L : count;
    }

    private MapSqlParameterSource identity(long tenantId, UUID eventId, String owner) {
        return params(tenantId)
                .addValue("eventId", eventId)
                .addValue("owner", owner);
    }

    private MapSqlParameterSource params(long tenantId) {
        return new MapSqlParameterSource("tenantId", tenantId);
    }

    private AttentionAuditEvent map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AttentionAuditEvent(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getLong("tenant_id"),
                resultSet.getLong("user_id"),
                resultSet.getString("subject_type"),
                resultSet.getObject("subject_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getString("scope_kind"),
                resultSet.getString("effect"),
                resultSet.getLong("subject_version"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getInt("attempt_count"));
    }

    private String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) return "Unknown attention audit relay failure";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    public record AttentionAuditEvent(
            UUID eventId,
            long tenantId,
            long userId,
            String subjectType,
            UUID subjectId,
            String eventType,
            String scopeKind,
            String effect,
            long subjectVersion,
            Instant occurredAt,
            int attemptCount) {
    }
}
