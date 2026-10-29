package com.dwp.services.messaging.operations;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("messagingScheduledDeliveryHealth")
public final class MessagingScheduledDeliveryHealthIndicator implements HealthIndicator {

    private final MessagingScheduledDeliveryOperations operations;

    public MessagingScheduledDeliveryHealthIndicator(
            MessagingScheduledDeliveryOperations operations) {
        this.operations = operations;
    }

    @Override
    public Health health() {
        MessagingScheduledDeliveryOperations.ScheduledDeliveryStatus status = operations.status();
        Health.Builder health = switch (status.state()) {
            case "READY" -> Health.up();
            case "BLOCKED" -> Health.outOfService();
            default -> Health.unknown();
        };
        return health
                .withDetail("state", status.state())
                .withDetail("reasonCode", status.reasonCode() == null ? "NONE" : status.reasonCode())
                .withDetail("lastCompletedAt", status.lastCompletedAt() == null
                        ? "NEVER" : status.lastCompletedAt())
                .withDetail("pendingCount", status.pendingCount())
                .withDetail("overdueCount", status.overdueCount())
                .withDetail("recentFailedCount", status.recentFailedCount())
                .build();
    }
}
