package com.dwp.services.platform.calendar;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Low-cardinality operational signals for poll commands and room contention. */
@Component
final class CalendarSchedulingPollMetrics {

    private final Counter created;
    private final Counter opened;
    private final Counter voted;
    private final Counter cancelled;
    private final Counter confirmed;
    private final Counter read;
    private final Counter roomUnavailable;
    private final Counter expired;
    private final Counter holdsExpired;
    private final Counter purged;

    CalendarSchedulingPollMetrics(MeterRegistry registry) {
        created = registry.counter("dwp.calendar.poll.commands", "command", "create");
        opened = registry.counter("dwp.calendar.poll.commands", "command", "open");
        voted = registry.counter("dwp.calendar.poll.commands", "command", "vote");
        cancelled = registry.counter("dwp.calendar.poll.commands", "command", "cancel");
        confirmed = registry.counter("dwp.calendar.poll.commands", "command", "confirm");
        read = registry.counter("dwp.calendar.poll.reads");
        roomUnavailable = registry.counter("dwp.calendar.poll.room.unavailable");
        expired = registry.counter("dwp.calendar.poll.expired");
        holdsExpired = registry.counter("dwp.calendar.poll.holds.expired");
        purged = registry.counter("dwp.calendar.poll.retention.purged");
    }

    void created() { created.increment(); }

    void opened() { opened.increment(); }

    void voted() { voted.increment(); }

    void cancelled() { cancelled.increment(); }

    void confirmed() { confirmed.increment(); }

    void read(int count) { read.increment(Math.max(0, count)); }

    void roomUnavailable() { roomUnavailable.increment(); }

    void expired(int count) { expired.increment(Math.max(0, count)); }

    void holdsExpired(int count) { holdsExpired.increment(Math.max(0, count)); }

    void purged(int count) { purged.increment(Math.max(0, count)); }
}
