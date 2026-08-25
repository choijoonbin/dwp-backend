package com.dwp.services.notification.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPolicyRuntimeEvaluatorTest {

    @Test
    void managedPolicyWinsOverUserAndProfileOverrides() {
        var managed = NotificationPolicyRuntimeEvaluator.policy(
                true, false, false, false, "IMMEDIATE");
        var userEnabled = NotificationPolicyRuntimeEvaluator.userRule(
                true, "IMMEDIATE", true);

        assertThat(NotificationPolicyRuntimeEvaluator.inAppDeliveryEnabled(
                managed, userEnabled, true)).isFalse();
    }

    @Test
    void userRuleWinsWhenThePolicyAllowsOverrides() {
        var overridable = NotificationPolicyRuntimeEvaluator.policy(
                true, false, true, true, "IMMEDIATE");
        var userMuted = NotificationPolicyRuntimeEvaluator.userRule(
                true, "MUTED", true);

        assertThat(NotificationPolicyRuntimeEvaluator.inAppDeliveryEnabled(
                overridable, userMuted, true)).isFalse();
    }

    @Test
    void defaultPolicyAdmissionMatchesTheAdminSimulationInput() {
        var proposed = NotificationPolicyRuntimeEvaluator.policy(
                true, true, true, false, "IMMEDIATE");

        assertThat(NotificationPolicyRuntimeEvaluator.inAppDeliveryEnabled(
                proposed,
                NotificationPolicyRuntimeEvaluator.userRule(false, null, null),
                null)).isTrue();
    }
}
