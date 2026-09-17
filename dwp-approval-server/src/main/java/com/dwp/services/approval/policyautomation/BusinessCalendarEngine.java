package com.dwp.services.approval.policyautomation;

import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.*;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BusinessCalendarEngine {
    private static final int MAXIMUM_SEARCH_DAYS = 3_660;

    private BusinessCalendarEngine() {
    }

    static void validate(CalendarDraft calendar) {
        if (calendar == null || calendar.calendarId() == null
                || blank(calendar.calendarKey())
                || !calendar.calendarKey().trim().toUpperCase(Locale.ROOT)
                .matches("[A-Z][A-Z0-9_.-]{2,99}")
                || blank(calendar.displayName()) || calendar.displayName().trim().length() > 200
                || calendar.lifecycle() == null || calendar.expectedVersion() < 0
                || calendar.workWeek() == null || calendar.workWeek().isEmpty()
                || calendar.workWeek().size() > 7
                || calendar.holidays() == null || calendar.holidays().size() > 2_000
                || calendar.exceptions() == null || calendar.exceptions().size() > 2_000) {
            throw PolicyAutomationRejected.invalid("Business calendar definition is invalid.");
        }
        try {
            ZoneId.of(calendar.timeZone());
        } catch (RuntimeException exception) {
            throw PolicyAutomationRejected.invalid("Business calendar time zone is invalid.");
        }
        for (Map.Entry<String, WorkHours> entry : calendar.workWeek().entrySet()) {
            try {
                DayOfWeek.valueOf(entry.getKey());
            } catch (RuntimeException exception) {
                throw PolicyAutomationRejected.invalid("Business calendar week day is invalid.");
            }
            requireHours(entry.getValue());
        }
        if (calendar.holidays().stream().map(Holiday::date).distinct().count()
                != calendar.holidays().size()
                || calendar.exceptions().stream().map(CalendarException::date).distinct().count()
                != calendar.exceptions().size()) {
            throw PolicyAutomationRejected.invalid(
                    "Business calendar dates must be unique within each collection.");
        }
        calendar.holidays().forEach(holiday -> {
            if (holiday == null || holiday.date() == null || blank(holiday.label())
                    || holiday.label().trim().length() > 160) {
                throw PolicyAutomationRejected.invalid("Business calendar holiday is invalid.");
            }
        });
        calendar.exceptions().forEach(exception -> {
            if (exception == null || exception.date() == null || blank(exception.reason())
                    || exception.reason().trim().length() > 500
                    || exception.closed() && (exception.opensAt() != null || exception.closesAt() != null)
                    || !exception.closed() && !validHours(
                    new WorkHours(exception.opensAt(), exception.closesAt()))) {
                throw PolicyAutomationRejected.invalid("Business calendar exception is invalid.");
            }
        });
    }

    static Instant addBusinessMinutes(CalendarView calendar, Instant start, long minutes) {
        if (calendar == null || calendar.lifecycle() != Lifecycle.ACTIVE
                || start == null || minutes < 0 || minutes > 5_256_000L) {
            throw PolicyAutomationRejected.invalid("Business-time calculation is invalid.");
        }
        if (minutes == 0) return start;
        ZoneId zone = ZoneId.of(calendar.timeZone());
        Set<LocalDate> holidays = calendar.holidays().stream()
                .map(Holiday::date).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<LocalDate, CalendarException> exceptions = new HashMap<>();
        calendar.exceptions().forEach(value -> exceptions.put(value.date(), value));
        ZonedDateTime cursor = start.atZone(zone);
        long remaining = minutes;
        for (int day = 0; day < MAXIMUM_SEARCH_DAYS; day++) {
            LocalDate date = cursor.toLocalDate();
            WorkHours hours = hours(calendar, date, holidays, exceptions);
            if (hours != null) {
                ZonedDateTime opens = resolve(zone, date, hours.opensAt());
                ZonedDateTime closes = resolve(zone, date, hours.closesAt());
                ZonedDateTime effective = cursor.isAfter(opens) ? cursor : opens;
                if (effective.isBefore(closes)) {
                    long available = Duration.between(effective, closes).toMinutes();
                    if (remaining <= available) return effective.plusMinutes(remaining).toInstant();
                    remaining -= available;
                }
            }
            cursor = resolve(zone, date.plusDays(1), java.time.LocalTime.MIDNIGHT);
        }
        throw PolicyAutomationRejected.unavailable(
                "Business calendar has no reachable working interval.");
    }

    private static WorkHours hours(
            CalendarView calendar,
            LocalDate date,
            Set<LocalDate> holidays,
            Map<LocalDate, CalendarException> exceptions) {
        CalendarException exception = exceptions.get(date);
        if (exception != null) {
            return exception.closed() ? null
                    : new WorkHours(exception.opensAt(), exception.closesAt());
        }
        if (holidays.contains(date)) return null;
        return calendar.workWeek().get(date.getDayOfWeek().name());
    }

    private static ZonedDateTime resolve(ZoneId zone, LocalDate date, java.time.LocalTime time) {
        LocalDateTime local = LocalDateTime.of(date, time);
        java.util.List<java.time.ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) {
            return zone.getRules().getTransition(local).getDateTimeAfter().atZone(zone);
        }
        return ZonedDateTime.ofLocal(local, zone, offsets.getFirst());
    }

    private static void requireHours(WorkHours hours) {
        if (!validHours(hours)) {
            throw PolicyAutomationRejected.invalid("Business calendar work hours are invalid.");
        }
    }

    private static boolean validHours(WorkHours hours) {
        return hours != null && hours.opensAt() != null && hours.closesAt() != null
                && hours.closesAt().isAfter(hours.opensAt());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
