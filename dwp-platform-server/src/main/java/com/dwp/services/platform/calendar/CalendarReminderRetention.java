package com.dwp.services.platform.calendar;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
class CalendarReminderRetention {

    private final CalendarReminderRepository repository;
    private final CalendarReminderMetrics metrics;
    private final Clock clock;
    private final int retentionDays;
    private final int batchSize;
    private final int maxRounds;

    @Autowired
    CalendarReminderRetention(
            CalendarReminderRepository repository,
            CalendarReminderMetrics metrics,
            @Value("${dwp.platform.calendar.reminders.retention-days:90}") int retentionDays,
            @Value("${dwp.platform.calendar.reminders.retention-batch-size:500}") int batchSize,
            @Value("${dwp.platform.calendar.reminders.retention-max-rounds:10}") int maxRounds) {
        this(repository, metrics, Clock.systemUTC(), retentionDays, batchSize, maxRounds);
    }

    CalendarReminderRetention(
            CalendarReminderRepository repository,
            CalendarReminderMetrics metrics,
            Clock clock,
            int retentionDays,
            int batchSize,
            int maxRounds) {
        this.repository = repository;
        this.metrics = metrics;
        this.clock = clock;
        if (retentionDays < 1 || retentionDays > 3650) {
            throw new IllegalArgumentException(
                    "Calendar reminder retention must be between 1 and 3650 days.");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException(
                    "Calendar reminder retention batch must be between 1 and 1000.");
        }
        if (maxRounds < 1 || maxRounds > 100) {
            throw new IllegalArgumentException(
                    "Calendar reminder retention rounds must be between 1 and 100.");
        }
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.maxRounds = maxRounds;
    }

    @Scheduled(cron = "${dwp.platform.calendar.reminders.retention-cron:0 41 * * * *}")
    @Transactional
    void purge() {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .minusDays(retentionDays);
        int deleted = 0;
        for (int round = 0; round < maxRounds; round++) {
            int ledger = repository.purgeDeliveryLedger(cutoff, batchSize);
            int sets = repository.purgeInvalidSets(cutoff, batchSize);
            deleted += ledger + sets;
            if (ledger < batchSize && sets < batchSize) break;
        }
        metrics.purged(deleted);
    }
}
