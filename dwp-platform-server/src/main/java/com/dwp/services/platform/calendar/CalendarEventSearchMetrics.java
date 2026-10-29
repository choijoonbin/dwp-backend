package com.dwp.services.platform.calendar;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
final class CalendarEventSearchMetrics {

    private final Counter success;
    private final Counter rejected;
    private final Counter budgetExceeded;
    private final Counter projected;
    private final Counter matches;
    private final Counter databasePages;
    private final Timer duration;

    CalendarEventSearchMetrics(MeterRegistry registry) {
        success = registry.counter("dwp.calendar.events.search.success");
        rejected = registry.counter("dwp.calendar.events.search.rejected");
        budgetExceeded = registry.counter("dwp.calendar.events.search.budget.exceeded");
        projected = registry.counter("dwp.calendar.events.search.projected");
        matches = registry.counter("dwp.calendar.events.search.matches");
        databasePages = registry.counter("dwp.calendar.events.search.database.pages");
        duration = registry.timer("dwp.calendar.events.search.duration");
    }

    void success(int projectedCount, int matchCount, int queryPages, long elapsedNanos) {
        success.increment();
        projected.increment(projectedCount);
        matches.increment(matchCount);
        databasePages.increment(queryPages);
        duration.record(Duration.ofNanos(Math.max(0L, elapsedNanos)));
    }

    void rejected(long elapsedNanos) {
        rejected.increment();
        duration.record(Duration.ofNanos(Math.max(0L, elapsedNanos)));
    }

    void budgetExceeded() {
        budgetExceeded.increment();
    }
}
