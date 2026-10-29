package com.dwp.services.notification.operations;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Truthful activation gate for capabilities that have no production adapter yet. */
@Component
public final class NotificationExternalCapabilityReadiness implements HealthIndicator {

    @Override
    public Health health() {
        return Health.down()
                .withDetail("state", "BLOCKED_EXTERNAL")
                .withDetail("peopleAudienceSnapshot", "NOT_IMPLEMENTED")
                .withDetail("providers", Map.of(
                        "email", "DISABLED",
                        "webPush", "DISABLED",
                        "mobilePush", "DISABLED",
                        "teams", "DISABLED",
                        "slack", "DISABLED"))
                .withDetail(
                        "reason",
                        "People snapshot and external provider adapters are not production-ready")
                .build();
    }
}
