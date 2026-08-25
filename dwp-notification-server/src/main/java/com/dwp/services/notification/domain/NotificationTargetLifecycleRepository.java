package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationModels.ChangeSignal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationTargetLifecycleRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationTargetLifecycleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChangeSignal> markUnavailable(
            long tenantId,
            String ownerAppKey,
            String targetReference,
            String state,
            String reason) {
        MapSqlParameterSource scope = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("ownerAppKey", ownerAppKey)
                .addValue("targetReference", targetReference);
        List<TargetProjection> projections = jdbc.query("""
                SELECT user_notification.user_id,
                       user_notification.notification_id,
                       user_notification.target_state,
                       user_notification.target_state_reason
                  FROM ntf_user_notifications user_notification
                  JOIN ntf_notifications notification
                    ON notification.tenant_id = user_notification.tenant_id
                   AND notification.notification_id = user_notification.notification_id
                  JOIN ntf_notification_type_versions type_version
                    ON type_version.type_version_id = notification.type_version_id
                  JOIN ntf_notification_types type
                    ON type.type_id = type_version.type_id
                 WHERE user_notification.tenant_id = :tenantId
                   AND type.owner_app_key = :ownerAppKey
                   AND user_notification.target_ref = :targetReference
                 FOR UPDATE OF user_notification
                """, scope, (resultSet, rowNumber) -> new TargetProjection(
                resultSet.getLong("user_id"),
                resultSet.getObject("notification_id", UUID.class),
                resultSet.getString("target_state"),
                resultSet.getString("target_state_reason")));

        List<ChangeSignal> signals = new ArrayList<>();
        for (TargetProjection projection : projections) {
            if (state.equals(projection.state()) && reason.equals(projection.reason())) continue;
            MapSqlParameterSource identity = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("userId", projection.userId())
                    .addValue("notificationId", projection.notificationId())
                    .addValue("state", state)
                    .addValue("reason", reason);
            jdbc.update("""
                    INSERT INTO ntf_user_counters (tenant_id, user_id)
                    VALUES (:tenantId, :userId)
                    ON CONFLICT (tenant_id, user_id) DO NOTHING
                    """, identity);
            long changeVersion = jdbc.queryForObject("""
                    UPDATE ntf_user_counters
                       SET counter_version = counter_version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenantId AND user_id = :userId
                     RETURNING counter_version
                    """, identity, Long.class);
            int changed = jdbc.update("""
                    UPDATE ntf_user_notifications
                       SET target_state = :state,
                           target_state_reason = :reason,
                           change_version = :changeVersion,
                           version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenantId
                       AND user_id = :userId
                       AND notification_id = :notificationId
                    """, identity.addValue("changeVersion", changeVersion));
            if (changed != 1) {
                throw new IllegalStateException(
                        "Notification target lifecycle update lost its recipient projection.");
            }
            signals.add(new ChangeSignal(
                    tenantId,
                    projection.userId(),
                    changeVersion,
                    projection.notificationId()));
        }
        return List.copyOf(signals);
    }

    private record TargetProjection(
            long userId,
            UUID notificationId,
            String state,
            String reason) {
    }
}
