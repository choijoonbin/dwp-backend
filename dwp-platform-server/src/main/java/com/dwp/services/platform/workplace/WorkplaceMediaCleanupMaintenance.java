package com.dwp.services.platform.workplace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
class WorkplaceMediaCleanupMaintenance {

    private static final Logger log =
            LoggerFactory.getLogger(WorkplaceMediaCleanupMaintenance.class);

    private final WorkplaceMediaCleanupRepository repository;
    private final boolean enabled;
    private final int batchSize;
    private final int stagedGraceMinutes;

    WorkplaceMediaCleanupMaintenance(
            WorkplaceMediaCleanupRepository repository,
            @Value("${dwp.platform.workplace.media-cleanup.enabled:true}") boolean enabled,
            @Value("${dwp.platform.workplace.media-cleanup.batch-size:200}") int batchSize,
            @Value("${dwp.platform.workplace.media-cleanup.staged-grace-minutes:120}")
                    int stagedGraceMinutes) {
        if (batchSize < 1 || batchSize > 2000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 2000");
        }
        if (stagedGraceMinutes < 15 || stagedGraceMinutes > 10080) {
            throw new IllegalArgumentException(
                    "stagedGraceMinutes must be between 15 and 10080");
        }
        this.repository = repository;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.stagedGraceMinutes = stagedGraceMinutes;
    }

    @Scheduled(cron = "${dwp.platform.workplace.media-cleanup.cron:0 */15 * * * *}")
    void reconcile() {
        if (!enabled) return;
        try {
            int count = repository.reconcile(
                    batchSize, OffsetDateTime.now().minusMinutes(stagedGraceMinutes));
            if (count > 0) {
                log.info("Reconciled {} unreferenced Workplace floor-plan media assets", count);
            }
        } catch (RuntimeException exception) {
            log.error("Workplace floor-plan media reconciliation failed", exception);
        }
    }
}
