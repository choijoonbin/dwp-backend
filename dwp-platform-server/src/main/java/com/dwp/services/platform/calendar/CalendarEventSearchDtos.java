package com.dwp.services.platform.calendar;

import java.time.OffsetDateTime;
import java.util.List;

final class CalendarEventSearchDtos {

    private CalendarEventSearchDtos() {
    }

    record SearchPage(
            List<CalendarDtos.EventSummary> items,
            String nextCursor,
            boolean hasMore,
            String normalizedQuery,
            Range range,
            OffsetDateTime generatedAt) {

        SearchPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record Range(
            OffsetDateTime from,
            OffsetDateTime to,
            String timeZone) {
    }
}
