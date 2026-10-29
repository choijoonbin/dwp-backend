package com.dwp.services.platform.workplace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

@Component
class WorkplacePrivacyMaintenance {

    private static final Logger log = LoggerFactory.getLogger(WorkplacePrivacyMaintenance.class);

    private final WorkplacePrivacyRepository repository;
    private final WorkplaceMaintenanceMetrics metrics;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;
    private final int auditRetentionDays;
    private final int maxBatches;
    private final long maxRunMillis;

    @Autowired
    WorkplacePrivacyMaintenance(
            WorkplacePrivacyRepository repository,
            WorkplaceMaintenanceMetrics metrics,
            @Value("${dwp.platform.workplace.privacy-maintenance.enabled:true}") boolean enabled,
            @Value("${dwp.platform.workplace.privacy-maintenance.batch-size:500}") int batchSize,
            @Value("${dwp.platform.workplace.privacy-maintenance.audit-retention-days:2555}")
                    int auditRetentionDays,
            @Value("${dwp.platform.workplace.privacy-maintenance.max-batches:30}")
                    int maxBatches,
            @Value("${dwp.platform.workplace.privacy-maintenance.max-run-ms:30000}")
                    long maxRunMillis) {
        this(repository, metrics, Clock.systemUTC(), enabled, batchSize,
                auditRetentionDays, maxBatches, maxRunMillis);
    }

    WorkplacePrivacyMaintenance(
            WorkplacePrivacyRepository repository,
            WorkplaceMaintenanceMetrics metrics,
            Clock clock,
            boolean enabled,
            int batchSize,
            int auditRetentionDays,
            int maxBatches,
            long maxRunMillis) {
        WorkplaceMaintenanceRunBudget.validateBatchSize(batchSize);
        WorkplaceMaintenanceRunBudget.validateMaxBatches(maxBatches);
        WorkplaceMaintenanceRunBudget.validateMaxRunMillis(maxRunMillis);
        if (maxBatches < 3) {
            throw new IllegalArgumentException(
                    "privacy maintenance maxBatches must be at least 3");
        }
        if (auditRetentionDays < 365 || auditRetentionDays > 3650) {
            throw new IllegalArgumentException(
                    "auditRetentionDays must be between 365 and 3650");
        }
        this.repository = repository;
        this.metrics = metrics;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.auditRetentionDays = auditRetentionDays;
        this.maxBatches = maxBatches;
        this.maxRunMillis = maxRunMillis;
    }

    @Scheduled(cron = "${dwp.platform.workplace.privacy-maintenance.cron:0 23 3 * * *}")
    void anonymizeExpiredBookings() {
        if (!enabled) return;
        WorkplaceMaintenanceRunBudget budget =
                new WorkplaceMaintenanceRunBudget(clock, maxBatches, maxRunMillis);
        OffsetDateTime startedAt = OffsetDateTime.now(clock);
        OffsetDateTime auditCutoff = startedAt.minusDays(auditRetentionDays);
        long anonymized = 0;
        long releaseWindows = 0;
        long auditEvents = 0;
        boolean bookingsDrained = false;
        boolean releaseWindowsDrained = false;
        boolean auditsDrained = false;
        try {
            while (!(bookingsDrained && releaseWindowsDrained && auditsDrained)) {
                if (!bookingsDrained) {
                    if (!budget.tryStartBatch()) break;
                    int count = validBatch(repository.anonymizeExpired(batchSize));
                    anonymized = Math.addExact(anonymized, count);
                    metrics.processed("privacy-retention", "booking", count);
                    bookingsDrained = count < batchSize;
                }
                if (!releaseWindowsDrained) {
                    if (!budget.tryStartBatch()) break;
                    int count = validBatch(
                            repository.anonymizeExpiredReleaseWindows(batchSize));
                    releaseWindows = Math.addExact(releaseWindows, count);
                    metrics.processed("privacy-retention", "release-window", count);
                    releaseWindowsDrained = count < batchSize;
                }
                if (!auditsDrained) {
                    if (!budget.tryStartBatch()) break;
                    int count = validBatch(
                            repository.purgeExpiredAuditReplicas(batchSize, auditCutoff));
                    auditEvents = Math.addExact(auditEvents, count);
                    metrics.processed("privacy-retention", "audit-replica", count);
                    auditsDrained = count < batchSize;
                }
            }

            OffsetDateTime observedAt = OffsetDateTime.now(clock);
            WorkplacePrivacyRepository.RetentionBacklog backlog =
                    repository.retentionBacklog(observedAt, auditRetentionDays);
            metrics.privacy(backlog, observedAt);
            metrics.run("privacy-retention",
                    backlog.total() == 0 ? "drained" : "budget-exhausted");
            if (backlog.total() > 0) {
                log.warn(
                        "Workplace privacy maintenance stopped after {} batches with {} "
                                + "eligible records remaining",
                        budget.startedBatches(), backlog.total());
            }
            if (anonymized + releaseWindows + auditEvents > 0) {
                log.info(
                        "Workplace privacy maintenance anonymized {} bookings and {} release "
                                + "windows, and purged {} expired audit replicas in {} batches",
                        anonymized, releaseWindows, auditEvents, budget.startedBatches());
            }
        } catch (RuntimeException exception) {
            metrics.run("privacy-retention", "failed");
            log.error("Workplace reservation privacy maintenance failed", exception);
        }
    }

    private int validBatch(int count) {
        if (count < 0 || count > batchSize) {
            throw new IllegalStateException(
                    "privacy maintenance returned an invalid batch size of " + count);
        }
        return count;
    }
}
