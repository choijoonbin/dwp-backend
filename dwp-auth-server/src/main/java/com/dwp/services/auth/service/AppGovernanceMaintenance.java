package com.dwp.services.auth.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AppGovernanceMaintenance {

    private static final int BATCH_SIZE = 250;
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final AppGovernanceService service;

    public AppGovernanceMaintenance(AppGovernanceService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${dwp.auth.app-governance.expiry-interval-ms:60000}")
    public void expireTimeBoundResponsibilities() {
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            if (service.expireDueAssignments(BATCH_SIZE) < BATCH_SIZE) return;
        }
    }
}
