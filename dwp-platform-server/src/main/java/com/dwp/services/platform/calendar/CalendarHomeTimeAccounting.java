package com.dwp.services.platform.calendar;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

final class CalendarHomeTimeAccounting {

    private CalendarHomeTimeAccounting() {
    }

    // Preserve per-occurrence workload semantics (not a free/busy union). Reporting
    // boundaries must be local-date starts so DST days need not be 24 hours long.
    static int minutes(
            List<CalendarDtos.EventSummary> events,
            CalendarTypes.EventType type,
            OffsetDateTime from,
            OffsetDateTime to) {
        return events.stream().filter(event -> event.type() == type)
                .mapToInt(event -> {
                    OffsetDateTime start = event.startsAt().isBefore(from) ? from : event.startsAt();
                    OffsetDateTime end = event.endsAt().isAfter(to) ? to : event.endsAt();
                    return end.isAfter(start) ? (int) Duration.between(start, end).toMinutes() : 0;
                })
                .sum();
    }
}
