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
class WorkplaceExperienceFacilitiesMaintenance {

    private static final Logger log =
            LoggerFactory.getLogger(WorkplaceExperienceFacilitiesMaintenance.class);

    private final WorkplaceExperienceFacilitiesRetention retention;
    private final WorkplaceMaintenanceMetrics metrics;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;
    private final int maxBatches;
    private final long maxRunMillis;

    @Autowired
    WorkplaceExperienceFacilitiesMaintenance(
            WorkplaceExperienceFacilitiesRetention retention,
            WorkplaceMaintenanceMetrics metrics,
            @Value("${dwp.platform.workplace.privacy-maintenance.enabled:true}")
                    boolean enabled,
            @Value("${dwp.platform.workplace.privacy-maintenance.batch-size:500}")
                    int batchSize,
            @Value("${dwp.platform.workplace.privacy-maintenance.max-batches:30}")
                    int maxBatches,
            @Value("${dwp.platform.workplace.privacy-maintenance.max-run-ms:30000}")
                    long maxRunMillis) {
        this(retention, metrics, Clock.systemUTC(), enabled, batchSize,
                maxBatches, maxRunMillis);
    }

    WorkplaceExperienceFacilitiesMaintenance(
            WorkplaceExperienceFacilitiesRetention retention,
            WorkplaceMaintenanceMetrics metrics,
            Clock clock,
            boolean enabled,
            int batchSize,
            int maxBatches,
            long maxRunMillis) {
        WorkplaceMaintenanceRunBudget.validateBatchSize(batchSize);
        WorkplaceMaintenanceRunBudget.validateMaxBatches(maxBatches);
        WorkplaceMaintenanceRunBudget.validateMaxRunMillis(maxRunMillis);
        this.retention = retention;
        this.metrics = metrics;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.maxRunMillis = maxRunMillis;
    }

    @Scheduled(cron = "${dwp.platform.workplace.privacy-maintenance.cron:0 23 3 * * *}")
    void purgeExpiredRecords() {
        if (!enabled) return;
        WorkplaceMaintenanceRunBudget budget =
                new WorkplaceMaintenanceRunBudget(clock, maxBatches, maxRunMillis);
        long requests = 0;
        long closures = 0;
        try {
            while (budget.tryStartBatch()) {
                WorkplaceExperienceFacilitiesRetention.Purged result =
                        retention.purge(batchSize);
                validBatch(result.requests());
                validBatch(result.closures());
                requests = Math.addExact(requests, result.requests());
                closures = Math.addExact(closures, result.closures());
                metrics.processed("facility-retention", "request", result.requests());
                metrics.processed("facility-retention", "closure", result.closures());
                if (result.requests() < batchSize && result.closures() < batchSize) {
                    break;
                }
            }

            OffsetDateTime observedAt = OffsetDateTime.now(clock);
            WorkplaceExperienceFacilitiesRetention.RetentionBacklog backlog =
                    retention.retentionBacklog();
            metrics.facilities(backlog, observedAt);
            metrics.run("facility-retention",
                    backlog.total() == 0 ? "drained" : "budget-exhausted");
            if (backlog.total() > 0) {
                log.warn(
                        "Native Workplace facility retention stopped after {} batches with {} "
                                + "eligible records remaining",
                        budget.startedBatches(), backlog.total());
            }
            if (requests + closures > 0) {
                log.info(
                        "Purged {} retained facility requests and {} retained closures in {} "
                                + "batches",
                        requests, closures, budget.startedBatches());
            }
        } catch (RuntimeException failure) {
            metrics.run("facility-retention", "failed");
            log.error("Native Workplace facility retention failed", failure);
        }
    }

    private void validBatch(int count) {
        if (count < 0 || count > batchSize) {
            throw new IllegalStateException(
                    "facility retention returned an invalid batch size of " + count);
        }
    }
}
