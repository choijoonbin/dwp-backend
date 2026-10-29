package com.dwp.services.messaging.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("dwp.messaging.retention")
public record MessagingRetentionProperties(
        boolean enabled,
        ExecutionMode mode,
        boolean paused,
        boolean holdAuthorityReady,
        boolean downstreamLifecycleReady,
        int batchSize,
        int maximumBatchesPerRun,
        int maximumAttachmentDeletesPerRun,
        Duration leaseDuration) {

    public MessagingRetentionProperties {
        mode = mode == null ? ExecutionMode.DRY_RUN : mode;
        batchSize = batchSize == 0 ? 100 : batchSize;
        maximumBatchesPerRun = maximumBatchesPerRun == 0 ? 10 : maximumBatchesPerRun;
        maximumAttachmentDeletesPerRun = maximumAttachmentDeletesPerRun == 0
                ? 1_000 : maximumAttachmentDeletesPerRun;
        leaseDuration = leaseDuration == null ? Duration.ofMinutes(15) : leaseDuration;
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Messaging retention batch size must be 1 to 500.");
        }
        if (maximumBatchesPerRun < 1 || maximumBatchesPerRun > 100) {
            throw new IllegalArgumentException(
                    "Messaging retention maximum batches per run must be 1 to 100.");
        }
        if (maximumAttachmentDeletesPerRun < 1 || maximumAttachmentDeletesPerRun > 5_000) {
            throw new IllegalArgumentException(
                    "Messaging retention attachment delete limit must be 1 to 5000.");
        }
        if (leaseDuration.compareTo(Duration.ofMinutes(1)) < 0
                || leaseDuration.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException(
                    "Messaging retention lease duration must be 1 minute to 2 hours.");
        }
    }

    public enum ExecutionMode {
        DRY_RUN,
        ENFORCING
    }

    public String runtimeState() {
        if (!enabled) return "DISABLED";
        if (paused) return "PAUSED";
        if (mode == ExecutionMode.DRY_RUN) return "DRY_RUN";
        return holdAuthorityReady && downstreamLifecycleReady ? "ENFORCING" : "BLOCKED";
    }

    public String blockedReason() {
        if (mode != ExecutionMode.ENFORCING || !enabled || paused) return null;
        if (!holdAuthorityReady) return "LEGAL_HOLD_AUTHORITY_UNAVAILABLE";
        if (!downstreamLifecycleReady) return "DOWNSTREAM_LIFECYCLE_UNVERIFIED";
        return null;
    }
}
