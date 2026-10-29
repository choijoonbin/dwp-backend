package com.dwp.services.messaging.operations;

import com.dwp.services.messaging.attachment.AttachmentProperties;
import com.dwp.services.messaging.attachment.AttachmentScanner;
import com.dwp.services.messaging.attachment.AttachmentStorage;
import com.dwp.services.messaging.domain.MessagingDtos;
import com.dwp.services.messaging.meeting.MeetingProvider;
import com.dwp.services.messaging.meeting.MeetingProviderCapability;
import com.dwp.services.messaging.presence.MessagingPresenceHealthIndicator;
import com.dwp.services.messaging.presence.MessagingPresenceService;
import com.dwp.services.messaging.realtime.MessagingRealtimeFanoutProbe;
import com.dwp.services.messaging.retention.MessagingRetentionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Cached, truthful runtime capability evidence for the Messaging admin surface. */
@Component
public final class MessagingOperationalReadiness {

    private final AttachmentStorage storage;
    private final AttachmentScanner scanner;
    private final AttachmentProperties attachmentProperties;
    private final MeetingProvider meetingProvider;
    private final MessagingPresenceService presenceService;
    private final MessagingPresenceHealthIndicator presenceHealth;
    private final MessagingRetentionService retentionService;
    private final MessagingScheduledDeliveryOperations scheduledDeliveryOperations;
    private final MessagingDeliveryReadinessOperations deliveryReadinessOperations;
    private final MessagingRealtimeFanoutProbe realtimeFanoutProbe;
    private final boolean telemetryConfigured;
    private final Duration providerEvidenceMaximumAge;
    private final Duration retentionEvidenceMaximumAge;
    private final Clock clock;
    private volatile ProviderEvidence providerEvidence;

    @Autowired
    public MessagingOperationalReadiness(
            AttachmentStorage storage,
            AttachmentScanner scanner,
            AttachmentProperties attachmentProperties,
            MeetingProvider meetingProvider,
            MessagingPresenceService presenceService,
            MessagingPresenceHealthIndicator presenceHealth,
            MessagingRetentionService retentionService,
            MessagingScheduledDeliveryOperations scheduledDeliveryOperations,
            MessagingDeliveryReadinessOperations deliveryReadinessOperations,
            MessagingRealtimeFanoutProbe realtimeFanoutProbe,
            @Value("${otel.sdk.disabled:true}") boolean telemetryDisabled,
            @Value("${dwp.messaging.readiness.maximum-age:PT3M}")
            Duration providerEvidenceMaximumAge,
            @Value("${dwp.messaging.retention.evidence-maximum-age:PT2H}")
            Duration retentionEvidenceMaximumAge) {
        this(
                storage, scanner, attachmentProperties, meetingProvider, presenceService,
                presenceHealth, retentionService, scheduledDeliveryOperations,
                deliveryReadinessOperations, realtimeFanoutProbe, !telemetryDisabled,
                providerEvidenceMaximumAge, retentionEvidenceMaximumAge, Clock.systemUTC());
    }

    MessagingOperationalReadiness(
            AttachmentStorage storage,
            AttachmentScanner scanner,
            AttachmentProperties attachmentProperties,
            MeetingProvider meetingProvider,
            MessagingPresenceService presenceService,
            MessagingPresenceHealthIndicator presenceHealth,
            MessagingRetentionService retentionService,
            MessagingScheduledDeliveryOperations scheduledDeliveryOperations,
            MessagingDeliveryReadinessOperations deliveryReadinessOperations,
            MessagingRealtimeFanoutProbe realtimeFanoutProbe,
            boolean telemetryConfigured,
            Duration providerEvidenceMaximumAge,
            Duration retentionEvidenceMaximumAge,
            Clock clock) {
        if (providerEvidenceMaximumAge == null
                || providerEvidenceMaximumAge.compareTo(Duration.ofSeconds(30)) < 0
                || providerEvidenceMaximumAge.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                    "Messaging provider readiness maximum age must be 30 seconds to 1 hour.");
        }
        if (retentionEvidenceMaximumAge == null
                || retentionEvidenceMaximumAge.compareTo(Duration.ofMinutes(1)) < 0
                || retentionEvidenceMaximumAge.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException(
                    "Messaging retention evidence maximum age must be 1 minute to 24 hours.");
        }
        this.storage = storage;
        this.scanner = scanner;
        this.attachmentProperties = attachmentProperties;
        this.meetingProvider = meetingProvider;
        this.presenceService = presenceService;
        this.presenceHealth = presenceHealth;
        this.retentionService = retentionService;
        this.scheduledDeliveryOperations = scheduledDeliveryOperations;
        this.deliveryReadinessOperations = deliveryReadinessOperations;
        this.realtimeFanoutProbe = realtimeFanoutProbe;
        this.telemetryConfigured = telemetryConfigured;
        this.providerEvidenceMaximumAge = providerEvidenceMaximumAge;
        this.retentionEvidenceMaximumAge = retentionEvidenceMaximumAge;
        this.clock = clock;
        this.providerEvidence = ProviderEvidence.unverified(realtimeFanoutProbe.configured());
    }

    @Scheduled(
            initialDelayString = "${dwp.messaging.readiness.initial-delay:PT10S}",
            fixedDelayString = "${dwp.messaging.readiness.interval:PT1M}")
    public void refreshProviderEvidence() {
        OffsetDateTime verifiedAt = OffsetDateTime.now(clock);
        AttachmentStorage.ProviderHealth storageHealth = safeStorageHealth();
        AttachmentScanner.ProviderHealth scannerHealth = safeScannerHealth();
        boolean presenceEffective = presenceService.enabled()
                && Status.UP.equals(presenceHealth.health().getStatus());
        providerEvidence = new ProviderEvidence(
                provider(storageHealth.effective(), storageHealth.productionReady(),
                        storageHealth.reasonCode(), verifiedAt),
                provider(scannerHealth.effective(), scannerHealth.productionReady(),
                        scannerHealth.reasonCode(), verifiedAt),
                new ProviderStatus(
                        presenceEffective,
                        presenceEffective ? "READY" : "UNAVAILABLE",
                        presenceEffective ? null : "PRESENCE_AUTHORITY_UNAVAILABLE",
                        verifiedAt),
                safeFanoutProbe(verifiedAt));
    }

    public MessagingDtos.RuntimeReadiness snapshot(long tenantId) {
        OffsetDateTime evaluatedAt = OffsetDateTime.now(clock);
        ProviderEvidence evidence = providerEvidence;
        List<MessagingDtos.RuntimeCapability> capabilities =
                providerCapabilities(evaluatedAt, evidence);
        MessagingDeliveryReadinessOperations.DeliveryEvidence delivery =
                deliveryReadinessOperations.evidence(tenantId);
        capabilities.add(durableCommitCapability(delivery.durableLog()));
        capabilities.add(sseResumeCapability(delivery.durableLog()));
        capabilities.add(notificationOutboxCapability(delivery.notificationOutbox()));
        capabilities.add(retentionCapability(tenantId, evaluatedAt));
        capabilities.add(scheduledDeliveryCapability(tenantId));
        return new MessagingDtos.RuntimeReadiness(
                evaluatedAt, overallState(capabilities), List.copyOf(capabilities));
    }

    public MessagingDtos.RuntimeReadiness providerSnapshot() {
        OffsetDateTime evaluatedAt = OffsetDateTime.now(clock);
        List<MessagingDtos.RuntimeCapability> capabilities =
                providerCapabilities(evaluatedAt, providerEvidence);
        return new MessagingDtos.RuntimeReadiness(
                evaluatedAt, overallState(capabilities), List.copyOf(capabilities));
    }

    private List<MessagingDtos.RuntimeCapability> providerCapabilities(
            OffsetDateTime evaluatedAt, ProviderEvidence evidence) {
        List<MessagingDtos.RuntimeCapability> capabilities = new ArrayList<>();
        capabilities.add(providerCapability(
                "ATTACHMENT_STORAGE", attachmentProperties.storage(), true, true,
                evidence.storage(), evaluatedAt));
        capabilities.add(providerCapability(
                "ATTACHMENT_SCANNER", attachmentProperties.scanner(), true, true,
                evidence.scanner(), evaluatedAt));
        capabilities.add(meetingCapability(evaluatedAt));
        capabilities.add(new MessagingDtos.RuntimeCapability(
                "SEARCH", "postgresql", true, true, true,
                "DEGRADED", "SQL_FALLBACK_ACTIVE",
                evaluatedAt, "CURRENT", null));
        capabilities.add(presenceCapability(evidence.presence(), evaluatedAt));
        capabilities.add(fanoutCapability(evidence.fanout(), evaluatedAt));
        capabilities.add(telemetryCapability());
        return capabilities;
    }

    private MessagingDtos.RuntimeCapability providerCapability(
            String capability,
            String providerName,
            boolean required,
            boolean configured,
            ProviderStatus provider,
            OffsetDateTime evaluatedAt) {
        ProviderStatus current = current(provider, evaluatedAt);
        return new MessagingDtos.RuntimeCapability(
                capability, providerName, required, configured,
                current.effective(), current.state(),
                current.reasonCode(), current.verifiedAt(),
                freshness(current.verifiedAt(), evaluatedAt, providerEvidenceMaximumAge), null);
    }

    private MessagingDtos.RuntimeCapability presenceCapability(
            ProviderStatus provider, OffsetDateTime evaluatedAt) {
        if (!presenceService.enabled()) {
            return new MessagingDtos.RuntimeCapability(
                    "PRESENCE", "redis", true, false, false,
                    "DISABLED", "PRESENCE_DISABLED",
                    provider.verifiedAt(), freshness(
                            provider.verifiedAt(), evaluatedAt, providerEvidenceMaximumAge), null);
        }
        return providerCapability(
                "PRESENCE", "redis", true, true, provider, evaluatedAt);
    }

    private MessagingDtos.RuntimeCapability meetingCapability(OffsetDateTime evaluatedAt) {
        MeetingProviderCapability capability = meetingProvider.capability();
        if (!capability.available()) {
            return new MessagingDtos.RuntimeCapability(
                    "MEETING", capability.provider(), false, false, false, "DISABLED",
                    value(capability.unavailableReason(), "MEETING_PROVIDER_DISABLED"),
                    null, "NEVER", null);
        }
        return new MessagingDtos.RuntimeCapability(
                "MEETING", capability.provider(), false, true, false, "DEGRADED",
                "MEETING_PROVIDER_CONNECTIVITY_UNVERIFIED", null, "NEVER", null);
    }

    private MessagingDtos.RuntimeCapability fanoutCapability(
            FanoutStatus provider, OffsetDateTime evaluatedAt) {
        String evidenceFreshness = freshness(
                provider.verifiedAt(), evaluatedAt, providerEvidenceMaximumAge);
        if (provider.verifiedAt() != null && !"CURRENT".equals(evidenceFreshness)) {
            return new MessagingDtos.RuntimeCapability(
                    "REALTIME_FANOUT", "redis-pubsub", false, provider.configured(), false,
                    "DEGRADED", "REALTIME_FANOUT_EVIDENCE_STALE", provider.verifiedAt(),
                    evidenceFreshness, null);
        }
        return new MessagingDtos.RuntimeCapability(
                "REALTIME_FANOUT", "redis-pubsub", false, provider.configured(),
                provider.effective(), provider.state(), provider.reasonCode(),
                provider.verifiedAt(), evidenceFreshness, null);
    }

    private MessagingDtos.RuntimeCapability durableCommitCapability(
            MessagingDeliveryReadinessOperations.DurableLogStatus status) {
        return new MessagingDtos.RuntimeCapability(
                "DURABLE_COMMIT", "postgresql-event-log", true, true, status.effective(),
                status.state(), status.reasonCode(), status.verifiedAt(), "CURRENT", null);
    }

    private MessagingDtos.RuntimeCapability sseResumeCapability(
            MessagingDeliveryReadinessOperations.DurableLogStatus status) {
        return new MessagingDtos.RuntimeCapability(
                "SSE_RESUME", "postgresql-event-log", true, true, status.effective(),
                status.state(), status.effective() ? null : "SSE_REPLAY_LOG_UNAVAILABLE",
                status.verifiedAt(), "CURRENT", null);
    }

    private MessagingDtos.RuntimeCapability notificationOutboxCapability(
            MessagingDeliveryReadinessOperations.NotificationOutboxStatus status) {
        return new MessagingDtos.RuntimeCapability(
                "NOTIFICATION_OUTBOX", "transactional-kafka-outbox", true,
                status.configured(), status.effective(), status.state(), status.reasonCode(),
                status.verifiedAt(), "CURRENT", status.pendingWorkCount());
    }

    private MessagingDtos.RuntimeCapability retentionCapability(
            long tenantId, OffsetDateTime evaluatedAt) {
        MessagingRetentionService.RetentionStatus retention = retentionService.status(tenantId);
        String state = retention.state();
        String reason = retention.reasonCode();
        boolean effective = false;
        if (retention.pendingAttachmentDeletionCount() > 0) {
            state = "BLOCKED";
            reason = "ATTACHMENT_DELETION_BACKLOG";
        } else if ("ENFORCING".equals(state)) {
            if ("COMPLETED".equals(retention.lastRunState())
                    && "ENFORCING".equals(retention.lastRunExecutionMode())) {
                state = "READY";
                effective = true;
            } else if ("FAILED".equals(retention.lastRunState())) {
                state = "BLOCKED";
            } else {
                state = "DEGRADED";
                reason = "COMPLETED".equals(retention.lastRunState())
                        ? "RETENTION_AWAITING_ENFORCING_SUCCESS"
                        : "RETENTION_AWAITING_FIRST_SUCCESS";
            }
        } else if ("PAUSED".equals(state)) {
            state = "BLOCKED";
            reason = "OPERATOR_PAUSED";
        } else if ("DRY_RUN".equals(state)) {
            state = "DEGRADED";
            reason = "RETENTION_DRY_RUN_ONLY";
        } else if ("DISABLED".equals(state)) {
            reason = "RETENTION_DISABLED";
        }
        String freshness = freshness(
                retention.lastVerifiedAt(), evaluatedAt, retentionEvidenceMaximumAge);
        if (effective && !"CURRENT".equals(freshness)) {
            effective = false;
            state = "DEGRADED";
            reason = "RETENTION_EVIDENCE_STALE";
        }
        return new MessagingDtos.RuntimeCapability(
                "RETENTION_ENFORCEMENT", "database-worker", true,
                retention.configured(), effective, state, reason,
                retention.lastVerifiedAt(), freshness,
                retention.pendingAttachmentDeletionCount());
    }

    private MessagingDtos.RuntimeCapability telemetryCapability() {
        return telemetryConfigured
                ? new MessagingDtos.RuntimeCapability(
                        "OBSERVABILITY", "opentelemetry", true, true, false, "DEGRADED",
                        "TELEMETRY_EXPORT_UNVERIFIED", null, "NEVER", null)
                : new MessagingDtos.RuntimeCapability(
                        "OBSERVABILITY", "opentelemetry", true, false, false, "DISABLED",
                        "TELEMETRY_DISABLED", null, "NEVER", null);
    }

    private MessagingDtos.RuntimeCapability scheduledDeliveryCapability(long tenantId) {
        MessagingScheduledDeliveryOperations.ScheduledDeliveryStatus scheduled =
                scheduledDeliveryOperations.status(tenantId);
        String freshness = scheduled.lastCompletedAt() == null ? "NEVER"
                : "SCHEDULED_DELIVERY_HEARTBEAT_STALE".equals(scheduled.reasonCode())
                ? "STALE" : "CURRENT";
        return new MessagingDtos.RuntimeCapability(
                "SCHEDULED_DELIVERY", "database-worker", true,
                scheduled.configured(), scheduled.effective(), scheduled.state(),
                scheduled.reasonCode(), scheduled.lastCompletedAt(), freshness,
                scheduled.pendingCount() + scheduled.recentFailedCount());
    }

    private ProviderStatus current(ProviderStatus provider, OffsetDateTime evaluatedAt) {
        if (provider.verifiedAt() == null) return provider;
        if (Duration.between(provider.verifiedAt(), evaluatedAt)
                .compareTo(providerEvidenceMaximumAge) <= 0) return provider;
        return new ProviderStatus(
                false, "UNAVAILABLE", "PROVIDER_EVIDENCE_STALE", provider.verifiedAt());
    }

    private ProviderStatus provider(
            boolean effective,
            boolean productionReady,
            String reasonCode,
            OffsetDateTime verifiedAt) {
        if (!effective) {
            return new ProviderStatus(
                    false, "UNAVAILABLE", value(reasonCode, "PROVIDER_UNAVAILABLE"), verifiedAt);
        }
        return productionReady
                ? new ProviderStatus(true, "READY", null, verifiedAt)
                : new ProviderStatus(
                        true, "DEGRADED", value(reasonCode, "DEVELOPMENT_PROVIDER"), verifiedAt);
    }

    private AttachmentStorage.ProviderHealth safeStorageHealth() {
        try {
            return storage.health();
        } catch (RuntimeException exception) {
            return new AttachmentStorage.ProviderHealth(
                    false, false, "ATTACHMENT_STORAGE_PROBE_FAILED");
        }
    }

    private AttachmentScanner.ProviderHealth safeScannerHealth() {
        try {
            return scanner.health();
        } catch (RuntimeException exception) {
            return new AttachmentScanner.ProviderHealth(
                    false, false, "ATTACHMENT_SCANNER_PROBE_FAILED");
        }
    }

    private FanoutStatus safeFanoutProbe(OffsetDateTime verifiedAt) {
        try {
            MessagingRealtimeFanoutProbe.ProbeResult result = realtimeFanoutProbe.probe(verifiedAt);
            return new FanoutStatus(
                    result.configured(), result.effective(), result.state(),
                    result.reasonCode(), result.verifiedAt());
        } catch (RuntimeException exception) {
            return new FanoutStatus(
                    realtimeFanoutProbe.configured(), false, "DEGRADED",
                    "REALTIME_FANOUT_PROBE_FAILED", verifiedAt);
        }
    }

    private String overallState(List<MessagingDtos.RuntimeCapability> capabilities) {
        boolean degraded = false;
        for (MessagingDtos.RuntimeCapability capability : capabilities) {
            if (!capability.required()) continue;
            if (List.of("BLOCKED", "UNAVAILABLE", "DISABLED").contains(capability.state())) {
                return "BLOCKED";
            }
            degraded |= "DEGRADED".equals(capability.state());
        }
        return degraded ? "DEGRADED" : "READY";
    }

    private String freshness(
            OffsetDateTime verifiedAt, OffsetDateTime evaluatedAt, Duration maximumAge) {
        if (verifiedAt == null) return "NEVER";
        return Duration.between(verifiedAt, evaluatedAt).compareTo(maximumAge) <= 0
                ? "CURRENT" : "STALE";
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    private record ProviderStatus(
            boolean effective, String state, String reasonCode, OffsetDateTime verifiedAt) {

        private static ProviderStatus unverified(String reasonCode) {
            return new ProviderStatus(false, "UNAVAILABLE", reasonCode, null);
        }
    }

    private record ProviderEvidence(
            ProviderStatus storage,
            ProviderStatus scanner,
            ProviderStatus presence,
            FanoutStatus fanout) {

        private static ProviderEvidence unverified(boolean fanoutConfigured) {
            return new ProviderEvidence(
                    ProviderStatus.unverified("ATTACHMENT_STORAGE_NOT_YET_VERIFIED"),
                    ProviderStatus.unverified("ATTACHMENT_SCANNER_NOT_YET_VERIFIED"),
                    ProviderStatus.unverified("PRESENCE_NOT_YET_VERIFIED"),
                    new FanoutStatus(
                            fanoutConfigured, false,
                            fanoutConfigured ? "DEGRADED" : "DISABLED",
                            fanoutConfigured
                                    ? "REALTIME_FANOUT_NOT_YET_VERIFIED"
                                    : "REALTIME_FANOUT_DISABLED",
                            null));
        }
    }

    private record FanoutStatus(
            boolean configured,
            boolean effective,
            String state,
            String reasonCode,
            OffsetDateTime verifiedAt) {
    }
}
