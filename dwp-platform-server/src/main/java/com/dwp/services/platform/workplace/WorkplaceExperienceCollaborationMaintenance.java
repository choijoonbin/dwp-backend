package com.dwp.services.platform.workplace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Native plans reuse the existing Workplace privacy policy and maintenance schedule. */
@Component
class WorkplaceExperienceCollaborationMaintenance {
    private static final Logger log = LoggerFactory.getLogger(WorkplaceExperienceCollaborationMaintenance.class);
    private final WorkplaceExperienceCollaborationRepository repository;
    private final boolean enabled;
    private final int batchSize;

    WorkplaceExperienceCollaborationMaintenance(WorkplaceExperienceCollaborationRepository repository,
            @Value("${dwp.platform.workplace.privacy-maintenance.enabled:true}") boolean enabled,
            @Value("${dwp.platform.workplace.privacy-maintenance.batch-size:500}") int batchSize) {
        if (batchSize < 1 || batchSize > 5000) throw new IllegalArgumentException("Invalid privacy maintenance batch size");
        this.repository = repository;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${dwp.platform.workplace.privacy-maintenance.cron:0 23 3 * * *}")
    void purgeExpiredPlans() {
        if (!enabled) return;
        try {
            int removed = repository.deleteExpiredWorkPlans(batchSize);
            if (removed > 0) log.info("Removed {} expired native Workplace work plans", removed);
        } catch (RuntimeException exception) {
            log.error("Native Workplace work-plan privacy maintenance failed", exception);
        }
    }
}
