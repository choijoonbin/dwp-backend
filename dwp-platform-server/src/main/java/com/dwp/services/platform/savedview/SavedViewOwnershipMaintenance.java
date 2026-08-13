package com.dwp.services.platform.savedview;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class SavedViewOwnershipMaintenance {

    private final SavedViewService service;

    public SavedViewOwnershipMaintenance(SavedViewService service) {
        this.service = service;
    }

    @Scheduled(cron = "${dwp.platform.saved-view.retention-cron:0 */15 * * * *}")
    public void archiveExpiredOrphans() {
        service.archiveExpiredOrphans(OffsetDateTime.now());
    }
}
