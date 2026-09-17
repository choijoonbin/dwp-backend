package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Settings;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceRuntime.EffectiveGovernance;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionContextReference;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionPolicyDecision;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAttentionControlFingerprintTest {

    private static final UUID NOTIFICATION_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final EffectiveGovernance GOVERNANCE = new EffectiveGovernance(
            new Settings(75, 5, 8, List.of("#security-alert"), true, 25, true),
            true,
            UUID.fromString("30000000-0000-0000-0000-000000000001"),
            3L);

    @Test
    void fingerprintIsStableButBoundToTenantUserAndExactPreviewInput() {
        NotificationRequestContext.Actor owner = actor(42L, 17L);
        String fingerprint = issue(owner, null);

        assertThat(fingerprint).matches("[a-f0-9]{64}");
        assertThat(issue(owner, null)).isEqualTo(fingerprint);
        assertThat(issue(actor(42L, 18L), null)).isNotEqualTo(fingerprint);
        assertThat(issue(actor(43L, 17L), null)).isNotEqualTo(fingerprint);
        assertThat(issue(owner, Instant.parse("2026-09-18T00:00:00Z")))
                .isNotEqualTo(fingerprint);
        assertThat(NotificationAttentionControlFingerprint.matches(
                fingerprint, fingerprint)).isTrue();
        assertThat(NotificationAttentionControlFingerprint.matches(
                fingerprint, "0".repeat(64))).isFalse();
    }

    private String issue(
            NotificationRequestContext.Actor actor,
            Instant expiresAt) {
        return NotificationAttentionControlFingerprint.issue(
                actor,
                NOTIFICATION_ID,
                "approvals",
                "APPROVAL.ACTION_REQUIRED",
                "FOLLOW_CONTEXT",
                "FOLLOW",
                expiresAt,
                new AttentionContextReference(
                        "THREAD", "thread:approval-42", "Approval thread"),
                null,
                GOVERNANCE,
                null,
                AttentionPolicyDecision.allowed());
    }

    private NotificationRequestContext.Actor actor(long tenantId, long userId) {
        return new NotificationRequestContext.Actor(
                tenantId, userId, Set.of(), Set.of(), false, "dwp-gateway");
    }
}
