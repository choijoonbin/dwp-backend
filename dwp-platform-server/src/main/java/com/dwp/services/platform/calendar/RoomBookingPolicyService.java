package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.EventType;
import static com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern;

@Service
class RoomBookingPolicyService {

    private static final int MAX_OCCURRENCES = 4000;

    private final CalendarRepository repository;
    private final CalendarSchedulingHorizon schedulingHorizon;

    RoomBookingPolicyService(
            CalendarRepository repository,
            CalendarSchedulingHorizon schedulingHorizon) {
        this.repository = repository;
        this.schedulingHorizon = schedulingHorizon;
    }

    void validateLockedCreate(
            Long tenantId,
            CalendarRepository.ResourceRow resource,
            CalendarRepository.PolicyRow policy,
            CalendarDtos.CreateEventRequest request) {
        validate(tenantId, resource, policy, null, booking(request));
    }

    void validateLockedUpdate(
            Long tenantId,
            CalendarRepository.ResourceRow resource,
            CalendarRepository.PolicyRow policy,
            UUID excludingEventId,
            CalendarDtos.UpdateEventRequest request) {
        validate(tenantId, resource, policy, excludingEventId, booking(request));
    }

    private void validate(
            Long tenantId,
            CalendarRepository.ResourceRow resource,
            CalendarRepository.PolicyRow policy,
            UUID excludingEventId,
            RoomBookingRequest request) {
        if (resource == null || resource.type() != CalendarTypes.ResourceType.ROOM) return;
        if (request.type() != EventType.MEETING) {
            throw invalid("Room reservations must be meeting events.");
        }
        if (request.allDay()) {
            throw invalid("Room reservations must use a specific time range.");
        }
        ZoneId resourceZone = zone(resource.timeZone());
        if (!resourceZone.equals(zone(request.timeZone()))) {
            throw invalid("The reservation time zone must match the meeting room time zone.");
        }
        List<BookingWindow> windows = bookingWindows(request, resourceZone, policy);
        for (BookingWindow window : windows) {
            if (repository.resourceConflict(
                    tenantId,
                    resource.resourceId(),
                    window.startsAt().minusMinutes(policy.defaultBufferMinutes()),
                    window.endsAt().plusMinutes(policy.defaultBufferMinutes()),
                    excludingEventId)) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The meeting room or its required booking buffer is unavailable.");
            }
        }
    }

    private List<BookingWindow> bookingWindows(
            RoomBookingRequest request,
            ZoneId resourceZone,
            CalendarRepository.PolicyRow policy) {
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw invalid("The room reservation must end after it starts.");
        }
        Duration duration = Duration.between(request.startsAt(), request.endsAt());
        if (duration.compareTo(Duration.ofMinutes(policy.minimumEventMinutes())) < 0
                || duration.compareTo(Duration.ofMinutes(policy.maximumEventMinutes())) > 0) {
            throw invalid("The room reservation duration is outside the tenant policy.");
        }
        if (request.recurrence() == RecurrencePattern.NONE && request.recurrenceUntil() != null) {
            throw invalid("A recurrence end date requires a recurrence pattern.");
        }
        if (request.recurrence() != RecurrencePattern.NONE && request.recurrenceUntil() == null) {
            throw invalid("Recurring room reservations require an end date.");
        }

        CalendarSchedulingHorizon.Horizon horizon = schedulingHorizon.evaluate(
                resourceZone, policy.maximumAdvanceDays());
        ZonedDateTime first = request.startsAt().atZoneSameInstant(resourceZone);
        LocalDate lastDate = request.recurrenceUntil() == null
                ? first.toLocalDate()
                : request.recurrenceUntil();
        if (lastDate.isBefore(first.toLocalDate()) || !horizon.contains(lastDate)) {
            throw invalid("The room reservation exceeds the advance booking policy.");
        }

        List<BookingWindow> result = new ArrayList<>();
        OffsetDateTime current = request.startsAt();
        while (!current.atZoneSameInstant(resourceZone).toLocalDate().isAfter(lastDate)) {
            if (result.size() >= MAX_OCCURRENCES) {
                throw invalid("The recurring room reservation is too large.");
            }
            BookingWindow window = new BookingWindow(current, current.plus(duration));
            validateWindow(window, resourceZone, horizon, policy);
            result.add(window);
            if (request.recurrence() == RecurrencePattern.NONE) break;
            current = increment(
                    current, request.recurrence(), request.recurrenceInterval(), resourceZone);
        }
        return result;
    }

    private void validateWindow(
            BookingWindow window,
            ZoneId resourceZone,
            CalendarSchedulingHorizon.Horizon horizon,
            CalendarRepository.PolicyRow policy) {
        if (horizon.isPast(window.startsAt())) {
            throw invalid("Meeting rooms cannot be reserved in the past.");
        }
        ZonedDateTime start = window.startsAt().atZoneSameInstant(resourceZone);
        ZonedDateTime end = window.endsAt().atZoneSameInstant(resourceZone);
        if (!horizon.contains(window.startsAt(), resourceZone)) {
            throw invalid("The room reservation exceeds the advance booking policy.");
        }
        if (!start.toLocalDate().equals(end.toLocalDate())
                || start.toLocalTime().isBefore(policy.workingDayStart())
                || end.toLocalTime().isAfter(policy.workingDayEnd())) {
            throw invalid("The room reservation is outside operating hours.");
        }
    }

    private OffsetDateTime increment(
            OffsetDateTime value,
            RecurrencePattern pattern,
            int interval,
            ZoneId resourceZone) {
        ZonedDateTime local = value.atZoneSameInstant(resourceZone);
        return switch (pattern) {
            case DAILY -> local.plusDays(interval).toOffsetDateTime();
            case WEEKLY -> local.plusWeeks(interval).toOffsetDateTime();
            case MONTHLY -> local.plusMonths(interval).toOffsetDateTime();
            case NONE -> value;
        };
    }

    private ZoneId zone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException exception) {
            throw invalid("The meeting room time zone is invalid.");
        }
    }

    private RoomBookingRequest booking(CalendarDtos.CreateEventRequest request) {
        return new RoomBookingRequest(
                request.type(), request.startsAt(), request.endsAt(), request.timeZone(),
                request.allDay(), request.recurrence(), request.recurrenceInterval(),
                request.recurrenceUntil());
    }

    private RoomBookingRequest booking(CalendarDtos.UpdateEventRequest request) {
        return new RoomBookingRequest(
                request.type(), request.startsAt(), request.endsAt(), request.timeZone(),
                request.allDay(), request.recurrence(), request.recurrenceInterval(),
                request.recurrenceUntil());
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private record RoomBookingRequest(
            EventType type,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            boolean allDay,
            RecurrencePattern recurrence,
            int recurrenceInterval,
            LocalDate recurrenceUntil) {
    }

    private record BookingWindow(OffsetDateTime startsAt, OffsetDateTime endsAt) {
    }
}
