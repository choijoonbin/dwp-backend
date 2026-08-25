package com.dwp.services.notification.domain;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRuntimeAdmissionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationRuntimeAdmissionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean inAppDeliveryEnabled(
            long tenantId,
            long userId,
            String appKey,
            String typeKey) {
        PolicyAdmissionRow row = jdbc.queryForObject("""
                WITH effective_policy AS (
                    SELECT policy.mandatory,
                           channel.enabled,
                           channel.user_overridable,
                           channel.default_mode
                      FROM ntf_routing_policies policy
                      JOIN ntf_policy_channel_rules channel
                        ON channel.policy_id = policy.policy_id
                       AND channel.channel = 'IN_APP'
                     WHERE policy.state = 'PUBLISHED'
                       AND (policy.tenant_id IS NULL OR policy.tenant_id = :tenantId)
                       AND (policy.effective_from IS NULL
                            OR policy.effective_from <= CURRENT_TIMESTAMP)
                       AND (policy.effective_to IS NULL
                            OR policy.effective_to > CURRENT_TIMESTAMP)
                       AND (
                            (policy.scope_type = 'TYPE' AND policy.scope_key = :typeKey)
                         OR (policy.scope_type = 'APP' AND policy.scope_key = :appKey)
                         OR policy.scope_type IN ('TENANT', 'PROVIDER')
                       )
                     ORDER BY CASE policy.scope_type
                                  WHEN 'TYPE' THEN 4
                                  WHEN 'APP' THEN 3
                                  WHEN 'TENANT' THEN 2
                                  ELSE 1
                              END DESC,
                              (policy.tenant_id IS NOT NULL) DESC,
                              policy.version DESC
                     LIMIT 1
                ), user_rule AS (
                    SELECT rule.delivery_mode,
                           channel.enabled AS channel_enabled
                      FROM ntf_user_subscription_rules rule
                      LEFT JOIN ntf_user_subscription_rule_channels channel
                        ON channel.tenant_id = rule.tenant_id
                       AND channel.user_id = rule.user_id
                       AND channel.rule_id = rule.rule_id
                       AND channel.channel = 'IN_APP'
                     WHERE rule.tenant_id = :tenantId
                       AND rule.user_id = :userId
                       AND rule.app_key = :appKey
                       AND rule.type_key = :typeKey
                     LIMIT 1
                ), user_profile AS (
                    SELECT jsonb_exists(default_channels, 'IN_APP') AS channel_enabled
                      FROM ntf_user_delivery_profiles
                     WHERE tenant_id = :tenantId AND user_id = :userId
                )
                SELECT EXISTS (SELECT 1 FROM effective_policy) AS policy_present,
                       (SELECT mandatory FROM effective_policy) AS policy_mandatory,
                       (SELECT enabled FROM effective_policy) AS policy_enabled,
                       (SELECT user_overridable FROM effective_policy)
                           AS policy_user_overridable,
                       (SELECT default_mode FROM effective_policy) AS policy_default_mode,
                       EXISTS (SELECT 1 FROM user_rule) AS user_rule_present,
                       (SELECT delivery_mode FROM user_rule) AS user_rule_mode,
                       (SELECT channel_enabled FROM user_rule) AS user_rule_channel_enabled,
                       (SELECT channel_enabled FROM user_profile) AS profile_channel_enabled
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userId", userId)
                .addValue("appKey", appKey)
                .addValue("typeKey", typeKey),
                (resultSet, rowNumber) -> new PolicyAdmissionRow(
                        resultSet.getBoolean("policy_present"),
                        resultSet.getObject("policy_mandatory", Boolean.class),
                        resultSet.getObject("policy_enabled", Boolean.class),
                        resultSet.getObject("policy_user_overridable", Boolean.class),
                        resultSet.getString("policy_default_mode"),
                        resultSet.getBoolean("user_rule_present"),
                        resultSet.getString("user_rule_mode"),
                        resultSet.getObject("user_rule_channel_enabled", Boolean.class),
                        resultSet.getObject("profile_channel_enabled", Boolean.class)));
        if (row == null) throw new IllegalStateException("Notification policy evaluation failed.");
        return NotificationPolicyRuntimeEvaluator.inAppDeliveryEnabled(
                NotificationPolicyRuntimeEvaluator.policy(
                        row.policyPresent(),
                        row.policyMandatory(),
                        row.policyEnabled(),
                        row.policyUserOverridable(),
                        row.policyDefaultMode()),
                NotificationPolicyRuntimeEvaluator.userRule(
                        row.userRulePresent(),
                        row.userRuleMode(),
                        row.userRuleChannelEnabled()),
                row.profileChannelEnabled());
    }

    private record PolicyAdmissionRow(
            boolean policyPresent,
            Boolean policyMandatory,
            Boolean policyEnabled,
            Boolean policyUserOverridable,
            String policyDefaultMode,
            boolean userRulePresent,
            String userRuleMode,
            Boolean userRuleChannelEnabled,
            Boolean profileChannelEnabled) {
    }
}
