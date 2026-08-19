package com.dwp.services.platform.workplace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
class WorkplacePrivacyMaintenance {

    private static final Logger log = LoggerFactory.getLogger(WorkplacePrivacyMaintenance.class);

    private final WorkplacePrivacyRepository repository;
    private final boolean enabled;
    private final int batchSize;
    private final int auditRetentionDays;

    WorkplacePrivacyMaintenance(
            WorkplacePrivacyRepository repository,
            @Value("${dwp.platform.workplace.privacy-maintenance.enabled:true}") boolean enabled,
            @Value("${dwp.platform.workplace.privacy-maintenance.batch-size:500}") int batchSize,
            @Value("${dwp.platform.workplace.privacy-maintenance.audit-retention-days:2555}")
                    int auditRetentionDays) {
        if (batchSize < 1 || batchSize > 5000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 5000");
        }
        if (auditRetentionDays < 365 || auditRetentionDays > 3650) {
            throw new IllegalArgumentException(
                    "auditRetentionDays must be between 365 and 3650");
        }
        this.repository = repository;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.auditRetentionDays = auditRetentionDays;
    }

    @Scheduled(cron = "${dwp.platform.workplace.privacy-maintenance.cron:0 23 3 * * *}")
    void anonymizeExpiredBookings() {
        if (!enabled) return;
        try {
            int anonymized = repository.anonymizeExpired(batchSize);
            int releaseWindows = repository.anonymizeExpiredReleaseWindows(batchSize);
            int auditEvents = repository.purgeExpiredAuditReplicas(
                    batchSize, OffsetDateTime.now().minusDays(auditRetentionDays));
            if (anonymized + releaseWindows + auditEvents > 0) {
                log.info(
                        "Workplace privacy maintenance anonymized {} bookings and {} release "
                                + "windows, and purged {} expired audit replicas",
                        anonymized, releaseWindows, auditEvents);
            }
        } catch (RuntimeException exception) {
            log.error("Workplace reservation privacy maintenance failed", exception);
        }
    }
}
