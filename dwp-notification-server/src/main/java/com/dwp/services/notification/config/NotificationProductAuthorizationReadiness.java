package com.dwp.services.notification.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Prevents configuration strings from being mistaken for an approved v4 bundle. */
@Component
public final class NotificationProductAuthorizationReadiness implements HealthIndicator {

    @Override
    public Health health() {
        return Health.down()
                .withDetail("state", "BLOCKED_EXTERNAL")
                .withDetail(
                        "bundleStatus",
                        NotificationProductionConfigurationGuard
                                .EMBEDDED_PRODUCT_AUTHORIZATION_V4_STATUS)
                .withDetail(
                        "reason",
                        "The repository Product Authorization v4 artifact is not published")
                .build();
    }
}
