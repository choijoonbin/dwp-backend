package com.dwp.services.platform.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AppAccessRequestMaintenance {

    private static final Logger log = LoggerFactory.getLogger(AppAccessRequestMaintenance.class);

    private final WorkspaceService service;

    public AppAccessRequestMaintenance(WorkspaceService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        expireDueRequests();
    }

    @Scheduled(cron = "${dwp.platform.app-access.expiry-cron:0 * * * * *}")
    public void expireDueRequests() {
        int expired = service.expireDueAppAccessRequests();
        if (expired > 0) {
            log.info("Expired {} workspace app access request(s)", expired);
        }
    }
}
