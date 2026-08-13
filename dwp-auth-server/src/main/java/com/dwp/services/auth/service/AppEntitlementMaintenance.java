package com.dwp.services.auth.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AppEntitlementMaintenance {

    private static final int BATCH_SIZE = 250;
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final AppEntitlementService service;

    public AppEntitlementMaintenance(AppEntitlementService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${dwp.auth.app-entitlement.expiry-interval-ms:60000}")
    public void expireTimeBoundEntitlements() {
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            if (service.expireDue(BATCH_SIZE) < BATCH_SIZE) return;
        }
    }
}
