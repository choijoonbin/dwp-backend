package com.dwp.services.messaging.retention;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

@Component("messagingRetentionHealth")
public final class MessagingRetentionHealthIndicator implements HealthIndicator {

    private final MessagingRetentionProperties properties;
    private final MessagingRetentionRepository repository;
    private final Duration evidenceMaximumAge;
    private final Clock clock;

    @Autowired
    public MessagingRetentionHealthIndicator(
            MessagingRetentionProperties properties,
            MessagingRetentionRepository repository,
            @Value("${dwp.messaging.retention.evidence-maximum-age:PT2H}")
            Duration evidenceMaximumAge) {
        this(properties, repository, evidenceMaximumAge, Clock.systemUTC());
    }

    MessagingRetentionHealthIndicator(
            MessagingRetentionProperties properties,
            MessagingRetentionRepository repository,
            Duration evidenceMaximumAge,
            Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.evidenceMaximumAge = evidenceMaximumAge;
        this.clock = clock;
    }

    @Override
    public Health health() {
        String state = properties.runtimeState();
        Health.Builder health = switch (state) {
            case "BLOCKED", "PAUSED" -> Health.outOfService();
            case "ENFORCING" -> enforcingHealth();
            default -> Health.unknown();
        };
        health.withDetail("state", state)
                .withDetail("executionMode", properties.mode().name())
                .withDetail("killSwitchEnabled", !properties.enabled())
                .withDetail("operatorPaused", properties.paused())
                .withDetail("legalHoldAuthorityReady", properties.holdAuthorityReady())
                .withDetail(
                        "downstreamLifecycleReady", properties.downstreamLifecycleReady());
        if (properties.blockedReason() != null) {
            health.withDetail("reasonCode", properties.blockedReason());
        }
        return health.build();
    }

    private Health.Builder enforcingHealth() {
        if (evidenceMaximumAge == null
                || evidenceMaximumAge.isNegative() || evidenceMaximumAge.isZero()) {
            return Health.outOfService()
                    .withDetail("reasonCode", "RETENTION_EVIDENCE_MAXIMUM_AGE_INVALID");
        }
        try {
            OffsetDateTime staleBefore = OffsetDateTime.now(clock).minus(evidenceMaximumAge);
            MessagingRetentionRepository.RetentionHealthSummary evidence =
                    repository.retentionHealth(staleBefore);
            Health.Builder health = evidence.tenantCount() > 0
                    && evidence.failedTenantCount() == 0
                    && evidence.backlogTenantCount() == 0
                    && evidence.missingSuccessCount() == 0
                    && evidence.staleSuccessCount() == 0
                    ? Health.up() : Health.outOfService();
            String reason = evidence.tenantCount() == 0
                    ? "RETENTION_TENANT_POLICY_MISSING"
                    : evidence.pendingAttachmentCount() > 0
                    ? "ATTACHMENT_DELETION_BACKLOG"
                    : evidence.failedTenantCount() > 0
                    ? "RETENTION_RECENT_RUN_FAILED"
                    : evidence.missingSuccessCount() > 0
                    ? "RETENTION_AWAITING_FIRST_SUCCESS"
                    : evidence.staleSuccessCount() > 0
                    ? "RETENTION_EVIDENCE_STALE" : null;
            if (reason != null) health.withDetail("reasonCode", reason);
            health
                    .withDetail("tenantCount", evidence.tenantCount())
                    .withDetail("failedTenantCount", evidence.failedTenantCount())
                    .withDetail("backlogTenantCount", evidence.backlogTenantCount())
                    .withDetail("pendingAttachmentCount", evidence.pendingAttachmentCount())
                    .withDetail("missingSuccessCount", evidence.missingSuccessCount())
                    .withDetail("staleSuccessCount", evidence.staleSuccessCount());
            if (evidence.lastSuccessAt() != null) {
                health.withDetail("lastSuccessAt", evidence.lastSuccessAt());
            }
            return health;
        } catch (RuntimeException exception) {
            return Health.outOfService()
                    .withDetail("reasonCode", "RETENTION_EVIDENCE_QUERY_FAILED");
        }
    }
}
