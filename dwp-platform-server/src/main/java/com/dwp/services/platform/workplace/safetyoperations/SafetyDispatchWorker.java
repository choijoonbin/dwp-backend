package com.dwp.services.platform.workplace.safetyoperations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class SafetyDispatchWorker {
    private static final Logger log = LoggerFactory.getLogger(SafetyDispatchWorker.class);
    private final SafetyIncidentRepository repository;
    private final SafetyDispatchRecoveryRepository recovery;
    private final SafetyDispatchService service;
    private final boolean enabled;
    private final int batchSize;
    private final java.time.Duration processingTimeout;

    SafetyDispatchWorker(
            SafetyIncidentRepository repository,
            SafetyDispatchRecoveryRepository recovery,
            SafetyDispatchService service,
            @Value("${dwp.workplace.safety.dispatch-worker-enabled:true}") boolean enabled,
            @Value("${dwp.workplace.safety.dispatch-worker-batch-size:50}") int batchSize,
            @Value("${dwp.workplace.safety.dispatch-processing-timeout:PT2M}")
            java.time.Duration processingTimeout) {
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("batchSize");
        if (processingTimeout.isNegative() || processingTimeout.isZero()) {
            throw new IllegalArgumentException("processingTimeout");
        }
        this.repository = repository;
        this.recovery = recovery;
        this.service = service;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.processingTimeout = processingTimeout;
    }

    @Scheduled(fixedDelayString = "${dwp.workplace.safety.dispatch-worker-delay-ms:2000}")
    void dispatchQueued() {
        if (!enabled) return;
        var now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        recovery.recoverStaleDispatches(batchSize, now.minus(processingTimeout), now);
        for (var work : repository.dispatchable(batchSize, now.minus(processingTimeout))) {
            try { service.dispatch(work); }
            catch (RuntimeException failure) {
                log.warn("Safety dispatch did not complete for attemptId={}", work.attemptId(), failure);
            }
        }
        now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        for (var work : recovery.pending(batchSize, now)) {
            try { service.reconcile(work); }
            catch (RuntimeException failure) {
                log.warn("Safety provider status lookup did not complete for attemptId={}",
                        work.attemptId(), failure);
            }
        }
    }
}
