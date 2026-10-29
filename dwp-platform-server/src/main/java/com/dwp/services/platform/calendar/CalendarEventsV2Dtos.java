package com.dwp.services.platform.calendar;

import java.time.OffsetDateTime;
import java.util.List;

final class CalendarEventsV2Dtos {

    private CalendarEventsV2Dtos() {
    }

    record EventPage(
            List<CalendarDtos.EventSummary> items,
            String nextCursor,
            boolean hasMore,
            Range range,
            OffsetDateTime generatedAt) {
    }

    record Range(OffsetDateTime from, OffsetDateTime to, String timeZone) {
    }
}
