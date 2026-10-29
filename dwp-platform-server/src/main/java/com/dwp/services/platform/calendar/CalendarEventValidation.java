package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.EventType;
import static com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern;
import static com.dwp.services.platform.calendar.CalendarTypes.ResourceState;

/**
 * Owns event and resource scheduling validation while the transactional command boundary remains
 * in {@link CalendarService}.
 */
final class CalendarEventValidation {

    private static final int MAX_OCCURRENCES = 4000;
    private static final Duration MAX_QUERY_SPAN = Duration.ofDays(370);

    private final CalendarRepository repository;
    private final CalendarSchedulingHorizon schedulingHorizon;
    private final CalendarRecurrenceEngine recurrence = new CalendarRecurrenceEngine();

    CalendarEventValidation(
            CalendarRepository repository,
            CalendarOccurrenceProjector occurrenceProjector,
            CalendarSchedulingHorizon schedulingHorizon) {
        this.repository = repository;
        this.schedulingHorizon = schedulingHorizon;
    }

    CalendarRepository.PolicyRow validateEvent(
            Long tenantId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            EventType type,
            String description,
            RecurrencePattern pattern,
            LocalDate recurrenceUntil,
            List<CalendarDtos.AttendeeInput> attendees) {
        return validateEvent(
                tenantId, startsAt, endsAt, timeZone, type, description,
                pattern, 1, recurrenceUntil, null, List.of(), attendees);
    }

    CalendarRepository.PolicyRow validateEvent(
            Long tenantId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            EventType type,
            String description,
            RecurrencePattern recurrencePattern,
            int recurrenceInterval,
            LocalDate recurrenceUntil,
            Integer recurrenceCount,
            List<DayOfWeek> recurrenceDays,
            List<CalendarDtos.AttendeeInput> attendees) {
        ZoneId eventZone = zone(timeZone);
        if (!endsAt.isAfter(startsAt)) throw invalid("The event must end after it starts.");
        CalendarRepository.PolicyRow policy = repository.policy(tenantId);
        long minutes = Duration.between(startsAt, endsAt).toMinutes();
        if (minutes < policy.minimumEventMinutes() || minutes > policy.maximumEventMinutes()) {
            throw invalid("The event duration is outside the tenant scheduling policy.");
        }
        CalendarSchedulingHorizon.Horizon horizon = schedulingHorizon.evaluate(
                eventZone, policy.maximumAdvanceDays());
        if (!horizon.contains(startsAt, eventZone)) {
            throw invalid("The event is beyond the maximum advance booking window.");
        }
        if (policy.enforceMeetingAgenda() && type == EventType.MEETING
                && (description == null || description.isBlank())) {
            throw invalid("A meeting agenda is required by tenant policy.");
        }
        if (!policy.allowExternalAttendees() && attendees.stream()
                .anyMatch(this::isExternalAttendee)) {
            throw invalid("External attendees are disabled by tenant policy.");
        }
        if (recurrencePattern == RecurrencePattern.NONE && recurrenceUntil != null) {
            throw invalid("A recurrence end date requires a recurrence pattern.");
        }
        LocalDate localStart = startsAt.atZoneSameInstant(eventZone).toLocalDate();
        if (recurrenceUntil != null && recurrenceUntil.isBefore(localStart)) {
            throw invalid("The recurrence end date cannot precede the first event.");
        }
        if (!horizon.contains(recurrenceUntil)) {
            throw invalid("The recurrence end date exceeds the advance booking policy.");
        }
        if (recurrenceCount != null) {
            CalendarRecurrenceEngine.Rule rule = rule(
                    recurrencePattern, recurrenceInterval, recurrenceUntil,
                    recurrenceCount, recurrenceDays, timeZone);
            OffsetDateTime lastOccurrence = startsAt;
            for (int ordinal = 1; ordinal < recurrenceCount; ordinal++) {
                lastOccurrence = recurrence.next(startsAt, lastOccurrence, rule);
            }
            if (!horizon.contains(lastOccurrence, eventZone)) {
                throw invalid("The recurrence count exceeds the advance booking policy.");
            }
        }
        return policy;
    }

    void validateRecurrence(
            OffsetDateTime startsAt,
            String timeZone,
            RecurrencePattern pattern,
            int interval,
            LocalDate until,
            Integer count,
            List<DayOfWeek> days) {
        try {
            recurrence.validate(startsAt, recurrence.rule(
                    pattern, interval, until, count,
                    days == null ? java.util.Set.of() : java.util.Set.copyOf(days),
                    timeZone));
            if (days != null && days.stream().distinct().count() != days.size()) {
                throw new IllegalArgumentException("Weekly BYDAY values must be unique.");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage());
        }
    }

    CalendarRepository.ResourceRow validateResource(
            Long tenantId,
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID excludingEventId,
            String timeZone,
            RecurrencePattern pattern,
            int recurrenceInterval,
            LocalDate recurrenceUntil,
            List<DayOfWeek> recurrenceDays,
            Integer recurrenceCount,
            String locale) {
        if (resourceId == null) return null;
        CalendarRepository.ResourceRow resource = repository.resource(
                        tenantId, resourceId, korean(locale))
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "The resource was not found."));
        if (resource.state() != ResourceState.AVAILABLE) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The resource is not available.");
        }
        if (repository.isWorkplaceManagedResource(tenantId, resourceId)
                && !repository.isWorkplaceResourceBookable(tenantId, resourceId)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The Workplace location for this room is not open for booking.");
        }
        if (pattern != RecurrencePattern.NONE
                && recurrenceUntil == null && recurrenceCount == null) {
            throw invalid("Recurring resource reservations require an end date or count.");
        }
        repository.lockResource(tenantId, resourceId);
        for (BookingWindow occurrence : bookingWindows(
                startsAt, endsAt, timeZone, pattern, recurrenceInterval,
                recurrenceUntil, recurrenceDays, recurrenceCount)) {
            if (repository.facilityClosureConflict(
                            tenantId, resourceId, occurrence.startsAt(), occurrence.endsAt())
                    || repository.resourceConflict(
                            tenantId, resourceId, occurrence.startsAt(), occurrence.endsAt(),
                            excludingEventId)) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The resource is unavailable for this reservation period.");
            }
        }
        return resource;
    }

    void validateRange(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw invalid("A valid date range is required.");
        }
        if (Duration.between(from, to).compareTo(MAX_QUERY_SPAN) > 0) {
            throw invalid("Calendar queries are limited to 370 days.");
        }
    }

    ZoneId zone(String value) {
        try {
            return ZoneId.of(value == null || value.isBlank() ? "Asia/Seoul" : value);
        } catch (DateTimeException exception) {
            throw invalid("The time zone is invalid.");
        }
    }

    private List<BookingWindow> bookingWindows(
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            RecurrencePattern pattern,
            int recurrenceInterval,
            LocalDate recurrenceUntil,
            List<DayOfWeek> recurrenceDays,
            Integer recurrenceCount) {
        Duration duration = Duration.between(startsAt, endsAt);
        CalendarRecurrenceEngine.Rule rule = rule(
                pattern, recurrenceInterval, recurrenceUntil,
                recurrenceCount, recurrenceDays, timeZone);
        OffsetDateTime exclusiveTo;
        if (recurrenceUntil != null) {
            exclusiveTo = recurrenceUntil.plusDays(1)
                    .atStartOfDay(rule.zone()).toOffsetDateTime();
        } else if (recurrenceCount != null) {
            OffsetDateTime last = startsAt;
            for (int ordinal = 1; ordinal < recurrenceCount; ordinal++) {
                last = recurrence.next(startsAt, last, rule);
            }
            exclusiveTo = last.plus(duration).plusNanos(1);
        } else {
            exclusiveTo = endsAt.plusNanos(1);
        }
        try {
            return recurrence.project(
                            startsAt, endsAt, rule, startsAt, exclusiveTo, MAX_OCCURRENCES)
                    .stream()
                    .map(value -> new BookingWindow(value.startsAt(), value.endsAt()))
                    .toList();
        } catch (IllegalArgumentException
                 | CalendarRecurrenceEngine.ProjectionLimitExceededException exception) {
            throw invalid("The recurring reservation exceeds the scheduling policy.");
        }
    }

    private CalendarRecurrenceEngine.Rule rule(
            RecurrencePattern pattern,
            int interval,
            LocalDate until,
            Integer count,
            List<DayOfWeek> days,
            String timeZone) {
        EnumSet<DayOfWeek> normalized = days == null || days.isEmpty()
                ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(days);
        return recurrence.rule(pattern, interval, until, count, normalized, timeZone);
    }

    private boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private boolean isExternalAttendee(CalendarDtos.AttendeeInput attendee) {
        return attendee.userId() == null && attendee.personPublicId() == null;
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private record BookingWindow(OffsetDateTime startsAt, OffsetDateTime endsAt) {
    }
}
