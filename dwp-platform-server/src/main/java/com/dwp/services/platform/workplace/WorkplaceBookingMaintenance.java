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
public class WorkplaceBookingMaintenance {

    private static final Logger log = LoggerFactory.getLogger(WorkplaceBookingMaintenance.class);

    private final WorkplaceService service;
    private final WorkplaceMaintenanceMetrics metrics;
    private final Clock clock;
    private final int batchSize;
    private final int perTenantLimit;
    private final int maxBatches;
    private final long maxRunMillis;

    @Autowired
    public WorkplaceBookingMaintenance(
            WorkplaceService service,
            WorkplaceMaintenanceMetrics metrics,
            @Value("${dwp.platform.workplace.booking-maintenance.batch-size:500}")
                    int batchSize,
            @Value("${dwp.platform.workplace.booking-maintenance.per-tenant-limit:50}")
                    int perTenantLimit,
            @Value("${dwp.platform.workplace.booking-maintenance.max-batches:20}")
                    int maxBatches,
            @Value("${dwp.platform.workplace.booking-maintenance.max-run-ms:20000}")
                    long maxRunMillis) {
        this(service, metrics, Clock.systemUTC(), batchSize, perTenantLimit,
                maxBatches, maxRunMillis);
    }

    WorkplaceBookingMaintenance(
            WorkplaceService service,
            WorkplaceMaintenanceMetrics metrics,
            Clock clock,
            int batchSize,
            int perTenantLimit,
            int maxBatches,
            long maxRunMillis) {
        WorkplaceMaintenanceRunBudget.validateBatchSize(batchSize);
        WorkplaceMaintenanceRunBudget.validateMaxBatches(maxBatches);
        WorkplaceMaintenanceRunBudget.validateMaxRunMillis(maxRunMillis);
        if (perTenantLimit < 1 || perTenantLimit > batchSize) {
            throw new IllegalArgumentException(
                    "perTenantLimit must be between 1 and batchSize");
        }
        this.service = service;
        this.metrics = metrics;
        this.clock = clock;
        this.batchSize = batchSize;
        this.perTenantLimit = perTenantLimit;
        this.maxBatches = maxBatches;
        this.maxRunMillis = maxRunMillis;
    }

    @Scheduled(fixedDelayString = "${dwp.platform.workplace.no-show-sweep-ms:60000}")
    public void maintainBookingLifecycle() {
        WorkplaceMaintenanceRunBudget budget =
                new WorkplaceMaintenanceRunBudget(clock, maxBatches, maxRunMillis);
        int noShows = 0;
        int completed = 0;
        boolean lockContended = false;
        try {
            while (budget.tryStartBatch()) {
                WorkplaceService.LifecycleBatch batch =
                        service.maintainBookingLifecycle(batchSize, perTenantLimit);
                if (!batch.lockAcquired()) {
                    lockContended = true;
                    break;
                }
                noShows = Math.addExact(noShows, batch.noShows());
                completed = Math.addExact(completed, batch.completed());
                metrics.processed("booking-lifecycle", "no-show", batch.noShows());
                metrics.processed("booking-lifecycle", "completion", batch.completed());
                if (batch.total() == 0) {
                    break;
                }
            }

            OffsetDateTime observedAt = OffsetDateTime.now(clock);
            WorkplaceBookingRepository.LifecycleBacklog backlog =
                    service.bookingLifecycleBacklog();
            metrics.booking(backlog, observedAt);
            String outcome = lockContended
                    ? "lock-contended"
                    : backlog.total() == 0 ? "drained" : "budget-exhausted";
            metrics.run("booking-lifecycle", outcome);
            if (backlog.total() > 0 && !lockContended) {
                log.warn(
                        "Workplace booking lifecycle stopped after {} batches with {} records "
                                + "remaining",
                        budget.startedBatches(), backlog.total());
            } else if (noShows + completed > 0) {
                log.info(
                        "Workplace booking lifecycle released {} no-shows and completed {} "
                                + "bookings in {} batches",
                        noShows, completed, budget.startedBatches());
            }
        } catch (RuntimeException failure) {
            metrics.run("booking-lifecycle", "failed");
            log.error("Workplace booking lifecycle maintenance failed", failure);
        }
    }
}
