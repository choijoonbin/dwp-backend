package com.dwp.services.messaging.operations;

import com.dwp.services.messaging.domain.MessagingDtos;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("messagingProviderReadinessHealth")
public final class MessagingProviderReadinessHealthIndicator implements HealthIndicator {

    private final MessagingOperationalReadiness readiness;

    public MessagingProviderReadinessHealthIndicator(MessagingOperationalReadiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public Health health() {
        MessagingDtos.RuntimeReadiness snapshot = readiness.providerSnapshot();
        Health.Builder health = switch (snapshot.overallState()) {
            case "READY" -> Health.up();
            case "BLOCKED" -> Health.outOfService();
            default -> Health.unknown();
        };
        return health
                .withDetail("overallState", snapshot.overallState())
                .withDetail("evaluatedAt", snapshot.evaluatedAt())
                .withDetail("capabilities", snapshot.capabilities())
                .build();
    }
}
