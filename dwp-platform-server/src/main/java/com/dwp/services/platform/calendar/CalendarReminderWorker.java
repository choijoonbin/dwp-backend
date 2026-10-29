package com.dwp.services.platform.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
class CalendarReminderWorker {

    private static final Logger log = LoggerFactory.getLogger(CalendarReminderWorker.class);

    private final CalendarReminderTransactions transactions;
    private final CalendarReminderMetrics metrics;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;

    @Autowired
    CalendarReminderWorker(
            CalendarReminderTransactions transactions,
            CalendarReminderMetrics metrics,
            @Value("${dwp.platform.calendar.reminders.enabled:true}") boolean enabled,
            @Value("${dwp.platform.calendar.reminders.batch-size:100}") int batchSize) {
        this(transactions, metrics, Clock.systemUTC(), enabled, batchSize);
    }

    CalendarReminderWorker(
            CalendarReminderTransactions transactions,
            CalendarReminderMetrics metrics,
            Clock clock,
            boolean enabled,
            int batchSize) {
        this.transactions = transactions;
        this.metrics = metrics;
        this.clock = clock;
        this.enabled = enabled;
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException(
                    "Calendar reminder batch size must be between 1 and 500.");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString =
            "${dwp.platform.calendar.reminders.poll-delay-ms:30000}")
    void publishDue() {
        if (!enabled) return;
        long started = System.nanoTime();
        try {
            var result = transactions.publishDue(
                    OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), batchSize);
            metrics.batch(result, System.nanoTime() - started);
        } catch (RuntimeException exception) {
            metrics.failure(System.nanoTime() - started);
            log.error("Calendar reminder polling failed", exception);
        }
    }
}
