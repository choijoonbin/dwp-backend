package com.dwp.services.notification.domain;

import java.util.UUID;

record NotificationAttentionDecision(
        UUID ruleId,
        String scopeKind,
        String effect,
        Long ruleRevision,
        String policySource,
        String reasonCode) {

    private static final NotificationAttentionDecision NONE =
            new NotificationAttentionDecision(null, null, null, null, null, null);

    static NotificationAttentionDecision none() {
        return NONE;
    }

    boolean matched() {
        return ruleId != null;
    }

    boolean suppress() {
        return "MUTE".equals(effect);
    }

    boolean followOverride() {
        return "FOLLOW".equals(effect);
    }

    String effectivePriority(String contractPriority) {
        if (!"PRIORITIZE".equals(effect)
                || "HIGH".equals(contractPriority)
                || "URGENT".equals(contractPriority)) {
            return contractPriority;
        }
        return "HIGH";
    }
}
