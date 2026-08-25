package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationAppSummaryModels.AppNotificationCounter;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class NotificationAppSummaryRepository {

    static final String UNREAD_BY_APP_SQL = """
            SELECT type.owner_app_key AS app_key,
                   COUNT(*) AS total_unread,
                   COUNT(*) FILTER (
                       WHERE user_notification.action_required
                   ) AS actionable_unread,
                   COUNT(*) FILTER (
                       WHERE user_notification.effective_priority = 'URGENT'
                   ) AS urgent_unread,
                   MAX(user_notification.last_activity_at) AS last_activity_at
              FROM ntf_user_notifications user_notification
              JOIN ntf_notifications notification
                ON notification.tenant_id = user_notification.tenant_id
               AND notification.notification_id = user_notification.notification_id
              JOIN ntf_notification_type_versions type_version
                ON type_version.type_version_id = notification.type_version_id
              JOIN ntf_notification_types type
                ON type.type_id = type_version.type_id
             WHERE user_notification.tenant_id = :tenantId
               AND user_notification.user_id = :userId
               AND user_notification.inbox_state = 'ACTIVE'
               AND user_notification.read_at IS NULL
               AND (
                   user_notification.snoozed_until IS NULL
                   OR user_notification.snoozed_until <= CURRENT_TIMESTAMP
               )
               AND type.owner_app_key ~ '^[a-z0-9][a-z0-9-]{0,63}$'
             GROUP BY type.owner_app_key
             ORDER BY type.owner_app_key
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationAppSummaryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AppNotificationCounter> unreadByApp(
            NotificationRequestContext.Actor actor,
            int fetchLimit) {
        return jdbc.query(
                UNREAD_BY_APP_SQL,
                actorParams(actor).addValue("limit", fetchLimit),
                (resultSet, rowNumber) -> new AppNotificationCounter(
                        resultSet.getString("app_key"),
                        resultSet.getLong("total_unread"),
                        resultSet.getLong("actionable_unread"),
                        resultSet.getLong("urgent_unread"),
                        instant(resultSet.getTimestamp("last_activity_at"))));
    }

    public Optional<AppSummaryMetadata> metadata(NotificationRequestContext.Actor actor) {
        List<AppSummaryMetadata> rows = jdbc.query("""
                SELECT counter_version, updated_at
                  FROM ntf_user_counters
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                """, actorParams(actor), (resultSet, rowNumber) -> new AppSummaryMetadata(
                resultSet.getLong("counter_version"),
                instant(resultSet.getTimestamp("updated_at"))));
        return rows.stream().findFirst();
    }

    private MapSqlParameterSource actorParams(NotificationRequestContext.Actor actor) {
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record AppSummaryMetadata(long version, Instant updatedAt) {
    }
}
