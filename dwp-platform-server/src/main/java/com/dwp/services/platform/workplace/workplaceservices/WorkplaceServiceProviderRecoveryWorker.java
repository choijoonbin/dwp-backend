package com.dwp.services.platform.workplace.workplaceservices;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/** Bounded global recovery; every provider action performed here is status lookup only. */
@Component
class WorkplaceServiceProviderRecoveryWorker {
    private static final Logger log =
            LoggerFactory.getLogger(WorkplaceServiceProviderRecoveryWorker.class);

    private final WorkplaceServicesRepository services;
    private final WorkplaceServiceOperationsRepository operations;
    private final WorkplaceServiceLineAdjustmentService adjustments;
    private final WorkplaceServiceOperationsService operationService;
    private final boolean enabled;
    private final int batchSize;
    private final Clock clock;

    @Autowired
    WorkplaceServiceProviderRecoveryWorker(
            WorkplaceServicesRepository services,
            WorkplaceServiceOperationsRepository operations,
            WorkplaceServiceLineAdjustmentService adjustments,
            WorkplaceServiceOperationsService operationService,
            @Value("${dwp.workplace.services.provider-worker.enabled:true}") boolean enabled,
            @Value("${dwp.workplace.services.provider-worker.batch-size:50}") int batchSize) {
        this(services, operations, adjustments, operationService, enabled, batchSize,
                Clock.systemUTC());
    }

    WorkplaceServiceProviderRecoveryWorker(
            WorkplaceServicesRepository services,
            WorkplaceServiceOperationsRepository operations,
            WorkplaceServiceLineAdjustmentService adjustments,
            WorkplaceServiceOperationsService operationService,
            boolean enabled, int batchSize, Clock clock) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("batchSize must be between 1 and 500");
        }
        this.services = services;
        this.operations = operations;
        this.adjustments = adjustments;
        this.operationService = operationService;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString =
            "${dwp.workplace.services.provider-worker.poll-delay-ms:5000}")
    void scheduledRun() {
        if (!enabled) return;
        processPending();
    }

    int processPending() {
        int processed = 0;
        for (var adjustment : services.pendingLineAdjustments(batchSize)) {
            try {
                if (!services.claimLineAdjustmentRecovery(adjustment.tenantId(),
                        adjustment.adjustmentId(), OffsetDateTime.now(clock))) continue;
                adjustments.recoverPending(adjustment);
                processed++;
            } catch (RuntimeException racedOrUnavailable) {
                log.warn("Service provider adjustment recovery did not complete for adjustmentId={}",
                        adjustment.adjustmentId());
            }
        }
        int remaining = Math.max(0, batchSize - processed);
        if (remaining == 0) return processed;
        for (var grant : operations.pendingAccessGrants(remaining)) {
            try {
                if (!operations.claimAccessGrantRecovery(grant.tenantId(), grant.grantId(),
                        OffsetDateTime.now(clock))) continue;
                if (operationService.recoverPendingGrant(grant)) processed++;
            } catch (RuntimeException racedOrUnavailable) {
                log.warn("Service credential recovery did not complete for grantId={}",
                        grant.grantId());
            }
        }
        return processed;
    }
}
