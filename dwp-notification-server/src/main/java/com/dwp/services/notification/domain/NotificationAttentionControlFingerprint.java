package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationAttentionGovernanceRuntime.EffectiveGovernance;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionContextReference;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionPolicyDecision;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRule;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicy;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicyChannel;
import com.dwp.services.notification.security.NotificationRequestContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

final class NotificationAttentionControlFingerprint {

    private NotificationAttentionControlFingerprint() {
    }

    static String issue(
            NotificationRequestContext.Actor actor,
            java.util.UUID notificationId,
            String appKey,
            String typeKey,
            String controlKey,
            String effect,
            Instant expiresAt,
            AttentionContextReference reference,
            AttentionRule currentRule,
            EffectiveGovernance governance,
            EffectivePolicy policy,
            AttentionPolicyDecision decision) {
        StringBuilder value = new StringBuilder("attention-control-preview:v1|");
        add(value, actor.tenantId());
        add(value, actor.userId());
        add(value, notificationId);
        add(value, appKey);
        add(value, typeKey);
        add(value, controlKey);
        add(value, effect);
        add(value, expiresAt);
        addReference(value, reference);
        addRule(value, currentRule);
        addGovernance(value, governance);
        addPolicy(value, policy);
        add(value, decision.locked());
        add(value, decision.policySource());
        add(value, decision.reasonCode());
        add(value, decision.policyRevision());
        return sha256(value.toString());
    }

    static boolean matches(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private static void addReference(
            StringBuilder value,
            AttentionContextReference reference) {
        if (reference == null) {
            add(value, null);
            return;
        }
        add(value, reference.scopeKind());
        add(value, reference.scopeKey());
    }

    private static void addRule(StringBuilder value, AttentionRule rule) {
        if (rule == null) {
            add(value, null);
            return;
        }
        add(value, rule.ruleId());
        add(value, rule.version());
        add(value, rule.effect());
        add(value, rule.enabled());
        add(value, rule.expiresAt());
        add(value, rule.source());
        add(value, rule.managed());
    }

    private static void addGovernance(
            StringBuilder value,
            EffectiveGovernance governance) {
        add(value, governance.published());
        add(value, governance.governanceId());
        add(value, governance.revisionNumber());
        add(value, governance.settings().maxActiveUserRules());
        add(value, governance.settings().maxVipRules());
        add(value, governance.settings().maxFollowRules());
        governance.settings().approvedTopicAllowlist().stream().sorted()
                .forEach(topic -> add(value, topic));
        add(value, governance.settings().mandatoryPolicyPrecedence());
    }

    private static void addPolicy(StringBuilder value, EffectivePolicy policy) {
        if (policy == null) {
            add(value, null);
            return;
        }
        add(value, policy.policyId());
        add(value, policy.tenantId());
        add(value, policy.scopeType());
        add(value, policy.scopeKey());
        add(value, policy.version());
        add(value, policy.mandatory());
        add(value, policy.quietHoursBypass());
        add(value, policy.digestMode());
        for (Map.Entry<String, EffectivePolicyChannel> entry
                : new TreeMap<>(policy.channels()).entrySet()) {
            EffectivePolicyChannel channel = entry.getValue();
            add(value, entry.getKey());
            add(value, channel.enabled());
            add(value, channel.defaultMode());
            add(value, channel.userOverridable());
            add(value, channel.maxPerWindow());
        }
    }

    private static void add(StringBuilder target, Object value) {
        String text = value == null ? "<null>" : value.toString();
        target.append(text.length()).append(':').append(text).append('|');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
