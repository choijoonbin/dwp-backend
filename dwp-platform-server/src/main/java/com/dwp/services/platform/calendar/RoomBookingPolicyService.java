package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.EventType;
import static com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern;
import static com.dwp.services.platform.calendar.CalendarTypes.ResourceState;
import static com.dwp.services.platform.calendar.CalendarTypes.RoomBookingEligibilityReason;

@Service
class RoomBookingPolicyService {

    private static final int MAX_OCCURRENCES = 4000;

    private final CalendarRepository repository;
    private final CalendarSchedulingHorizon schedulingHorizon;
    private final WorkplaceRoomRecurrencePolicy workplaceRecurrencePolicy;
    private final CalendarRecurrenceEngine recurrence = new CalendarRecurrenceEngine();

    RoomBookingPolicyService(
            CalendarRepository repository,
            CalendarSchedulingHorizon schedulingHorizon,
            WorkplaceRoomRecurrencePolicy workplaceRecurrencePolicy) {
        this.repository = repository;
        this.schedulingHorizon = schedulingHorizon;
        this.workplaceRecurrencePolicy = Objects.requireNonNull(
                workplaceRecurrencePolicy, "workplaceRecurrencePolicy");
    }

    void validateLockedCreate(
            Long tenantId,
            CalendarRepository.ResourceRow resource,
            CalendarRepository.PolicyRow policy,
            CalendarDtos.CreateEventRequest request) {
        validate(tenantId, resource, policy(policy), null, booking(request));
        validateLockedCapacity(tenantId, resource, request.attendees());
    }

    void validateLockedUpdate(
            Long tenantId,
            CalendarRepository.ResourceRow resource,
            CalendarRepository.PolicyRow policy,
            UUID excludingEventId,
            CalendarDtos.UpdateEventRequest request) {
        validate(tenantId, resource, policy(policy), excludingEventId, booking(request));
        validateLockedCapacity(tenantId, resource, request.attendees());
    }

    private void validateLockedCapacity(Long tenantId, CalendarRepository.ResourceRow resource,
                                        List<CalendarDtos.AttendeeInput> attendees) {
        if (resource == null || resource.type() != CalendarTypes.ResourceType.ROOM) return;
        // The native attendee owner persists one row per trimmed, case-insensitive email.
        // Invitations are counted without inferring organizer presence or actual attendance.
        long attendeeCount = attendees.stream()
                .map(attendee -> attendee.email().trim().toLowerCase(java.util.Locale.ROOT))
                .distinct().count();
        if (attendeeCount > repository.lockedRoomCapacity(tenantId, resource.resourceId())) {
            throw invalid("The number of invited attendees exceeds the meeting room capacity.");
        }
    }

    CalendarDtos.RoomBookingEligibility evaluateAvailability(
            CalendarDtos.ResourceSummary resource,
            CalendarDtos.Policy policy,
            OffsetDateTime from,
            OffsetDateTime to,
            UUID excludedEventId,
            OffsetDateTime evaluatedAt,
            boolean conflict) {
        RoomBookingEligibilityReason reason = RoomBookingEligibilityReason.ELIGIBLE;
        if (resource.type() != CalendarTypes.ResourceType.ROOM
                || resource.state() != ResourceState.AVAILABLE) {
            reason = RoomBookingEligibilityReason.RESOURCE_UNAVAILABLE;
        } else {
            try {
                ZoneId resourceZone = zone(resource.timeZone());
                bookingWindows(new RoomBookingRequest(
                        EventType.MEETING,
                        from,
                        to,
                        resource.timeZone(),
                        false,
                        RecurrencePattern.NONE,
                        1,
                        null,
                        List.of(),
                        null), resourceZone, policy(policy));
                if (conflict) reason = RoomBookingEligibilityReason.RESOURCE_CONFLICT;
            } catch (BaseException exception) {
                reason = RoomBookingEligibilityReason.POLICY_BLOCKED;
            }
        }
        return new CalendarDtos.RoomBookingEligibility(
                resource.resourceId(),
                reason == RoomBookingEligibilityReason.ELIGIBLE,
                reason,
                from,
                to,
                excludedEventId,
                evaluatedAt,
                resource.version(),
                policy.version());
    }

    private void validate(
            Long tenantId,
            CalendarRepository.ResourceRow resource,
            RoomPolicy policy,
            UUID excludingEventId,
            RoomBookingRequest request) {
        if (resource == null || resource.type() != CalendarTypes.ResourceType.ROOM) return;
        if (request.type() != EventType.MEETING) {
            throw invalid("Room reservations must be meeting events.");
        }
        if (request.allDay()) {
            throw invalid("Room reservations must use a specific time range.");
        }
        validateWorkplaceRecurrence(tenantId, resource, excludingEventId, request);
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

    private void validateWorkplaceRecurrence(
            Long tenantId,
            CalendarRepository.ResourceRow resource,
            UUID excludingEventId,
            RoomBookingRequest request) {
        if (request.recurrence() == RecurrencePattern.NONE) return;
        WorkplaceRoomRecurrencePolicy.ExistingSeries requested = series(
                resource.resourceId(), request);
        WorkplaceRoomRecurrencePolicy.ExistingSeries existing = excludingEventId == null
                ? null
                : workplaceRecurrencePolicy.existingSeries(tenantId, excludingEventId)
                        .orElse(null);
        validateWorkplaceRecurrence(tenantId, existing, requested);
    }

    void validateRestoreRecurrence(
            Long tenantId,
            CalendarTypes.ResourceType resourceType,
            WorkplaceRoomRecurrencePolicy.ExistingSeries cancelledSeries,
            WorkplaceRoomRecurrencePolicy.ExistingSeries requestedSeries) {
        if (resourceType != CalendarTypes.ResourceType.ROOM) return;
        validateWorkplaceRecurrence(tenantId, cancelledSeries, requestedSeries);
    }

    private void validateWorkplaceRecurrence(
            Long tenantId,
            WorkplaceRoomRecurrencePolicy.ExistingSeries existing,
            WorkplaceRoomRecurrencePolicy.ExistingSeries requested) {
        if (requested.recurrence() == RecurrencePattern.NONE
                || workplaceRecurrencePolicy.allowsRecurring(
                        tenantId, requested.calendarResourceId())) {
            return;
        }
        boolean grandfatheredExistingSeries = existing != null
                && Objects.equals(
                        requested.calendarResourceId(), existing.calendarResourceId())
                && existing.recurrence() != RecurrencePattern.NONE
                && existing.recurrence() == requested.recurrence()
                && existing.interval() == requested.interval()
                && Objects.equals(existing.days(), requested.days())
                && existing.startsAt().isEqual(requested.startsAt())
                && existing.endsAt().isEqual(requested.endsAt())
                && zone(existing.timeZone()).equals(zone(requested.timeZone()))
                && recurrenceEndIsNotExtended(existing, requested);
        if (!grandfatheredExistingSeries) {
            throw invalid("Recurring reservations are disabled by the Workplace policy.");
        }
    }

    private WorkplaceRoomRecurrencePolicy.ExistingSeries series(
            UUID resourceId, RoomBookingRequest request) {
        return new WorkplaceRoomRecurrencePolicy.ExistingSeries(
                request.recurrence(),
                request.recurrenceInterval(),
                request.recurrenceUntil(),
                request.recurrenceDays(),
                request.recurrenceCount(),
                request.startsAt(),
                request.endsAt(),
                request.timeZone(),
                resourceId);
    }

    private boolean recurrenceEndIsNotExtended(
            WorkplaceRoomRecurrencePolicy.ExistingSeries existing,
            WorkplaceRoomRecurrencePolicy.ExistingSeries requested) {
        if (existing.until() != null) {
            return requested.until() != null
                    && !requested.until().isAfter(existing.until())
                    && requested.count() == null;
        }
        if (existing.count() != null) {
            return requested.count() != null
                    && requested.count() <= existing.count()
                    && requested.until() == null;
        }
        return requested.until() == null && requested.count() == null;
    }

    private List<BookingWindow> bookingWindows(
            RoomBookingRequest request,
            ZoneId resourceZone,
            RoomPolicy policy) {
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw invalid("The room reservation must end after it starts.");
        }
        Duration duration = Duration.between(request.startsAt(), request.endsAt());
        if (duration.compareTo(Duration.ofMinutes(policy.minimumEventMinutes())) < 0
                || duration.compareTo(Duration.ofMinutes(policy.maximumEventMinutes())) > 0) {
            throw invalid("The room reservation duration is outside the tenant policy.");
        }
        if (request.recurrence() != RecurrencePattern.NONE
                && request.recurrenceUntil() == null
                && request.recurrenceCount() == null) {
            throw invalid("Recurring room reservations require bounded UNTIL or COUNT.");
        }

        CalendarSchedulingHorizon.Horizon horizon = schedulingHorizon.evaluate(
                resourceZone, policy.maximumAdvanceDays());
        if (!horizon.contains(request.startsAt(), resourceZone)
                || !horizon.contains(request.recurrenceUntil())) {
            throw invalid("The room reservation exceeds the advance booking policy.");
        }
        List<CalendarRecurrenceEngine.Projection> projected;
        try {
            CalendarRecurrenceEngine.Rule rule = recurrence.rule(
                    request.recurrence(), request.recurrenceInterval(),
                    request.recurrenceUntil(), request.recurrenceCount(),
                    Set.copyOf(request.recurrenceDays()), resourceZone.getId());
            OffsetDateTime windowTo = horizon.latestDate().plusDays(1)
                    .atStartOfDay(resourceZone).toOffsetDateTime();
            projected = recurrence.project(
                    request.startsAt(), request.endsAt(), rule,
                    request.startsAt().minusNanos(1), windowTo, MAX_OCCURRENCES);
        } catch (IllegalArgumentException
                 | CalendarRecurrenceEngine.ProjectionLimitExceededException exception) {
            throw invalid(exception.getMessage());
        }
        if (request.recurrenceCount() != null
                && projected.size() != request.recurrenceCount()) {
            throw invalid("The room reservation exceeds the advance booking policy.");
        }
        List<BookingWindow> result = new ArrayList<>(projected.size());
        for (CalendarRecurrenceEngine.Projection occurrence : projected) {
            BookingWindow window = occurrence.ordinal() == 1
                    ? new BookingWindow(request.startsAt(), request.endsAt())
                    : new BookingWindow(occurrence.startsAt(), occurrence.endsAt());
            validateWindow(window, resourceZone, horizon, policy);
            result.add(window);
        }
        if (result.isEmpty()) throw invalid("The recurring room reservation has no occurrences.");
        return result;
    }

    private void validateWindow(
            BookingWindow window,
            ZoneId resourceZone,
            CalendarSchedulingHorizon.Horizon horizon,
            RoomPolicy policy) {
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
                request.recurrenceUntil(),
                request.recurrenceDays() == null ? List.of() : request.recurrenceDays(),
                request.recurrenceCount());
    }

    private RoomBookingRequest booking(CalendarDtos.UpdateEventRequest request) {
        return new RoomBookingRequest(
                request.type(), request.startsAt(), request.endsAt(), request.timeZone(),
                request.allDay(), request.recurrence(), request.recurrenceInterval(),
                request.recurrenceUntil(),
                request.recurrenceDays() == null ? List.of() : request.recurrenceDays(),
                request.recurrenceCount());
    }

    private RoomPolicy policy(CalendarRepository.PolicyRow value) {
        return new RoomPolicy(
                value.workingDayStart(),
                value.workingDayEnd(),
                value.minimumEventMinutes(),
                value.maximumEventMinutes(),
                value.maximumAdvanceDays(),
                value.defaultBufferMinutes());
    }

    private RoomPolicy policy(CalendarDtos.Policy value) {
        return new RoomPolicy(
                value.workingDayStart(),
                value.workingDayEnd(),
                value.minimumEventMinutes(),
                value.maximumEventMinutes(),
                value.maximumAdvanceDays(),
                value.defaultBufferMinutes());
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
            LocalDate recurrenceUntil,
            List<DayOfWeek> recurrenceDays,
            Integer recurrenceCount) {
    }

    private record RoomPolicy(
            LocalTime workingDayStart,
            LocalTime workingDayEnd,
            int minimumEventMinutes,
            int maximumEventMinutes,
            int maximumAdvanceDays,
            int defaultBufferMinutes) {
    }

    private record BookingWindow(OffsetDateTime startsAt, OffsetDateTime endsAt) {
    }
}
