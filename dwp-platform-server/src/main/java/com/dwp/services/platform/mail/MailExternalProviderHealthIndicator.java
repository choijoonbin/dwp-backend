package com.dwp.services.platform.mail;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("mailExternalProvider")
class MailExternalProviderHealthIndicator implements HealthIndicator {

    private final MailExternalProviderReadiness readiness;

    MailExternalProviderHealthIndicator(MailExternalProviderReadiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public Health health() {
        MailExternalProviderReadiness.Snapshot snapshot = readiness.snapshot();
        Health.Builder builder = switch (snapshot.state()) {
            case DISABLED -> Health.unknown();
            case READY -> Health.up();
            case BLOCKED_EXTERNAL -> Health.down();
        };
        builder.withDetail("state", snapshot.state().name())
                .withDetail("checkedAt", snapshot.checkedAt().toString())
                .withDetail("enabledProviders", snapshot.enabledProviders())
                .withDetail("subscriptionsEnabled", snapshot.subscriptionsEnabled());
        if (!snapshot.errorCodes().isEmpty()) {
            builder.withDetail("errorCodes", snapshot.errorCodes());
        }
        return builder.build();
    }
}
