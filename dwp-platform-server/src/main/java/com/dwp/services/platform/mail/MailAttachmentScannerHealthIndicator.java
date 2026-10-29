package com.dwp.services.platform.mail;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("mailAttachmentScanner")
class MailAttachmentScannerHealthIndicator implements HealthIndicator {

    private final MailAttachmentSecurityReadiness readiness;

    MailAttachmentScannerHealthIndicator(MailAttachmentSecurityReadiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public Health health() {
        MailAttachmentSecurityReadiness.Snapshot snapshot = readiness.snapshot();
        Health.Builder builder = !snapshot.enabled()
                ? Health.unknown() : snapshot.ready() ? Health.up() : Health.down();
        builder.withDetail("enabled", snapshot.enabled())
                .withDetail("ready", snapshot.ready())
                .withDetail("owner", snapshot.owner())
                .withDetail("checkedAt", snapshot.checkedAt().toString());
        if (snapshot.errorCode() != null) {
            builder.withDetail("errorCode", snapshot.errorCode());
        }
        return builder.build();
    }
}
