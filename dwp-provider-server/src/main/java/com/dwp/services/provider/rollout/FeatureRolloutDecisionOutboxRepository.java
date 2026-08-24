package com.dwp.services.provider.rollout;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class FeatureRolloutDecisionOutboxRepository {

    private final JdbcTemplate jdbc;

    public FeatureRolloutDecisionOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public long appendAllTenants(UUID flagId, String flagKey, String state) {
        Long revision = jdbc.queryForObject("""
                INSERT INTO prv_feature_rollout_decision_revision (
                    feature_flag_id, opaque_revision, updated_at)
                VALUES (?, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (feature_flag_id)
                DO UPDATE SET
                    opaque_revision =
                        prv_feature_rollout_decision_revision.opaque_revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING opaque_revision
                """, Long.class, flagId);
        if (revision == null || revision < 1) {
            throw new IllegalStateException("Feature rollout decision revision was not advanced");
        }
        jdbc.update("""
                INSERT INTO prv_feature_rollout_decision_outbox (
                    event_id, auth_tenant_id, tenant_scope, flag_key,
                    opaque_revision, state)
                VALUES (?, NULL, 'ALL', ?, ?, ?)
                """, UUID.randomUUID(), flagKey, revision, state);
        return revision;
    }

    public long revision(UUID flagId) {
        Long revision = jdbc.queryForObject("""
                SELECT COALESCE((
                    SELECT opaque_revision
                      FROM prv_feature_rollout_decision_revision
                     WHERE feature_flag_id = ?), 0)
                """, Long.class, flagId);
        return revision == null ? 0 : revision;
    }

    public long revision(String flagKey) {
        Long revision = jdbc.queryForObject("""
                SELECT COALESCE(revision.opaque_revision, 0)
                  FROM prv_feature_flags flag
                  LEFT JOIN prv_feature_rollout_decision_revision revision
                    ON revision.feature_flag_id = flag.feature_flag_id
                 WHERE flag.feature_key = ?
                """, Long.class, flagKey);
        return revision == null ? 0 : revision;
    }

    @Transactional
    public List<DecisionEvent> claim(String workerId, int batchSize, Duration lease) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT event_id
                      FROM prv_feature_rollout_decision_outbox
                     WHERE delivery_status IN ('PENDING', 'FAILED')
                       AND next_attempt_at <= CURRENT_TIMESTAMP
                       AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP)
                     ORDER BY created_at, event_id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED)
                UPDATE prv_feature_rollout_decision_outbox event
                   SET delivery_status = 'SENDING',
                       attempt_count = event.attempt_count + 1,
                       locked_by = ?,
                       locked_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond')
                  FROM candidates
                 WHERE event.event_id = candidates.event_id
                RETURNING event.event_id, event.auth_tenant_id, event.tenant_scope,
                          event.flag_key, event.opaque_revision, event.state,
                          event.attempt_count, event.created_at
                """,
                (result, ignored) -> new DecisionEvent(
                        result.getObject("event_id", UUID.class),
                        result.getObject("auth_tenant_id", Long.class),
                        result.getString("tenant_scope"),
                        result.getString("flag_key"),
                        result.getLong("opaque_revision"),
                        result.getString("state"),
                        result.getInt("attempt_count"),
                        result.getObject("created_at", Instant.class)),
                batchSize, workerId, lease.toMillis());
    }

    public void markPublished(List<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                UPDATE prv_feature_rollout_decision_outbox
                   SET delivery_status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, locked_until = NULL, last_error = NULL
                 WHERE event_id = ?
                """, eventIds, eventIds.size(),
                (statement, eventId) -> statement.setObject(1, eventId));
    }

    public void markFailed(
            UUID eventId,
            int attempt,
            int maximumAttempts,
            String error) {
        int retrySeconds = Math.min(300, Math.max(2, 1 << Math.min(attempt, 8)));
        jdbc.update("""
                UPDATE prv_feature_rollout_decision_outbox
                   SET delivery_status = CASE WHEN ? >= ? THEN 'DEAD' ELSE 'FAILED' END,
                       next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                       locked_by = NULL, locked_until = NULL, last_error = ?
                 WHERE event_id = ?
                """, attempt, maximumAttempts, retrySeconds, truncate(error), eventId);
    }

    public int releaseExpired(Instant now) {
        return jdbc.update("""
                UPDATE prv_feature_rollout_decision_outbox
                   SET delivery_status = 'FAILED', locked_by = NULL, locked_until = NULL,
                       next_attempt_at = ?, last_error = 'Publisher lease expired'
                 WHERE delivery_status = 'SENDING' AND locked_until < ?
                """, now, now);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "Unknown publication failure";
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    public record DecisionEvent(
            UUID eventId,
            Long authTenantId,
            String tenantScope,
            String flagKey,
            long opaqueRevision,
            String state,
            int attempt,
            Instant createdAt) {
    }
}
