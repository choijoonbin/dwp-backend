package com.dwp.services.notification.domain;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationDeliveryAdmissionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationDeliveryAdmissionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AdmissionClaim claim(
            long tenantId,
            UUID sourceEventId,
            UUID typeVersionId,
            long userId,
            String channel) {
        UUID receiptId = UUID.randomUUID();
        MapSqlParameterSource params = identity(
                tenantId, sourceEventId, typeVersionId, userId, channel)
                .addValue("receiptId", receiptId);
        List<UUID> inserted = jdbc.query("""
                INSERT INTO ntf_delivery_admission_receipts (
                    receipt_id, tenant_id, source_event_id, type_version_id,
                    user_id, channel, decision)
                VALUES (
                    :receiptId, :tenantId, :sourceEventId, :typeVersionId,
                    :userId, :channel, 'PENDING')
                ON CONFLICT (
                    tenant_id, source_event_id, type_version_id, user_id, channel)
                DO NOTHING
                RETURNING receipt_id
                """, params, (resultSet, rowNumber) ->
                resultSet.getObject("receipt_id", UUID.class));
        if (!inserted.isEmpty()) return new AdmissionClaim(receiptId, "PENDING", true);
        return jdbc.queryForObject("""
                SELECT receipt_id, decision
                  FROM ntf_delivery_admission_receipts
                 WHERE tenant_id = :tenantId
                   AND source_event_id = :sourceEventId
                   AND type_version_id = :typeVersionId
                   AND user_id = :userId
                   AND channel = :channel
                """, params, (resultSet, rowNumber) -> new AdmissionClaim(
                resultSet.getObject("receipt_id", UUID.class),
                resultSet.getString("decision"),
                false));
    }

    public SuppressionMatch matchingSuppression(
            long tenantId,
            String appKey,
            String typeKey,
            String channel,
            Instant now) {
        List<SuppressionMatch> matches = jdbc.query("""
                SELECT suppression_id, critical_bypass, scope_type, scope_key
                  FROM ntf_delivery_suppressions
                 WHERE tenant_id = :tenantId
                   AND revoked_at IS NULL
                   AND starts_at <= :now
                   AND expires_at > :now
                   AND channel IN ('ALL', :channel)
                   AND (scope_type = 'TENANT'
                     OR (scope_type = 'APP' AND scope_key = :appKey)
                     OR (scope_type = 'TYPE' AND scope_key = :typeKey))
                 ORDER BY critical_bypass ASC,
                          CASE scope_type
                              WHEN 'TYPE' THEN 3
                              WHEN 'APP' THEN 2
                              ELSE 1
                          END DESC,
                          starts_at DESC,
                          suppression_id
                 LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("appKey", appKey)
                .addValue("typeKey", typeKey)
                .addValue("channel", channel)
                .addValue("now", Timestamp.from(now)),
                (resultSet, rowNumber) -> new SuppressionMatch(
                        resultSet.getObject("suppression_id", UUID.class),
                        resultSet.getBoolean("critical_bypass"),
                        resultSet.getString("scope_type"),
                        resultSet.getString("scope_key")));
        return matches.isEmpty() ? null : matches.get(0);
    }

    public Integer maximumPerWindow(
            long tenantId,
            String appKey,
            String typeKey,
            String channel) {
        List<Integer> limits = jdbc.query("""
                SELECT channel_rule.max_per_window
                  FROM ntf_routing_policies policy
                  JOIN ntf_policy_channel_rules channel_rule
                    ON channel_rule.policy_id = policy.policy_id
                   AND channel_rule.channel = :channel
                 WHERE policy.state = 'PUBLISHED'
                   AND channel_rule.enabled
                   AND (policy.tenant_id IS NULL OR policy.tenant_id = :tenantId)
                   AND (policy.effective_from IS NULL
                        OR policy.effective_from <= CURRENT_TIMESTAMP)
                   AND (policy.effective_to IS NULL
                        OR policy.effective_to > CURRENT_TIMESTAMP)
                   AND (policy.scope_type = 'PROVIDER'
                     OR policy.scope_type = 'TENANT'
                     OR (policy.scope_type = 'APP' AND policy.scope_key = :appKey)
                     OR (policy.scope_type = 'TYPE' AND policy.scope_key = :typeKey))
                 ORDER BY CASE policy.scope_type
                              WHEN 'TYPE' THEN 4
                              WHEN 'APP' THEN 3
                              WHEN 'TENANT' THEN 2
                              ELSE 1
                          END DESC,
                          (policy.tenant_id IS NOT NULL) DESC,
                          policy.version DESC
                 LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("appKey", appKey)
                .addValue("typeKey", typeKey)
                .addValue("channel", channel),
                (resultSet, rowNumber) ->
                        resultSet.getObject("max_per_window", Integer.class));
        return limits.isEmpty() ? null : limits.get(0);
    }

    public boolean incrementWindow(
            long tenantId,
            long userId,
            UUID typeVersionId,
            String channel,
            Instant windowStartedAt,
            int windowSeconds,
            int maximum) {
        List<Integer> counts = jdbc.query("""
                INSERT INTO ntf_delivery_rate_windows (
                    tenant_id, user_id, type_version_id, channel,
                    window_started_at, window_seconds, delivery_count)
                VALUES (
                    :tenantId, :userId, :typeVersionId, :channel,
                    :windowStartedAt, :windowSeconds, 1)
                ON CONFLICT (
                    tenant_id, user_id, type_version_id, channel,
                    window_started_at, window_seconds)
                DO UPDATE SET
                    delivery_count = ntf_delivery_rate_windows.delivery_count + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE ntf_delivery_rate_windows.delivery_count < :maximum
                RETURNING delivery_count
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userId", userId)
                .addValue("typeVersionId", typeVersionId)
                .addValue("channel", channel)
                .addValue("windowStartedAt", Timestamp.from(windowStartedAt))
                .addValue("windowSeconds", windowSeconds)
                .addValue("maximum", maximum),
                (resultSet, rowNumber) -> resultSet.getInt("delivery_count"));
        return !counts.isEmpty();
    }

    public void complete(
            long tenantId,
            UUID receiptId,
            String decision,
            String reasonCode,
            UUID suppressionId,
            Instant windowStartedAt) {
        int updated = jdbc.update("""
                UPDATE ntf_delivery_admission_receipts
                   SET decision = :decision,
                       reason_code = :reasonCode,
                       suppression_id = :suppressionId,
                       window_started_at = :windowStartedAt,
                       decided_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND receipt_id = :receiptId
                   AND decision = 'PENDING'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId)
                .addValue("decision", decision)
                .addValue("reasonCode", reasonCode)
                .addValue("suppressionId", suppressionId)
                .addValue("windowStartedAt", windowStartedAt == null
                        ? null : Timestamp.from(windowStartedAt)));
        if (updated != 1) {
            throw new IllegalStateException("Notification admission receipt is not pending.");
        }
    }

    private MapSqlParameterSource identity(
            long tenantId,
            UUID sourceEventId,
            UUID typeVersionId,
            long userId,
            String channel) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceEventId", sourceEventId)
                .addValue("typeVersionId", typeVersionId)
                .addValue("userId", userId)
                .addValue("channel", channel);
    }

    public record AdmissionClaim(UUID receiptId, String decision, boolean claimed) {
    }

    public record SuppressionMatch(
            UUID suppressionId,
            boolean criticalBypass,
            String scopeType,
            String scopeKey) {
    }
}
