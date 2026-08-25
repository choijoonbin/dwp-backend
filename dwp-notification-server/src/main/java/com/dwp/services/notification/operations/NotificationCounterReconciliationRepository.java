package com.dwp.services.notification.operations;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class NotificationCounterReconciliationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationCounterReconciliationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> driftedUserIds(long tenantId, Instant now, int limit) {
        return jdbc.queryForList("""
                WITH expected AS (
                    SELECT user_id,
                           COUNT(*) FILTER (
                               WHERE inbox_state = 'ACTIVE'
                                 AND read_at IS NULL
                                 AND (snoozed_until IS NULL OR snoozed_until <= :now)
                           ) AS unread_count,
                           COUNT(*) FILTER (
                               WHERE inbox_state = 'ACTIVE'
                                 AND read_at IS NULL
                                 AND action_required
                                 AND (snoozed_until IS NULL OR snoozed_until <= :now)
                           ) AS actionable_unread_count,
                           COUNT(*) FILTER (
                               WHERE inbox_state = 'ACTIVE'
                                 AND read_at IS NULL
                                 AND effective_priority = 'URGENT'
                                 AND (snoozed_until IS NULL OR snoozed_until <= :now)
                           ) AS urgent_count
                      FROM ntf_user_notifications
                     WHERE tenant_id = :tenantId
                     GROUP BY user_id
                )
                SELECT COALESCE(expected.user_id, counter.user_id) AS user_id
                  FROM expected
                  FULL OUTER JOIN ntf_user_counters counter
                    ON counter.tenant_id = :tenantId
                   AND counter.user_id = expected.user_id
                 WHERE counter.user_id IS NULL
                    OR expected.user_id IS NULL
                    OR counter.unread_count <> expected.unread_count
                    OR counter.actionable_unread_count <> expected.actionable_unread_count
                    OR counter.urgent_count <> expected.urgent_count
                 ORDER BY user_id
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("now", Timestamp.from(now))
                .addValue("limit", limit), Long.class);
    }

    public List<ProjectionCounterRow> lockProjectionRows(long tenantId, long userId) {
        return jdbc.query("""
                SELECT inbox_state, read_at, snoozed_until, action_required,
                       effective_priority, change_version
                  FROM ntf_user_notifications
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                 ORDER BY notification_id
                 FOR UPDATE
                """, identity(tenantId, userId), (resultSet, rowNumber) ->
                new ProjectionCounterRow(
                        resultSet.getString("inbox_state"),
                        instant(resultSet.getTimestamp("read_at")),
                        instant(resultSet.getTimestamp("snoozed_until")),
                        resultSet.getBoolean("action_required"),
                        resultSet.getString("effective_priority"),
                        resultSet.getLong("change_version")));
    }

    public void ensureCounter(long tenantId, long userId) {
        jdbc.update("""
                INSERT INTO ntf_user_counters (tenant_id, user_id)
                VALUES (:tenantId, :userId)
                ON CONFLICT (tenant_id, user_id) DO NOTHING
                """, identity(tenantId, userId));
    }

    public CounterRow lockCounter(long tenantId, long userId) {
        return jdbc.queryForObject("""
                SELECT unread_count, actionable_unread_count, urgent_count, counter_version
                  FROM ntf_user_counters
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                 FOR UPDATE
                """, identity(tenantId, userId), (resultSet, rowNumber) -> new CounterRow(
                resultSet.getLong("unread_count"),
                resultSet.getLong("actionable_unread_count"),
                resultSet.getLong("urgent_count"),
                resultSet.getLong("counter_version")));
    }

    public void repair(
            long tenantId,
            long userId,
            CounterRow expected,
            long maximumProjectionVersion) {
        int updated = jdbc.update("""
                UPDATE ntf_user_counters
                   SET unread_count = :unread,
                       actionable_unread_count = :actionable,
                       urgent_count = :urgent,
                       counter_version = GREATEST(counter_version, :projectionVersion),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                """, identity(tenantId, userId)
                .addValue("unread", expected.unread())
                .addValue("actionable", expected.actionable())
                .addValue("urgent", expected.urgent())
                .addValue("projectionVersion", maximumProjectionVersion));
        if (updated != 1) {
            throw new IllegalStateException("Notification counter reconciliation update failed.");
        }
    }

    private MapSqlParameterSource identity(long tenantId, long userId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userId", userId);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record ProjectionCounterRow(
            String inboxState,
            Instant readAt,
            Instant snoozedUntil,
            boolean actionRequired,
            String priority,
            long changeVersion) {
    }

    public record CounterRow(long unread, long actionable, long urgent, long version) {

        public boolean sameCounts(CounterRow other) {
            return other != null
                    && unread == other.unread
                    && actionable == other.actionable
                    && urgent == other.urgent;
        }
    }
}
