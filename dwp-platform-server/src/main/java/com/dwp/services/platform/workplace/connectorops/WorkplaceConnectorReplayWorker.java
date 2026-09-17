package com.dwp.services.platform.workplace.connectorops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsRepository.ReplayJobRow;

/** Dispatches queued jobs once and reconciles every non-terminal provider receipt by lookup. */
@Component
class WorkplaceConnectorReplayWorker {
    private static final Logger log = LoggerFactory.getLogger(WorkplaceConnectorReplayWorker.class);

    private final WorkplaceConnectorOpsRepository repository;
    private final WorkplaceConnectorReplayCoordinator coordinator;
    private final boolean enabled;
    private final int batchSize;

    WorkplaceConnectorReplayWorker(
            WorkplaceConnectorOpsRepository repository,
            WorkplaceConnectorReplayCoordinator coordinator,
            @Value("${dwp.workplace.connector-runtime.replay-worker-enabled:true}") boolean enabled,
            @Value("${dwp.workplace.connector-runtime.replay-worker-batch-size:20}") int batchSize) {
        if (batchSize < 1 || batchSize > 200) {
            throw new IllegalArgumentException("batchSize must be between 1 and 200");
        }
        this.repository = repository;
        this.coordinator = coordinator;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${dwp.workplace.connector-runtime.replay-worker-delay-ms:2000}")
    void dispatchQueued() {
        if (!enabled) return;
        for (ReplayJobRow job : repository.queuedReplays(batchSize)) {
            try {
                coordinator.dispatch(job.tenantId(), job.kind(), job.jobId());
            } catch (RuntimeException exception) {
                // A competing worker can win the optimistic state transition. Provider failures
                // are persisted as RESULT_UNKNOWN by the service and are never retried here.
                log.warn("Workplace connector replay dispatch did not complete for jobId={}",
                        job.jobId());
            }
        }
        for (ReplayJobRow job : repository.reconciliationCandidates(batchSize)) {
            try {
                coordinator.reconcile(job.tenantId(), job.kind(), job.jobId());
            } catch (RuntimeException exception) {
                log.warn("Workplace connector replay reconciliation did not complete for jobId={}",
                        job.jobId());
            }
        }
    }
}
