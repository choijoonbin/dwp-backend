package com.dwp.services.notification.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationProductionConfigurationGuardTest {

    @Test
    void missingEnvironmentFailsClosedInsteadOfSilentlyBecomingLocal() {
        assertThat(NotificationProductionConfigurationGuard.violations(
                new MockEnvironment()))
                .containsExactly("dwp.environment must be explicitly configured");
    }

    @Test
    void localAllowsRolloutAndRetentionDevelopmentDefaults() {
        assertThat(NotificationProductionConfigurationGuard.violations(
                new MockEnvironment().withProperty("dwp.environment", "local")))
                .isEmpty();
    }

    @Test
    void productionRejectsIndefiniteRetentionAndUnapprovedV4Rollout() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dwp.environment", "production")
                .withProperty("dwp.notification.retention.default-ttl", "P0D")
                .withProperty("dwp.notification.product-authorization-v4-enabled", "false")
                .withProperty(
                        "dwp.notification.product-authorization-v4-bundle-status", "DRAFT")
                .withProperty(
                        "dwp.notification.product-authorization-v4-rollout-revision", "");

        assertThat(NotificationProductionConfigurationGuard.violations(environment))
                .hasSize(5)
                .anyMatch(value -> value.contains("retention TTL"))
                .anyMatch(value -> value.contains("must be enabled"))
                .anyMatch(value -> value.contains("ACTIVE"))
                .anyMatch(value -> value.contains("rollout revision"))
                .anyMatch(value -> value.contains("remains DRAFT"));
    }

    @Test
    void productionRemainsBlockedWhenConfigurationClaimsActiveButArtifactIsDraft() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dwp.environment", "prod")
                .withProperty("dwp.notification.retention.default-ttl", "P90D")
                .withProperty("dwp.notification.product-authorization-v4-enabled", "true")
                .withProperty(
                        "dwp.notification.product-authorization-v4-bundle-status", "ACTIVE")
                .withProperty(
                        "dwp.notification.product-authorization-v4-rollout-revision",
                        "rollout-" + "a".repeat(64));

        assertThat(NotificationProductionConfigurationGuard.violations(environment))
                .containsExactly(
                        "embedded product authorization v4 bundle remains DRAFT; "
                                + "production is blocked");
    }
}
