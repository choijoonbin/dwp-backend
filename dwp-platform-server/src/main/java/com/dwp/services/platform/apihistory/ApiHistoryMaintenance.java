package com.dwp.services.platform.apihistory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ApiHistoryMaintenance {

    private static final Logger log = LoggerFactory.getLogger(ApiHistoryMaintenance.class);

    private final ApiHistoryService service;

    public ApiHistoryMaintenance(ApiHistoryService service) {
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        maintain();
    }

    @Scheduled(cron = "${dwp.platform.api-history.maintenance-cron:0 17 2 * * *}")
    public void maintain() {
        int droppedPartitions = service.maintainPartitions();
        if (droppedPartitions > 0) {
            log.info("API history retention removed {} expired partition(s)", droppedPartitions);
        }
    }
}
