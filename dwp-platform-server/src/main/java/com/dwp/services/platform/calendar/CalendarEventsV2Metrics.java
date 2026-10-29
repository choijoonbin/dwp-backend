package com.dwp.services.platform.calendar;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
final class CalendarEventsV2Metrics {

    private final Counter queries;
    private final Counter rows;
    private final Counter databasePages;
    private final Counter pages;
    private final Counter rejected;
    private final Timer duration;

    CalendarEventsV2Metrics(MeterRegistry registry) {
        queries = registry.counter("dwp.calendar.events.v2.queries");
        rows = registry.counter("dwp.calendar.events.v2.rows");
        databasePages = registry.counter("dwp.calendar.events.v2.database.pages");
        pages = registry.counter("dwp.calendar.events.v2.pages");
        rejected = registry.counter("dwp.calendar.events.v2.rejected");
        duration = registry.timer("dwp.calendar.events.v2.duration");
    }

    void success(int rowCount, int queryPages, long elapsedNanos) {
        queries.increment();
        rows.increment(rowCount);
        databasePages.increment(queryPages);
        pages.increment();
        duration.record(Duration.ofNanos(Math.max(0L, elapsedNanos)));
    }

    void rejected(long elapsedNanos) {
        rejected.increment();
        duration.record(Duration.ofNanos(Math.max(0L, elapsedNanos)));
    }
}
