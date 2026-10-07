package com.dwp.services.platform.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

public final class CalendarInsightsDtos {

    private CalendarInsightsDtos() {
    }

    public record Metrics(
            int eventCount,
            int meetingMinutes,
            int focusMinutes,
            int protectedFocusMinutes,
            int focusQualityPercent,
            int afterHoursMinutes,
            int noMeetingDays,
            int fragmentedDays,
            int conflictCount) {
    }

    public record Week(
            LocalDate weekStart,
            LocalDate weekEnd,
            Metrics metrics) {
    }

    public record Response(
            int weeks,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate previousPeriodStart,
            LocalDate previousPeriodEnd,
            String timeZone,
            List<DayOfWeek> workingDays,
            LocalTime workingDayStart,
            LocalTime workingDayEnd,
            Metrics current,
            Metrics previous,
            List<Week> trend,
            String source,
            String completeness,
            OffsetDateTime generatedAt) {

        public Response {
            workingDays = List.copyOf(workingDays);
            trend = List.copyOf(trend);
        }
    }
}
