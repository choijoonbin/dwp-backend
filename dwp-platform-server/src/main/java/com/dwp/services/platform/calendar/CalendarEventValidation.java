package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
    private final CalendarOccurrenceProjector occurrenceProjector;
    private final CalendarSchedulingHorizon schedulingHorizon;

    CalendarEventValidation(
            CalendarRepository repository,
            CalendarOccurrenceProjector occurrenceProjector,
            CalendarSchedulingHorizon schedulingHorizon) {
        this.repository = repository;
        this.occurrenceProjector = occurrenceProjector;
        this.schedulingHorizon = schedulingHorizon;
    }

    CalendarRepository.PolicyRow validateEvent(
            Long tenantId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            EventType type,
            String description,
            RecurrencePattern recurrence,
            LocalDate recurrenceUntil,
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
        if (recurrence == RecurrencePattern.NONE && recurrenceUntil != null) {
            throw invalid("A recurrence end date requires a recurrence pattern.");
        }
        LocalDate localStart = startsAt.atZoneSameInstant(eventZone).toLocalDate();
        if (recurrenceUntil != null && recurrenceUntil.isBefore(localStart)) {
            throw invalid("The recurrence end date cannot precede the first event.");
        }
        if (!horizon.contains(recurrenceUntil)) {
            throw invalid("The recurrence end date exceeds the advance booking policy.");
        }
        return policy;
    }

    CalendarRepository.ResourceRow validateResource(
            Long tenantId,
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID excludingEventId,
            String timeZone,
            RecurrencePattern recurrence,
            int recurrenceInterval,
            LocalDate recurrenceUntil,
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
        if (recurrence != RecurrencePattern.NONE && recurrenceUntil == null) {
            throw invalid("Recurring resource reservations require an end date.");
        }
        repository.lockResource(tenantId, resourceId);
        for (BookingWindow occurrence : bookingWindows(
                startsAt, endsAt, timeZone, recurrence, recurrenceInterval, recurrenceUntil)) {
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
            RecurrencePattern recurrence,
            int recurrenceInterval,
            LocalDate recurrenceUntil) {
        List<BookingWindow> result = new ArrayList<>();
        Duration duration = Duration.between(startsAt, endsAt);
        OffsetDateTime current = startsAt;
        LocalDate lastDate = recurrenceUntil == null
                ? startsAt.atZoneSameInstant(zone(timeZone)).toLocalDate()
                : recurrenceUntil;
        int guard = 0;
        while (!current.atZoneSameInstant(zone(timeZone)).toLocalDate().isAfter(lastDate)
                && guard++ < MAX_OCCURRENCES) {
            result.add(new BookingWindow(current, current.plus(duration)));
            if (recurrence == RecurrencePattern.NONE) break;
            current = occurrenceProjector.increment(
                    current, recurrence, recurrenceInterval, timeZone);
        }
        if (result.size() >= MAX_OCCURRENCES) {
            throw invalid("The recurring reservation exceeds the scheduling policy.");
        }
        return result;
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
