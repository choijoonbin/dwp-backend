package com.dwp.services.platform.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
class TenantMediaCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(TenantMediaCleanupWorker.class);

    private final TenantMediaCleanupOutbox outbox;
    private final TenantMediaStorage storage;
    private final boolean enabled;
    private final int batchSize;
    private final int leaseSeconds;
    private final int maximumAttempts;
    private final String workerId;

    TenantMediaCleanupWorker(
            TenantMediaCleanupOutbox outbox,
            TenantMediaStorage storage,
            @Value("${dwp.platform.assets.cleanup.enabled:true}") boolean enabled,
            @Value("${dwp.platform.assets.cleanup.batch-size:25}") int batchSize,
            @Value("${dwp.platform.assets.cleanup.lease-seconds:30}") int leaseSeconds,
            @Value("${dwp.platform.assets.cleanup.maximum-attempts:8}") int maximumAttempts,
            @Value("${dwp.platform.assets.cleanup.worker-id:${HOSTNAME:local}}") String workerName) {
        this.outbox = outbox;
        this.storage = storage;
        this.enabled = enabled;
        this.batchSize = positive(batchSize, "batchSize");
        this.leaseSeconds = positive(leaseSeconds, "leaseSeconds");
        this.maximumAttempts = positive(maximumAttempts, "maximumAttempts");
        this.workerId = workerName + ':' + UUID.randomUUID();
    }

    @Scheduled(fixedDelayString = "${dwp.platform.assets.cleanup.poll-delay-ms:2000}")
    void cleanPending() {
        if (!enabled) return;
        try {
            outbox.releaseExpiredLeases();
            for (TenantMediaCleanupOutbox.CleanupJob job
                    : outbox.claim(workerId, batchSize, leaseSeconds)) {
                clean(job);
            }
        } catch (RuntimeException exception) {
            log.error("Tenant media cleanup polling failed", exception);
        }
    }

    private void clean(TenantMediaCleanupOutbox.CleanupJob job) {
        try {
            if (!outbox.beginDelete(job, workerId)) {
                outbox.complete(job, workerId);
                return;
            }
            storage.delete(job.tenantId(), job.storageKey());
            outbox.completeDelete(job, workerId);
        } catch (RuntimeException exception) {
            long delaySeconds = Math.min(3600L, 5L << Math.min(job.attemptCount() - 1, 9));
            String errorCode = sanitize(exception.getClass().getSimpleName());
            outbox.fail(
                    job,
                    workerId,
                    maximumAttempts,
                    errorCode,
                    OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(delaySeconds));
            log.warn(
                    "Tenant media cleanup attempt failed for cleanupId={} attempt={}",
                    job.cleanupId(), job.attemptCount(), exception);
        }
    }

    private String sanitize(String value) {
        String normalized = value == null || value.isBlank()
                ? "MEDIA_CLEANUP_FAILED"
                : value.replaceAll("[^A-Za-z0-9_.-]", "_").toUpperCase();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private int positive(int value, String field) {
        if (value < 1) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }
}
