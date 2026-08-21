package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationSuppressionModels.Suppression;
import com.dwp.services.notification.domain.NotificationSuppressionModels.SuppressionCommand;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationSuppressionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationSuppressionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Suppression> list(long tenantId) {
        return jdbc.query("""
                SELECT suppression_id, scope_type, scope_key, channel,
                       starts_at, expires_at, critical_bypass, reason, created_by,
                       revoked_at, revoked_by, revoke_reason, version,
                       created_at, updated_at
                  FROM ntf_delivery_suppressions
                 WHERE tenant_id = :tenantId
                   AND (revoked_at IS NULL
                        OR revoked_at >= CURRENT_TIMESTAMP - INTERVAL '30 days')
                   AND (expires_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
                        OR revoked_at IS NOT NULL)
                 ORDER BY (revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP) DESC,
                          starts_at DESC, suppression_id
                 LIMIT 250
                """, tenant(tenantId), this::map);
    }

    public Optional<Suppression> find(long tenantId, UUID suppressionId) {
        return jdbc.query("""
                SELECT suppression_id, scope_type, scope_key, channel,
                       starts_at, expires_at, critical_bypass, reason, created_by,
                       revoked_at, revoked_by, revoke_reason, version,
                       created_at, updated_at
                  FROM ntf_delivery_suppressions
                 WHERE tenant_id = :tenantId AND suppression_id = :suppressionId
                """, tenant(tenantId).addValue("suppressionId", suppressionId), this::map)
                .stream().findFirst();
    }

    public Suppression create(
            long tenantId,
            long actorUserId,
            SuppressionCommand request,
            Instant startsAt) {
        UUID suppressionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ntf_delivery_suppressions (
                    suppression_id, tenant_id, scope_type, scope_key, channel,
                    starts_at, expires_at, critical_bypass, reason, created_by)
                VALUES (
                    :suppressionId, :tenantId, :scopeType, :scopeKey, :channel,
                    :startsAt, :expiresAt, :criticalBypass, :reason, :createdBy)
                """, scope(tenantId, request, startsAt)
                .addValue("suppressionId", suppressionId)
                .addValue("createdBy", actorUserId));
        return find(tenantId, suppressionId).orElseThrow();
    }

    public boolean revoke(
            long tenantId,
            UUID suppressionId,
            long actorUserId,
            long expectedVersion,
            String reason) {
        return jdbc.update("""
                UPDATE ntf_delivery_suppressions
                   SET revoked_at = CURRENT_TIMESTAMP,
                       revoked_by = :revokedBy,
                       revoke_reason = :revokeReason,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND suppression_id = :suppressionId
                   AND version = :expectedVersion
                   AND revoked_at IS NULL
                """, tenant(tenantId)
                .addValue("suppressionId", suppressionId)
                .addValue("revokedBy", actorUserId)
                .addValue("revokeReason", reason)
                .addValue("expectedVersion", expectedVersion)) == 1;
    }

    public boolean scopeExists(long tenantId, String scopeType, String scopeKey) {
        if ("TENANT".equals(scopeType)) return true;
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM ntf_notification_types
                 WHERE lifecycle_state = 'ACTIVE'
                   AND (tenant_id IS NULL OR tenant_id = :tenantId)
                   AND ((:scopeType = 'APP' AND owner_app_key = :scopeKey)
                     OR (:scopeType = 'TYPE' AND type_key = :scopeKey))
                """, tenant(tenantId)
                .addValue("scopeType", scopeType)
                .addValue("scopeKey", scopeKey), Long.class);
        return count != null && count > 0;
    }

    public long affectedTypeCount(long tenantId, String scopeType, String scopeKey) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM ntf_notification_types
                 WHERE lifecycle_state = 'ACTIVE'
                   AND (tenant_id IS NULL OR tenant_id = :tenantId)
                   AND (:scopeType = 'TENANT'
                     OR (:scopeType = 'APP' AND owner_app_key = :scopeKey)
                     OR (:scopeType = 'TYPE' AND type_key = :scopeKey))
                """, tenant(tenantId)
                .addValue("scopeType", scopeType)
                .addValue("scopeKey", scopeKey), Long.class);
        return count == null ? 0 : count;
    }

    public List<String> matchedTypeKeys(
            long tenantId,
            String scopeType,
            String scopeKey) {
        return jdbc.queryForList("""
                SELECT DISTINCT type_key
                  FROM ntf_notification_types
                 WHERE lifecycle_state = 'ACTIVE'
                   AND (tenant_id IS NULL OR tenant_id = :tenantId)
                   AND (:scopeType = 'TENANT'
                     OR (:scopeType = 'APP' AND owner_app_key = :scopeKey)
                     OR (:scopeType = 'TYPE' AND type_key = :scopeKey))
                 ORDER BY type_key
                 LIMIT 25
                """, tenant(tenantId)
                .addValue("scopeType", scopeType)
                .addValue("scopeKey", scopeKey), String.class);
    }

    public long observedNotifications7Days(
            long tenantId,
            String scopeType,
            String scopeKey) {
        return observed(tenantId, scopeType, scopeKey, false);
    }

    public long criticalNotifications7Days(
            long tenantId,
            String scopeType,
            String scopeKey) {
        return observed(tenantId, scopeType, scopeKey, true);
    }

    public long overlappingCount(
            long tenantId,
            SuppressionCommand request,
            Instant startsAt) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM ntf_delivery_suppressions
                 WHERE tenant_id = :tenantId
                   AND scope_type = :scopeType
                   AND scope_key = :scopeKey
                   AND channel = :channel
                   AND revoked_at IS NULL
                   AND starts_at < :expiresAt
                   AND expires_at > :startsAt
                """, scope(tenantId, request, startsAt), Long.class);
        return count == null ? 0 : count;
    }

    private long observed(
            long tenantId,
            String scopeType,
            String scopeKey,
            boolean criticalOnly) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM ntf_user_notifications user_notification
                  JOIN ntf_notifications notification
                    ON notification.tenant_id = user_notification.tenant_id
                   AND notification.notification_id = user_notification.notification_id
                  JOIN ntf_notification_type_versions type_version
                    ON type_version.type_version_id = notification.type_version_id
                  JOIN ntf_notification_types type ON type.type_id = type_version.type_id
                 WHERE user_notification.tenant_id = :tenantId
                   AND user_notification.created_at >= CURRENT_TIMESTAMP - INTERVAL '7 days'
                   AND (:scopeType = 'TENANT'
                     OR (:scopeType = 'APP' AND type.owner_app_key = :scopeKey)
                     OR (:scopeType = 'TYPE' AND type.type_key = :scopeKey))
                   AND (NOT :criticalOnly
                     OR type_version.priority = 'URGENT'
                     OR type_version.urgency = 'CRITICAL')
                """, tenant(tenantId)
                .addValue("scopeType", scopeType)
                .addValue("scopeKey", scopeKey)
                .addValue("criticalOnly", criticalOnly), Long.class);
        return count == null ? 0 : count;
    }

    private MapSqlParameterSource scope(
            long tenantId,
            SuppressionCommand request,
            Instant startsAt) {
        return tenant(tenantId)
                .addValue("scopeType", request.scopeType())
                .addValue("scopeKey", request.scopeKey())
                .addValue("channel", request.channel())
                .addValue("startsAt", Timestamp.from(startsAt))
                .addValue("expiresAt", Timestamp.from(request.expiresAt()))
                .addValue("criticalBypass", request.criticalBypass())
                .addValue("reason", request.reason());
    }

    private MapSqlParameterSource tenant(long tenantId) {
        return new MapSqlParameterSource("tenantId", tenantId);
    }

    private Suppression map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Suppression(
                resultSet.getObject("suppression_id", UUID.class),
                resultSet.getString("scope_type"),
                resultSet.getString("scope_key"),
                resultSet.getString("channel"),
                instant(resultSet, "starts_at"),
                instant(resultSet, "expires_at"),
                resultSet.getBoolean("critical_bypass"),
                resultSet.getString("reason"),
                resultSet.getLong("created_by"),
                instant(resultSet, "revoked_at"),
                resultSet.getObject("revoked_by", Long.class),
                resultSet.getString("revoke_reason"),
                Long.toString(resultSet.getLong("version")),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
