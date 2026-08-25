package com.dwp.services.notification.domain;

final class NotificationPolicyRuntimeEvaluator {

    private NotificationPolicyRuntimeEvaluator() {
    }

    static boolean inAppDeliveryEnabled(
            PolicyInput policy,
            UserRuleInput userRule,
            Boolean profileEnabled) {
        if (policy.present() && (policy.mandatory() || !policy.userOverridable())) {
            return policy.enabled() && !"MUTED".equals(policy.defaultMode());
        }
        if (userRule.present()) {
            return !"MUTED".equals(defaultString(userRule.mode(), "IMMEDIATE"))
                    && firstNonNull(
                    userRule.channelEnabled(),
                    profileEnabled,
                    policy.present() ? policy.enabled() : null,
                    true);
        }
        return firstNonNull(
                profileEnabled,
                policy.present() ? policy.enabled() : null,
                true);
    }

    static PolicyInput policy(
            boolean present,
            Boolean mandatory,
            Boolean enabled,
            Boolean userOverridable,
            String defaultMode) {
        return new PolicyInput(
                present,
                Boolean.TRUE.equals(mandatory),
                enabled == null || enabled,
                userOverridable == null || userOverridable,
                defaultString(defaultMode, "IMMEDIATE"));
    }

    static UserRuleInput userRule(
            boolean present,
            String mode,
            Boolean channelEnabled) {
        return new UserRuleInput(present, mode, channelEnabled);
    }

    private static boolean firstNonNull(Boolean... values) {
        for (Boolean value : values) {
            if (value != null) return value;
        }
        return true;
    }

    private static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }

    record PolicyInput(
            boolean present,
            boolean mandatory,
            boolean enabled,
            boolean userOverridable,
            String defaultMode) {
    }

    record UserRuleInput(boolean present, String mode, Boolean channelEnabled) {
    }
}
