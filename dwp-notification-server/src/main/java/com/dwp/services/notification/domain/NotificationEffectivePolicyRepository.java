package com.dwp.services.notification.domain;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class NotificationEffectivePolicyRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationEffectivePolicyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EffectivePolicy> findForTenant(long tenantId) {
        return jdbc.query("""
                WITH ranked_policy AS (
                    SELECT policy.*,
                           ROW_NUMBER() OVER (
                               PARTITION BY policy.scope_type, policy.scope_key
                               ORDER BY (policy.tenant_id IS NOT NULL) DESC,
                                        policy.version DESC,
                                        policy.created_at DESC
                           ) AS policy_rank
                      FROM ntf_routing_policies policy
                     WHERE policy.state = 'PUBLISHED'
                       AND (policy.tenant_id IS NULL OR policy.tenant_id = :tenantId)
                       AND (policy.effective_from IS NULL
                            OR policy.effective_from <= CURRENT_TIMESTAMP)
                       AND (policy.effective_to IS NULL
                            OR policy.effective_to > CURRENT_TIMESTAMP)
                )
                SELECT policy.policy_id, policy.tenant_id, policy.scope_type,
                       policy.scope_key, policy.version, policy.mandatory,
                       policy.quiet_hours_bypass, policy.digest_mode,
                       channel.channel, channel.enabled, channel.default_mode,
                       channel.user_overridable, channel.max_per_window
                  FROM ranked_policy policy
                  LEFT JOIN ntf_policy_channel_rules channel
                    ON channel.policy_id = policy.policy_id
                 WHERE policy.policy_rank = 1
                 ORDER BY CASE policy.scope_type
                              WHEN 'TYPE' THEN 4
                              WHEN 'APP' THEN 3
                              WHEN 'TENANT' THEN 2
                              ELSE 1
                          END DESC,
                          (policy.tenant_id IS NOT NULL) DESC,
                          policy.version DESC,
                          policy.policy_id,
                          channel.channel
                """, new MapSqlParameterSource("tenantId", tenantId), resultSet -> {
            Map<UUID, EffectivePolicyAccumulator> rows = new LinkedHashMap<>();
            while (resultSet.next()) {
                UUID policyId = resultSet.getObject("policy_id", UUID.class);
                EffectivePolicyAccumulator row = rows.computeIfAbsent(
                        policyId,
                        ignored -> accumulator(resultSet, policyId));
                String channel = resultSet.getString("channel");
                if (channel != null) {
                    row.channels.put(channel, new EffectivePolicyChannel(
                            channel,
                            resultSet.getBoolean("enabled"),
                            resultSet.getString("default_mode"),
                            resultSet.getBoolean("user_overridable"),
                            resultSet.getObject("max_per_window", Integer.class)));
                }
            }
            List<EffectivePolicy> result = new ArrayList<>(rows.size());
            rows.values().forEach(row -> result.add(row.toPolicy()));
            return List.copyOf(result);
        });
    }

    static EffectivePolicy selectPolicy(
            List<EffectivePolicy> policies,
            String appKey,
            String typeKey) {
        return policies.stream()
                .filter(policy -> switch (policy.scopeType()) {
                    case "TYPE" -> policy.scopeKey().equals(typeKey);
                    case "APP" -> policy.scopeKey().equals(appKey);
                    case "TENANT", "PROVIDER" -> true;
                    default -> false;
                })
                .max(Comparator
                        .comparingInt((EffectivePolicy policy) -> scopeRank(policy.scopeType()))
                        .thenComparingInt(policy -> policy.tenantId() == null ? 0 : 1)
                        .thenComparingLong(EffectivePolicy::version))
                .orElse(null);
    }

    static String policyMode(EffectivePolicy policy, EffectivePolicyChannel channel) {
        if (!"DIGEST".equals(channel.defaultMode())) return channel.defaultMode();
        return switch (policy.digestMode()) {
            case "WEEKLY" -> "WEEKLY_DIGEST";
            default -> "DAILY_DIGEST";
        };
    }

    private static int scopeRank(String scopeType) {
        return switch (scopeType) {
            case "TYPE" -> 4;
            case "APP" -> 3;
            case "TENANT" -> 2;
            case "PROVIDER" -> 1;
            default -> 0;
        };
    }

    private static EffectivePolicyAccumulator accumulator(ResultSet resultSet, UUID policyId) {
        try {
            return new EffectivePolicyAccumulator(
                    policyId,
                    resultSet.getObject("tenant_id", Long.class),
                    resultSet.getString("scope_type"),
                    resultSet.getString("scope_key"),
                    resultSet.getLong("version"),
                    resultSet.getBoolean("mandatory"),
                    resultSet.getBoolean("quiet_hours_bypass"),
                    resultSet.getString("digest_mode"));
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read notification policy.", exception);
        }
    }

    public record EffectivePolicy(
            UUID policyId,
            Long tenantId,
            String scopeType,
            String scopeKey,
            long version,
            boolean mandatory,
            boolean quietHoursBypass,
            String digestMode,
            Map<String, EffectivePolicyChannel> channels) {

        public String source() {
            return tenantId == null ? "PROVIDER_POLICY" : "TENANT_POLICY";
        }
    }

    public record EffectivePolicyChannel(
            String channel,
            boolean enabled,
            String defaultMode,
            boolean userOverridable,
            Integer maxPerWindow) {
    }

    private static final class EffectivePolicyAccumulator {
        private final UUID policyId;
        private final Long tenantId;
        private final String scopeType;
        private final String scopeKey;
        private final long version;
        private final boolean mandatory;
        private final boolean quietHoursBypass;
        private final String digestMode;
        private final Map<String, EffectivePolicyChannel> channels = new LinkedHashMap<>();

        private EffectivePolicyAccumulator(
                UUID policyId,
                Long tenantId,
                String scopeType,
                String scopeKey,
                long version,
                boolean mandatory,
                boolean quietHoursBypass,
                String digestMode) {
            this.policyId = policyId;
            this.tenantId = tenantId;
            this.scopeType = scopeType;
            this.scopeKey = scopeKey;
            this.version = version;
            this.mandatory = mandatory;
            this.quietHoursBypass = quietHoursBypass;
            this.digestMode = digestMode;
        }

        private EffectivePolicy toPolicy() {
            return new EffectivePolicy(
                    policyId,
                    tenantId,
                    scopeType,
                    scopeKey,
                    version,
                    mandatory,
                    quietHoursBypass,
                    digestMode,
                    Map.copyOf(channels));
        }
    }
}
