package com.dwp.services.auth.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PrivilegedAccessMaintenance {

    private static final int BATCH_SIZE = 250;
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final PrivilegedAccessService service;

    public PrivilegedAccessMaintenance(PrivilegedAccessService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${dwp.auth.privileged-access.expiry-interval-ms:60000}")
    public void expireTimeBoundAccess() {
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            if (service.expireStaleBatch(BATCH_SIZE) < BATCH_SIZE) return;
        }
    }
}
