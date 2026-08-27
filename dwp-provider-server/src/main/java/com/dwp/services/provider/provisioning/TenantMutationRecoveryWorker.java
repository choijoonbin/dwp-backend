package com.dwp.services.provider.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TenantMutationRecoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(TenantMutationRecoveryWorker.class);

    private final TenantMutationOrchestrator orchestrator;
    private final boolean enabled;
    private final int batchSize;

    public TenantMutationRecoveryWorker(
            TenantMutationOrchestrator orchestrator,
            @Value("${dwp.provider.tenant-mutation.recovery-enabled:true}") boolean enabled,
            @Value("${dwp.provider.tenant-mutation.recovery-batch-size:25}") int batchSize) {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Invalid tenant mutation recovery batch size.");
        }
        this.orchestrator = orchestrator;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${dwp.provider.tenant-mutation.recovery-delay-ms:1000}")
    public void pollSafely() {
        if (!enabled) return;
        for (int index = 0; index < batchSize; index++) {
            try {
                orchestrator.recoverOne();
            } catch (RuntimeException failure) {
                log.error("Durable tenant mutation recovery failed", failure);
                return;
            }
        }
    }
}
