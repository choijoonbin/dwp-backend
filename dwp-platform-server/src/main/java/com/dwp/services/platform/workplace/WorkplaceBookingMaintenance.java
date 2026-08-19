package com.dwp.services.platform.workplace;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkplaceBookingMaintenance {

    private final WorkplaceService service;

    public WorkplaceBookingMaintenance(WorkplaceService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${dwp.platform.workplace.no-show-sweep-ms:60000}")
    public void maintainBookingLifecycle() {
        service.maintainBookingLifecycle();
    }
}
