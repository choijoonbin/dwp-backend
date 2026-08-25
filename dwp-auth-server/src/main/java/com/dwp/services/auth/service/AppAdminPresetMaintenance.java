package com.dwp.services.auth.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expires the whole preset aggregate rather than leaving orphan authority. */
@Component
public class AppAdminPresetMaintenance {

    private static final int BATCH_SIZE = 250;
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final AppAdminPresetService service;

    public AppAdminPresetMaintenance(AppAdminPresetService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${dwp.auth.app-admin-preset.expiry-interval-ms:60000}")
    public void expireTimeBoundPresets() {
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            if (service.expireDueAssignments(BATCH_SIZE) < BATCH_SIZE) return;
        }
    }
}
