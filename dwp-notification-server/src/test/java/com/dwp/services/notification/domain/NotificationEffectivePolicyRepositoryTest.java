package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicy;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository.EffectivePolicyChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEffectivePolicyRepositoryTest {

    @Test
    void selectsTypeBeforeAppTenantAndProviderPolicies() {
        EffectivePolicy type = policy(9L, "TYPE", "MESSAGING.DIRECT_MESSAGE", 1);
        EffectivePolicy app = policy(9L, "APP", "messaging", 4);
        EffectivePolicy tenant = policy(9L, "TENANT", "tenant:9", 7);
        EffectivePolicy provider = policy(null, "PROVIDER", "default", 20);

        EffectivePolicy selected = NotificationEffectivePolicyRepository.selectPolicy(
                List.of(provider, tenant, app, type),
                "messaging",
                "MESSAGING.DIRECT_MESSAGE");

        assertThat(selected).isSameAs(type);
    }

    @Test
    void selectsTenantPolicyBeforeProviderAtTheSameScopeRank() {
        EffectivePolicy tenant = policy(9L, "TENANT", "tenant:9", 1);
        EffectivePolicy provider = policy(null, "PROVIDER", "default", 99);

        EffectivePolicy selected = NotificationEffectivePolicyRepository.selectPolicy(
                List.of(provider, tenant), "messaging", "MESSAGING.DIRECT_MESSAGE");

        assertThat(selected).isSameAs(tenant);
    }

    @Test
    void mapsGovernedDigestCadenceToTheUserContract() {
        EffectivePolicy weekly = policy(9L, "APP", "messaging", 1, "WEEKLY");

        assertThat(NotificationEffectivePolicyRepository.policyMode(
                weekly, weekly.channels().get("IN_APP")))
                .isEqualTo("WEEKLY_DIGEST");
    }

    private EffectivePolicy policy(
            Long tenantId,
            String scopeType,
            String scopeKey,
            long version) {
        return policy(tenantId, scopeType, scopeKey, version, "IMMEDIATE");
    }

    private EffectivePolicy policy(
            Long tenantId,
            String scopeType,
            String scopeKey,
            long version,
            String digestMode) {
        return new EffectivePolicy(
                UUID.randomUUID(),
                tenantId,
                scopeType,
                scopeKey,
                version,
                false,
                false,
                digestMode,
                Map.of("IN_APP", new EffectivePolicyChannel(
                        "IN_APP", true, "WEEKLY".equals(digestMode) ? "DIGEST" : "IMMEDIATE",
                        true, 100)));
    }
}
