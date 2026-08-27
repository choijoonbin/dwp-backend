package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;
@Service
public class CalendarService {

    private static final int MAX_OCCURRENCES = 4000;
    private static final Duration MAX_QUERY_SPAN = Duration.ofDays(370);
    private final CalendarRepository repository;
    private final CalendarOccurrenceProjector occurrenceProjector;
    private final CalendarRoomAccessGuard roomAccessGuard;
    private final CalendarSchedulingEvaluator schedulingEvaluator;
    private final CalendarSchedulingHorizon schedulingHorizon;
    private final RoomBookingPolicyService roomBookingPolicy;

    public CalendarService(
            CalendarRepository repository,
            WorkplaceRoomAccessPort roomAccess,
            CalendarSchedulingHorizon schedulingHorizon,
            RoomBookingPolicyService roomBookingPolicy) {
        this.repository = repository;
        this.occurrenceProjector = new CalendarOccurrenceProjector(repository);
        this.roomAccessGuard = new CalendarRoomAccessGuard(roomAccess);
        this.schedulingEvaluator = new CalendarSchedulingEvaluator(repository, roomAccessGuard);
        this.schedulingHorizon = schedulingHorizon;
        this.roomBookingPolicy = roomBookingPolicy;
    }

    @Transactional(readOnly = true)
    public List<CalendarDtos.CalendarSummary> calendars(
            Long tenantId, Long userId, UUID personPublicId, String locale) {
        return calendars(tenantId, userId, personPublicId, null, locale);
    }

    @Transactional(readOnly = true)
    public List<CalendarDtos.CalendarSummary> calendars(
            Long tenantId, Long userId, UUID personPublicId,
            String verifiedGroupRefs, String locale) {
        repository.linkIdentity(tenantId, userId, personPublicId);
        return repository.calendars(
                tenantId, userId, personPublicId, verifiedGroupRefs, korean(locale)).stream()
                .map(value -> new CalendarDtos.CalendarSummary(
                        value.calendarId(), value.calendarKey(), value.name(), value.color(),
                        value.type(), value.visibility(), value.ownerPersonPublicId(),
                        value.ownerDisplayName(), value.sourceKind(), value.accessLevel(),
                        value.subscriptionPolicy(),
                        value.subscriptionPolicy() == CalendarSubscriptionPolicy.REQUIRED,
                        value.selected(), value.favorite(), value.displayOrder(),
                        value.calendarVersion(), value.subscriptionVersion(),
                        CalendarAccessPolicy.calendarCapabilities(value)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CalendarDtos.EventSummary> events(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        return events(tenantId, userId, personPublicId, null, from, to, locale);
    }

    @Transactional(readOnly = true)
    public List<CalendarDtos.EventSummary> events(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        validateRange(from, to);
        repository.linkIdentity(tenantId, userId, personPublicId);
        return roomAccessGuard.filterViewableEvents(
                tenantId, userId, verifiedGroupRefs,
                occurrenceProjector.summaries(
                        tenantId, userId, personPublicId, verifiedGroupRefs,
                        from, to, locale));
    }

    @Transactional(readOnly = true)
    public CalendarDtos.HomeResponse home(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String timeZone,
            String locale) {
        return home(tenantId, userId, personPublicId, timeZone, locale, null);
    }

    @Transactional(readOnly = true)
    public CalendarDtos.HomeResponse home(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String timeZone,
            String locale,
            String verifiedGroupRefs) {
        ZoneId zone = zone(timeZone);
        repository.linkIdentity(tenantId, userId, personPublicId);
        CalendarRepository.PolicyRow policy = repository.policy(tenantId);
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate today = now.toLocalDate();
        LocalDate weekStartDate = startOfWeek(today, policy.weekStart());
        OffsetDateTime weekStart = weekStartDate.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime weekEnd = weekStart.plusDays(7);
        OffsetDateTime horizonEnd = now.plusDays(30).toOffsetDateTime();
        if (horizonEnd.isBefore(weekEnd)) horizonEnd = weekEnd;
        List<CalendarDtos.EventSummary> horizonEvents = roomAccessGuard.filterViewableEvents(
                tenantId, userId, verifiedGroupRefs,
                occurrenceProjector.summaries(
                        tenantId, userId, personPublicId, verifiedGroupRefs,
                        weekStart, horizonEnd, locale));
        List<CalendarDtos.EventSummary> weekEvents = horizonEvents.stream()
                .filter(event -> event.startsAt().isBefore(weekEnd)
                        && event.endsAt().isAfter(weekStart))
                .toList();
        OffsetDateTime dayStart = today.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = dayStart.plusDays(1);
        List<CalendarDtos.EventSummary> todayEvents = weekEvents.stream()
                .filter(event -> event.startsAt().isBefore(dayEnd) && event.endsAt().isAfter(dayStart))
                .sorted(Comparator.comparing(CalendarDtos.EventSummary::startsAt))
                .toList();
        CalendarDtos.EventSummary next = horizonEvents.stream()
                .filter(event -> event.endsAt().isAfter(now.toOffsetDateTime()))
                .min(Comparator.comparing(CalendarDtos.EventSummary::startsAt))
                .orElse(null);
        int meetingMinutes = minutes(weekEvents, EventType.MEETING);
        int focusMinutes = minutes(weekEvents, EventType.FOCUS);
        int conflicts = (int) weekEvents.stream().filter(CalendarDtos.EventSummary::conflict).count();
        int responses = (int) weekEvents.stream()
                .filter(event -> event.myResponse() == ResponseStatus.NEEDS_ACTION)
                .count();
        int availableRooms = (int) roomAccessGuard.filterViewableResources(
                tenantId, userId, verifiedGroupRefs, repository.resources(
                        tenantId, now.toOffsetDateTime(), now.plusHours(1).toOffsetDateTime(),
                        korean(locale), false)).stream()
                .filter(resource -> resource.type() == ResourceType.ROOM && resource.available())
                .count();
        CalendarDtos.HomeMetrics metrics = new CalendarDtos.HomeMetrics(
                weekEvents.size(), meetingMinutes, focusMinutes,
                policy.weeklyFocusTargetMinutes(), conflicts, responses, availableRooms);
        List<CalendarDtos.DayLoad> load = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            LocalDate date = weekStartDate.plusDays(day);
            OffsetDateTime start = date.atStartOfDay(zone).toOffsetDateTime();
            OffsetDateTime end = start.plusDays(1);
            List<CalendarDtos.EventSummary> values = weekEvents.stream()
                    .filter(event -> event.startsAt().isBefore(end) && event.endsAt().isAfter(start))
                    .toList();
            int dailyMeetings = minutes(values, EventType.MEETING);
            int dailyFocus = minutes(values, EventType.FOCUS);
            int dailyConflicts = (int) values.stream()
                    .filter(CalendarDtos.EventSummary::conflict).count();
            int loadPercent = Math.min(160, Math.round(
                    dailyMeetings * 100f / Math.max(1, policy.dailyMeetingLimitMinutes())));
            load.add(new CalendarDtos.DayLoad(
                    date, dailyMeetings, dailyFocus, values.size(), dailyConflicts, loadPercent));
        }
        return new CalendarDtos.HomeResponse(
                today, zone.getId(), next, todayEvents, metrics, List.copyOf(load),
                occurrenceProjector.attention(weekEvents, policy, locale), OffsetDateTime.now());
    }

    @Transactional
    public CalendarDtos.EventSummary create(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String organizerName,
            String locale,
            String correlationId,
            CalendarDtos.CreateEventRequest request) {
        return create(
                tenantId, userId, personPublicId, organizerName,
                locale, correlationId, null, request);
    }

    @Transactional
    public CalendarDtos.EventSummary create(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String organizerName,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.CreateEventRequest request) {
        repository.linkIdentity(tenantId, userId, personPublicId);
        String requestFingerprint = CalendarRequestFingerprint.create(request);
        repository.lockEventIdempotency(tenantId, userId, request.idempotencyKey());
        CalendarRepository.IdempotencyRow idempotency = repository.eventIdempotency(
                tenantId, userId, request.idempotencyKey()).orElse(null);
        if (idempotency != null) {
            if (!requestFingerprint.equals(idempotency.requestFingerprint())) {
                throw conflict("The idempotency key was already used with a different request.");
            }
            CalendarRepository.EventRow existing = CalendarRepositoryRouting.event(
                            repository, tenantId, userId, personPublicId, verifiedGroupRefs,
                            idempotency.eventId(), korean(locale))
                    .orElseThrow(() -> conflict(
                            "The calendar idempotency state is unavailable."));
            roomAccessGuard.requireBook(
                    tenantId, userId, verifiedGroupRefs, existing.resource());
            return occurrenceProjector.summary(
                    tenantId, userId, personPublicId, existing, false, locale);
        }
        CalendarRepository.PolicyRow policy = validateEvent(
                tenantId, request.startsAt(), request.endsAt(), request.timeZone(),
                request.type(), request.description(), request.recurrence(),
                request.recurrenceUntil(), request.attendees());
        CalendarRepository.ResourceRow resource = validateResource(
                tenantId, request.resourceId(), request.startsAt(), request.endsAt(), null,
                request.timeZone(), request.recurrence(), request.recurrenceInterval(),
                request.recurrenceUntil(), locale);
        roomBookingPolicy.validateLockedCreate(tenantId, resource, policy, request);
        roomAccessGuard.requireBook(tenantId, userId, verifiedGroupRefs, resource);
        UUID calendarId = request.calendarId() == null
                ? repository.ensurePersonalCalendar(tenantId, userId, personPublicId)
                : CalendarAccessPolicy.writableCalendar(
                        repository, tenantId, userId, personPublicId,
                        verifiedGroupRefs, request.calendarId());
        UUID eventId = repository.insertEvent(
                tenantId, userId, personPublicId, organizerName,
                calendarId, requestFingerprint, request);
        if (resource != null) {
            repository.insertBooking(
                    tenantId, userId, eventId, resource, request.startsAt(), request.endsAt());
        }
        repository.audit(tenantId, userId, eventId, "calendar.event.created", correlationId,
                Map.of(), Map.of(
                        "title", request.title(),
                        "startsAt", request.startsAt(),
                        "endsAt", request.endsAt(),
                        "type", request.type().name(),
                        "resourceId", request.resourceId() == null ? "" : request.resourceId()));
        CalendarRepository.EventRow created = CalendarRepositoryRouting.event(
                        repository, tenantId, userId, personPublicId, verifiedGroupRefs,
                        eventId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return occurrenceProjector.summary(
                tenantId, userId, personPublicId, created, false, locale);
    }

    @Transactional
    public CalendarDtos.EventSummary update(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            CalendarDtos.UpdateEventRequest request) {
        return update(
                tenantId, userId, personPublicId, eventId,
                locale, correlationId, null, request);
    }

    @Transactional
    public CalendarDtos.EventSummary update(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.UpdateEventRequest request) {
        CalendarRepository.EventRow before = CalendarRepositoryRouting.event(
                        repository, tenantId, userId, personPublicId, verifiedGroupRefs,
                        eventId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!CalendarAccessPolicy.canEdit(before)) {
            throw new BaseException(ErrorCode.FORBIDDEN, "This event is read-only.");
        }
        roomAccessGuard.requireBook(tenantId, userId, verifiedGroupRefs, before.resource());
        CalendarRepository.PolicyRow policy = validateEvent(
                tenantId, request.startsAt(), request.endsAt(), request.timeZone(),
                request.type(), request.description(), request.recurrence(),
                request.recurrenceUntil(), request.attendees());
        CalendarRepository.ResourceRow resource = validateResource(
                tenantId, request.resourceId(), request.startsAt(), request.endsAt(), eventId,
                request.timeZone(), request.recurrence(), request.recurrenceInterval(),
                request.recurrenceUntil(), locale);
        roomBookingPolicy.validateLockedUpdate(tenantId, resource, policy, eventId, request);
        if (before.resource() == null
                || resource == null
                || !before.resource().resourceId().equals(resource.resourceId())) {
            roomAccessGuard.requireBook(tenantId, userId, verifiedGroupRefs, resource);
        }
        if (CalendarRepositoryRouting.updateEvent(
                repository, tenantId, userId, personPublicId,
                verifiedGroupRefs, eventId, request) == 0) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The event changed. Refresh and try again.");
        }
        repository.replaceAttendees(tenantId, eventId, request.attendees());
        UUID previousResourceId = before.resource() == null
                ? null : before.resource().resourceId();
        boolean resourceChanged = !Objects.equals(previousResourceId, request.resourceId());
        boolean scheduleChanged = !before.startsAt().equals(request.startsAt())
                || !before.endsAt().equals(request.endsAt())
                || before.recurrence() != request.recurrence()
                || before.recurrenceInterval() != request.recurrenceInterval()
                || !Objects.equals(before.recurrenceUntil(), request.recurrenceUntil());
        if (resourceChanged) {
            repository.cancelBookings(tenantId, userId, eventId);
            if (resource != null) {
                repository.insertBooking(
                        tenantId, userId, eventId, resource,
                        request.startsAt(), request.endsAt());
            }
        } else if (resource != null && scheduleChanged) {
            repository.rescheduleBooking(
                    tenantId, userId, eventId, request.startsAt(), request.endsAt(),
                    resource.approvalRequired());
        }
        repository.audit(tenantId, userId, eventId, "calendar.event.updated", correlationId,
                eventSnapshot(before), Map.of(
                        "title", request.title(),
                        "startsAt", request.startsAt(),
                        "endsAt", request.endsAt(),
                        "type", request.type().name()));
        CalendarRepository.EventRow updated = CalendarRepositoryRouting.event(
                        repository, tenantId, userId, personPublicId, verifiedGroupRefs,
                        eventId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return occurrenceProjector.summary(
                tenantId, userId, personPublicId, updated, false, locale);
    }

    @Transactional
    public void cancel(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            CalendarDtos.VersionRequest request) {
        cancel(
                tenantId, userId, personPublicId, eventId,
                locale, correlationId, null, request);
    }

    @Transactional
    public void cancel(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.VersionRequest request) {
        CalendarRepository.EventRow before = CalendarRepositoryRouting.event(
                        repository, tenantId, userId, personPublicId, verifiedGroupRefs,
                        eventId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!CalendarAccessPolicy.canDelete(before)) {
            throw new BaseException(ErrorCode.FORBIDDEN, "This event cannot be cancelled.");
        }
        roomAccessGuard.requireBook(tenantId, userId, verifiedGroupRefs, before.resource());
        if (CalendarRepositoryRouting.cancelEvent(
                repository, tenantId, userId, personPublicId, verifiedGroupRefs,
                eventId, request.version()) == 0) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The event changed. Refresh and try again.");
        }
        repository.cancelBookings(tenantId, userId, eventId);
        repository.audit(tenantId, userId, eventId, "calendar.event.cancelled", correlationId,
                eventSnapshot(before), Map.of("status", "CANCELLED"));
    }

    @Transactional
    public CalendarDtos.EventSummary respond(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            CalendarDtos.RespondRequest request) {
        return respond(
                tenantId, userId, personPublicId, eventId,
                locale, correlationId, null, request);
    }

    @Transactional
    public CalendarDtos.EventSummary respond(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.RespondRequest request) {
        if (request.response() == ResponseStatus.NEEDS_ACTION) {
            throw invalid("A final attendance response is required.");
        }
        CalendarRepository.EventRow before = CalendarRepositoryRouting.event(
                        repository, tenantId, userId, personPublicId, verifiedGroupRefs,
                        eventId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        roomAccessGuard.requireView(tenantId, userId, verifiedGroupRefs, before.resource());
        if (repository.respond(tenantId, userId, personPublicId, eventId, request.response()) == 0) {
            throw new BaseException(ErrorCode.NOT_FOUND, "The attendee record was not found.");
        }
        repository.audit(tenantId, userId, eventId, "calendar.attendee.responded", correlationId,
                Map.of("response", before.myResponse() == null ? "" : before.myResponse().name()),
                Map.of("response", request.response().name()));
        CalendarRepository.EventRow updated = CalendarRepositoryRouting.event(
                        repository, tenantId, userId, personPublicId, verifiedGroupRefs,
                        eventId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return occurrenceProjector.summary(
                tenantId, userId, personPublicId, updated, false, locale);
    }

    @Transactional(readOnly = true)
    public List<CalendarDtos.ResourceSummary> resources(
            Long tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        validateRange(from, to);
        return repository.resources(tenantId, from, to, korean(locale), false).stream()
                .map(this::resource)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CalendarDtos.ResourceSummary> resources(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        validateRange(from, to);
        return roomAccessGuard.filterViewableResources(
                tenantId, userId, verifiedGroupRefs,
                repository.resources(tenantId, from, to, korean(locale), false)).stream()
                .map(this::resource)
                .toList();
    }

    @Transactional(readOnly = true)
    public CalendarDtos.AvailabilityResponse availability(
            Long tenantId,
            Long currentUserId,
            UUID currentPersonPublicId,
            List<UUID> requestedPeople,
            OffsetDateTime from,
            OffsetDateTime to,
            int durationMinutes,
            String timeZone,
            String locale) {
        validateRange(from, to);
        return schedulingEvaluator.availability(
                tenantId, currentUserId, currentPersonPublicId, requestedPeople,
                from, to, durationMinutes, timeZone, locale);
    }

    @Transactional(readOnly = true)
    public CalendarDtos.AvailabilityResponse availability(
            Long tenantId, Long currentUserId, UUID currentPersonPublicId,
            String verifiedGroupRefs, List<UUID> requestedPeople,
            OffsetDateTime from, OffsetDateTime to, int durationMinutes,
            String timeZone, String locale) {
        validateRange(from, to);
        return schedulingEvaluator.availability(
                tenantId, currentUserId, currentPersonPublicId, verifiedGroupRefs,
                requestedPeople, from, to, durationMinutes, timeZone, locale);
    }

    @Transactional(readOnly = true)
    public CalendarDtos.SchedulingEvaluationResponse evaluateScheduling(
            Long tenantId,
            Long currentUserId,
            UUID currentPersonPublicId,
            String verifiedGroupRefs,
            String locale,
            CalendarDtos.SchedulingEvaluationRequest request) {
        validateRange(request.from(), request.to());
        validateRange(request.roomStartsAt(), request.roomEndsAt());
        return schedulingEvaluator.evaluate(
                tenantId, currentUserId, currentPersonPublicId,
                verifiedGroupRefs, locale, request);
    }

    @Transactional(readOnly = true)
    public CalendarDtos.AdminOverview adminOverview(Long tenantId, String locale) {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        LocalDate week = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        OffsetDateTime from = week.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to = from.plusDays(7);
        CalendarRepository.AdminStats stats = repository.adminStats(tenantId, from, to);
        return new CalendarDtos.AdminOverview(
                stats.activeResources(), stats.resourcesInMaintenance(), stats.bookingsThisWeek(),
                stats.pendingBookings(), stats.eventsThisWeek(), stats.conflictedUsers(),
                policy(repository.policy(tenantId)),
                repository.resources(tenantId, from, to, korean(locale), true).stream()
                        .map(this::resource).toList(),
                OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public CalendarDtos.Policy policy(Long tenantId) {
        return policy(repository.policy(tenantId));
    }

    @Transactional(readOnly = true)
    public List<CalendarDtos.BookingSummary> pendingBookings(Long tenantId, String locale) {
        return repository.pendingBookings(tenantId, korean(locale)).stream()
                .map(this::booking)
                .toList();
    }

    @Transactional
    public CalendarDtos.BookingSummary decideBooking(
            Long tenantId,
            Long actorId,
            UUID bookingId,
            String locale,
            String correlationId,
            CalendarDtos.BookingDecisionRequest request) {
        String status = "APPROVE".equals(request.decision()) ? "CONFIRMED" : "DECLINED";
        CalendarRepository.BookingRow saved = repository.decideBooking(
                tenantId, actorId, bookingId, status, request.note(), request.version(),
                korean(locale));
        if (saved == null) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The booking changed or was already decided. Refresh and try again.");
        }
        repository.audit(tenantId, actorId, saved.eventId(),
                "calendar.booking." + status.toLowerCase(Locale.ROOT), correlationId,
                Map.of("status", "PENDING"), Map.of(
                        "bookingId", bookingId,
                        "status", status,
                        "note", request.note() == null ? "" : request.note()));
        return booking(saved);
    }

    @Transactional
    public CalendarDtos.Policy updatePolicy(
            Long tenantId,
            Long actorId,
            String correlationId,
            CalendarDtos.PolicyRequest request) {
        if (!request.workingDayEnd().isAfter(request.workingDayStart())) {
            throw invalid("Working hours must end after they start.");
        }
        if (request.minimumEventMinutes() > request.defaultEventMinutes()
                || request.defaultEventMinutes() > request.maximumEventMinutes()) {
            throw invalid("The default duration must be within the minimum and maximum duration.");
        }
        CalendarRepository.PolicyRow before = repository.policy(tenantId);
        if (repository.updatePolicy(tenantId, actorId, request) == 0) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The scheduling policy changed. Refresh and try again.");
        }
        repository.audit(tenantId, actorId, null, "calendar.policy.updated", correlationId,
                Map.of("version", before.version()), Map.of("version", before.version() + 1));
        return policy(repository.policy(tenantId));
    }

    @Transactional
    public CalendarDtos.ResourceSummary saveResource(
            Long tenantId,
            Long actorId,
            UUID resourceId,
            String locale,
            String correlationId,
            CalendarDtos.ResourceRequest request) {
        return saveResource(
                tenantId, actorId, resourceId, locale, correlationId, request, false);
    }

    @Transactional
    public CalendarDtos.ResourceSummary saveWorkplaceManagedResource(
            Long tenantId,
            Long actorId,
            UUID resourceId,
            String locale,
            String correlationId,
            CalendarDtos.ResourceRequest request) {
        return saveResource(
                tenantId, actorId, resourceId, locale, correlationId, request, true);
    }

    private CalendarDtos.ResourceSummary saveResource(
            Long tenantId,
            Long actorId,
            UUID resourceId,
            String locale,
            String correlationId,
            CalendarDtos.ResourceRequest request,
            boolean workplaceWrite) {
        if (resourceId != null
                && !workplaceWrite
                && repository.isWorkplaceManagedResource(tenantId, resourceId)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This room is managed by Workplace. Update it from Workplace locations.");
        }
        CalendarRepository.ResourceRow saved = repository.saveResource(
                tenantId, actorId, resourceId, request, korean(locale));
        if (saved == null) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The resource changed. Refresh and try again.");
        }
        repository.audit(tenantId, actorId, null,
                resourceId == null ? "calendar.resource.created" : "calendar.resource.updated",
                correlationId, Map.of(), Map.of(
                        "resourceId", saved.resourceId(),
                        "code", saved.code(),
                        "state", saved.state().name()));
        return resource(saved);
    }

    private CalendarRepository.ResourceRow validateResource(
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
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND, "The resource was not found."));
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
            if (repository.resourceConflict(
                    tenantId, resourceId, occurrence.startsAt(), occurrence.endsAt(),
                    excludingEventId)) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "The resource is already booked for this time.");
            }
        }
        return resource;
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
                .anyMatch(attendee -> !attendee.email().toLowerCase(Locale.ROOT).endsWith("@sk.com"))) {
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

    private void validateRange(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw invalid("A valid date range is required.");
        }
        if (Duration.between(from, to).compareTo(MAX_QUERY_SPAN) > 0) {
            throw invalid("Calendar queries are limited to 370 days.");
        }
    }

    private int minutes(List<CalendarDtos.EventSummary> events, EventType type) {
        return events.stream().filter(event -> event.type() == type)
                .mapToInt(event -> (int) Duration.between(event.startsAt(), event.endsAt()).toMinutes())
                .sum();
    }

    private LocalDate startOfWeek(LocalDate date, int weekStart) {
        int delta = Math.floorMod(date.getDayOfWeek().getValue() - weekStart, 7);
        return date.minusDays(delta);
    }

    private ZoneId zone(String value) {
        try {
            return ZoneId.of(value == null || value.isBlank() ? "Asia/Seoul" : value);
        } catch (DateTimeException exception) {
            throw invalid("The time zone is invalid.");
        }
    }

    private CalendarDtos.ResourceSummary resource(CalendarRepository.ResourceRow value) {
        return new CalendarDtos.ResourceSummary(
                value.resourceId(), value.code(), value.name(), value.nameKo(), value.nameEn(),
                value.type(), value.site(), value.floor(), value.capacity(), value.features(),
                value.timeZone(), value.approvalRequired(),
                value.state(), value.available(), value.version());
    }

    private CalendarDtos.BookingSummary booking(CalendarRepository.BookingRow value) {
        return new CalendarDtos.BookingSummary(
                value.bookingId(), value.eventId(), value.resourceId(), value.resourceName(),
                value.eventTitle(), value.startsAt(), value.endsAt(), value.organizerName(),
                value.organizerEmail(), value.status(), value.requestedBy(), value.decisionNote(),
                value.decidedAt(), value.decidedBy(), value.version());
    }

    private CalendarDtos.Policy policy(CalendarRepository.PolicyRow value) {
        return new CalendarDtos.Policy(
                value.weekStart(), value.workingDayStart(), value.workingDayEnd(),
                value.defaultEventMinutes(), value.minimumEventMinutes(),
                value.maximumEventMinutes(), value.maximumAdvanceDays(),
                value.defaultBufferMinutes(), value.weeklyFocusTargetMinutes(),
                value.dailyMeetingLimitMinutes(), value.enforceMeetingAgenda(),
                value.allowExternalAttendees(), value.version());
    }

    private Map<String, Object> eventSnapshot(CalendarRepository.EventRow value) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("eventId", value.eventId());
        snapshot.put("title", value.title());
        snapshot.put("startsAt", value.startsAt());
        snapshot.put("endsAt", value.endsAt());
        snapshot.put("type", value.type().name());
        snapshot.put("status", value.status().name());
        snapshot.put("version", value.version());
        return snapshot;
    }

    private boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private record BookingWindow(OffsetDateTime startsAt, OffsetDateTime endsAt) {
    }
}
