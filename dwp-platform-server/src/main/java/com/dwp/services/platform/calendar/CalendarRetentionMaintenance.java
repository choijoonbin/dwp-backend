package com.dwp.services.platform.calendar;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class CalendarRetentionMaintenance {

    private final CalendarRetentionRepository repository;

    CalendarRetentionMaintenance(CalendarRetentionRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "${dwp.platform.calendar.retention-cron:0 29 * * * *}")
    @Transactional
    public void purgeExpiredEvents() {
        repository.purgeExpiredEvents();
    }
}
