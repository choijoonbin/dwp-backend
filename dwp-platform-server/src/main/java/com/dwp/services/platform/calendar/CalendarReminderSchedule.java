package com.dwp.services.platform.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern;

final class CalendarReminderSchedule {

    private static final CalendarRecurrenceEngine RECURRENCE =
            new CalendarRecurrenceEngine();

    private CalendarReminderSchedule() {
    }

    static Occurrence next(
            CalendarRepository.EventRow event,
            int offsetMinutes,
            OffsetDateTime notBefore) {
        return next(new EventSchedule(
                event.startsAt(), event.timeZone(), event.recurrence(),
                event.recurrenceInterval(), event.recurrenceUntil(),
                event.recurrenceDays(), event.recurrenceCount()),
                offsetMinutes, notBefore);
    }

    static Occurrence next(
            EventSchedule event,
            int offsetMinutes,
            OffsetDateTime notBefore) {
        if (event == null || event.startsAt() == null || event.timeZone() == null
                || event.recurrence() == null || event.recurrenceInterval() < 1
                || offsetMinutes < 0 || offsetMinutes > 40320 || notBefore == null) {
            throw new IllegalArgumentException("A valid Calendar reminder schedule is required.");
        }
        Set<DayOfWeek> days = event.recurrenceDays() == null
                ? Set.of() : Set.copyOf(event.recurrenceDays());
        CalendarRecurrenceEngine.Rule rule = RECURRENCE.rule(
                event.recurrence(), event.recurrenceInterval(),
                event.recurrenceUntil(), event.recurrenceCount(), days, event.timeZone());
        RECURRENCE.validate(event.startsAt(), rule);
        OffsetDateTime startsAt = event.startsAt();
        for (int ordinal = 1;
                ordinal <= CalendarRecurrenceEngine.MAX_SERIES_OCCURRENCES;
                ordinal++) {
            if (event.recurrenceCount() != null && ordinal > event.recurrenceCount()) return null;
            if (event.recurrenceUntil() != null
                    && startsAt.atZoneSameInstant(rule.zone()).toLocalDate()
                            .isAfter(event.recurrenceUntil())) return null;
            OffsetDateTime dueAt = startsAt.minusMinutes(offsetMinutes);
            if (!dueAt.isBefore(notBefore)) return new Occurrence(startsAt, dueAt);
            if (event.recurrence() == RecurrencePattern.NONE) return null;
            startsAt = RECURRENCE.next(event.startsAt(), startsAt, rule);
        }
        throw new CalendarRecurrenceEngine.ProjectionLimitExceededException(
                CalendarRecurrenceEngine.MAX_SERIES_OCCURRENCES);
    }

    record EventSchedule(
            OffsetDateTime startsAt,
            String timeZone,
            RecurrencePattern recurrence,
            int recurrenceInterval,
            LocalDate recurrenceUntil,
            List<DayOfWeek> recurrenceDays,
            Integer recurrenceCount) {

        EventSchedule(
                OffsetDateTime startsAt,
                String timeZone,
                RecurrencePattern recurrence,
                int recurrenceInterval,
                LocalDate recurrenceUntil) {
            this(startsAt, timeZone, recurrence, recurrenceInterval,
                    recurrenceUntil, List.of(), null);
        }

        EventSchedule {
            recurrenceDays = recurrenceDays == null
                    ? List.of() : recurrenceDays.stream().distinct().sorted().toList();
        }
    }

    record Occurrence(OffsetDateTime startsAt, OffsetDateTime dueAt) {
    }
}
