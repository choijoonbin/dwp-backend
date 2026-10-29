package com.dwp.services.platform.calendar;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
class CalendarReminderMetrics {

    private final Counter claimed;
    private final Counter emitted;
    private final Counter duplicates;
    private final Counter failures;
    private final Counter purged;
    private final Timer duration;

    CalendarReminderMetrics(MeterRegistry registry) {
        claimed = registry.counter("dwp.calendar.reminder.claimed");
        emitted = registry.counter("dwp.calendar.reminder.emitted");
        duplicates = registry.counter("dwp.calendar.reminder.duplicates");
        failures = registry.counter("dwp.calendar.reminder.failures");
        purged = registry.counter("dwp.calendar.reminder.retention.purged");
        duration = registry.timer("dwp.calendar.reminder.worker.duration");
    }

    void batch(CalendarReminderTransactions.BatchResult result, long elapsedNanos) {
        claimed.increment(result.claimed());
        emitted.increment(result.emitted());
        duplicates.increment(result.duplicates());
        duration.record(Duration.ofNanos(Math.max(0L, elapsedNanos)));
    }

    void failure(long elapsedNanos) {
        failures.increment();
        duration.record(Duration.ofNanos(Math.max(0L, elapsedNanos)));
    }

    void purged(int count) {
        if (count > 0) purged.increment(count);
    }
}
