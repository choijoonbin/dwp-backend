package com.dwp.services.platform.workplace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/** Native plans reuse the existing Workplace privacy policy and maintenance schedule. */
@Component
class WorkplaceExperienceCollaborationMaintenance {
    private static final Logger log = LoggerFactory.getLogger(WorkplaceExperienceCollaborationMaintenance.class);
    private final WorkplaceExperienceCollaborationRepository repository;
    private final WorkplaceMaintenanceMetrics metrics;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;
    private final int maxBatches;
    private final long maxRunMillis;

    @Autowired
    WorkplaceExperienceCollaborationMaintenance(
            WorkplaceExperienceCollaborationRepository repository,
            WorkplaceMaintenanceMetrics metrics,
            @Value("${dwp.platform.workplace.privacy-maintenance.enabled:true}")
                    boolean enabled,
            @Value("${dwp.platform.workplace.privacy-maintenance.batch-size:500}")
                    int batchSize,
            @Value("${dwp.platform.workplace.privacy-maintenance.max-batches:30}")
                    int maxBatches,
            @Value("${dwp.platform.workplace.privacy-maintenance.max-run-ms:30000}")
                    long maxRunMillis) {
        this(repository, metrics, Clock.systemUTC(), enabled, batchSize,
                maxBatches, maxRunMillis);
    }

    WorkplaceExperienceCollaborationMaintenance(
            WorkplaceExperienceCollaborationRepository repository,
            WorkplaceMaintenanceMetrics metrics,
            Clock clock,
            boolean enabled,
            int batchSize,
            int maxBatches,
            long maxRunMillis) {
        WorkplaceMaintenanceRunBudget.validateBatchSize(batchSize);
        WorkplaceMaintenanceRunBudget.validateMaxBatches(maxBatches);
        WorkplaceMaintenanceRunBudget.validateMaxRunMillis(maxRunMillis);
        this.repository = repository;
        this.metrics = metrics;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.maxRunMillis = maxRunMillis;
    }

    @Scheduled(cron = "${dwp.platform.workplace.privacy-maintenance.cron:0 23 3 * * *}")
    void purgeExpiredPlans() {
        if (!enabled) return;
        WorkplaceMaintenanceRunBudget budget =
                new WorkplaceMaintenanceRunBudget(clock, maxBatches, maxRunMillis);
        long removed = 0;
        try {
            while (budget.tryStartBatch()) {
                int count = repository.deleteExpiredWorkPlans(batchSize);
                if (count < 0 || count > batchSize) {
                    throw new IllegalStateException(
                            "collaboration retention returned an invalid batch size of " + count);
                }
                removed = Math.addExact(removed, count);
                metrics.processed("collaboration-retention", "work-plan", count);
                if (count < batchSize) break;
            }
            OffsetDateTime observedAt = OffsetDateTime.now(clock);
            WorkplaceExperienceCollaborationRepository.RetentionBacklog backlog =
                    repository.retentionBacklog();
            metrics.collaboration(backlog, observedAt);
            metrics.run("collaboration-retention",
                    backlog.workPlans() == 0 ? "drained" : "budget-exhausted");
            if (backlog.workPlans() > 0) {
                log.warn(
                        "Native Workplace work-plan retention stopped after {} batches with {} "
                                + "eligible plans remaining",
                        budget.startedBatches(), backlog.workPlans());
            }
            if (removed > 0) {
                log.info(
                        "Removed {} expired native Workplace work plans in {} batches",
                        removed, budget.startedBatches());
            }
        } catch (RuntimeException exception) {
            metrics.run("collaboration-retention", "failed");
            log.error("Native Workplace work-plan privacy maintenance failed", exception);
        }
    }
}
