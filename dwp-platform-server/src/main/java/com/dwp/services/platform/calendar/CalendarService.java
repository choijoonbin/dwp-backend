package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.mail.MailProposalHandoffBinding;
import com.dwp.services.platform.mail.MailProposalOutcomePort;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoff;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoffOutboxRepository;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;

/**
 * Owns Calendar transaction boundaries and coordinates repositories and domain collaborators.
 *
 * <p>Event and resource validation is delegated to {@link CalendarEventValidation} so command
 * orchestration stays distinct from policy checks and recurring booking-window projection.
 * Public methods retain their established transaction semantics and compatibility surface.
 */
@Service
public class CalendarService {

    private final CalendarRepository repository;
    private final CalendarOccurrenceProjector occurrenceProjector;
    private final CalendarEventValidation eventValidation;
    private final CalendarRoomAccessGuard roomAccessGuard;
    private final CalendarSchedulingEvaluator schedulingEvaluator;
    private final RoomBookingPolicyService roomBookingPolicy;
    private final CalendarMailProposalBridge mailProposals;
    private final CalendarOccurrenceCommandService occurrenceCommands;
    private final CalendarAdministrationOperations administration;
    private PlatformDwaionHandoffOutboxRepository dwaionHandoffs;

    @Autowired(required = false)
    void setDwaionHandoffs(PlatformDwaionHandoffOutboxRepository dwaionHandoffs) {
        this.dwaionHandoffs = dwaionHandoffs;
    }

    public CalendarService(
            CalendarRepository repository,
            WorkplaceRoomAccessPort roomAccess,
            CalendarSchedulingHorizon schedulingHorizon,
            RoomBookingPolicyService roomBookingPolicy) {
        this(repository, roomAccess, schedulingHorizon, roomBookingPolicy, null,
                new CalendarOccurrenceProjector(repository), null);
    }

    public CalendarService(
            CalendarRepository repository,
            WorkplaceRoomAccessPort roomAccess,
            CalendarSchedulingHorizon schedulingHorizon,
            RoomBookingPolicyService roomBookingPolicy,
            MailProposalOutcomePort mailProposalOutcomes) {
        this(repository, roomAccess, schedulingHorizon, roomBookingPolicy, mailProposalOutcomes,
                new CalendarOccurrenceProjector(repository), null);
    }

    @Autowired
    CalendarService(
            CalendarRepository repository,
            WorkplaceRoomAccessPort roomAccess,
            CalendarSchedulingHorizon schedulingHorizon,
            RoomBookingPolicyService roomBookingPolicy,
            MailProposalOutcomePort mailProposalOutcomes,
            CalendarOccurrenceProjector occurrenceProjector,
            CalendarOccurrenceCommandService occurrenceCommands) {
        this.repository = repository;
        this.occurrenceProjector = occurrenceProjector;
        this.eventValidation = new CalendarEventValidation(
                repository, occurrenceProjector, schedulingHorizon);
        this.roomAccessGuard = new CalendarRoomAccessGuard(roomAccess);
        this.schedulingEvaluator = new CalendarSchedulingEvaluator(repository, roomAccessGuard);
        this.roomBookingPolicy = roomBookingPolicy;
        this.mailProposals = new CalendarMailProposalBridge(mailProposalOutcomes);
        this.occurrenceCommands = occurrenceCommands;
        this.administration = new CalendarAdministrationOperations(repository);
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
        eventValidation.validateRange(from, to);
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
        ZoneId zone = eventValidation.zone(timeZone);
        repository.linkIdentity(tenantId, userId, personPublicId);
        CalendarRepository.PolicyRow policy = repository.policy(tenantId);
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate today = now.toLocalDate();
        LocalDate weekStartDate = startOfWeek(today, policy.weekStart());
        OffsetDateTime weekStart = weekStartDate.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime weekEnd = weekStartDate.plusDays(7).atStartOfDay(zone).toOffsetDateTime();
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
        OffsetDateTime dayEnd = today.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        List<CalendarDtos.EventSummary> todayEvents = weekEvents.stream()
                .filter(event -> event.startsAt().isBefore(dayEnd) && event.endsAt().isAfter(dayStart))
                .sorted(Comparator.comparing(CalendarDtos.EventSummary::startsAt))
                .toList();
        CalendarDtos.EventSummary next = horizonEvents.stream()
                .filter(event -> event.endsAt().isAfter(now.toOffsetDateTime()))
                .min(Comparator.comparing(CalendarDtos.EventSummary::startsAt))
                .orElse(null);
        int meetingMinutes = minutes(weekEvents, EventType.MEETING, weekStart, weekEnd);
        int focusMinutes = minutes(weekEvents, EventType.FOCUS, weekStart, weekEnd);
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
            OffsetDateTime end = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
            List<CalendarDtos.EventSummary> values = weekEvents.stream()
                    .filter(event -> event.startsAt().isBefore(end) && event.endsAt().isAfter(start))
                    .toList();
            int dailyMeetings = minutes(values, EventType.MEETING, start, end);
            int dailyFocus = minutes(values, EventType.FOCUS, start, end);
            int dailyConflicts = (int) values.stream()
                    .filter(CalendarDtos.EventSummary::conflict).count();
            int loadPercent = Math.round(
                    dailyMeetings * 100f / Math.max(1, policy.dailyMeetingLimitMinutes()));
            load.add(new CalendarDtos.DayLoad(
                    date, dailyMeetings, dailyFocus, values.size(), dailyConflicts, loadPercent));
        }
        return new CalendarDtos.HomeResponse(
                today, zone.getId(), next, todayEvents, metrics, List.copyOf(load),
                occurrenceProjector.attention(weekEvents, policy, locale, focusMinutes), OffsetDateTime.now());
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
                locale, correlationId, null, request, null);
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
        return create(tenantId, userId, personPublicId, organizerName, locale,
                correlationId, verifiedGroupRefs, request, null);
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
            CalendarDtos.CreateEventRequest request,
            MailProposalHandoffBinding proposalBinding) {
        return create(tenantId, userId, personPublicId, organizerName, locale,
                correlationId, verifiedGroupRefs, request, proposalBinding, null, null);
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
            CalendarDtos.CreateEventRequest request,
            MailProposalHandoffBinding proposalBinding,
            PlatformDwaionHandoff.Binding dwaionBinding,
            PlatformDwaionHandoff.Identity dwaionIdentity) {
        mailProposals.validate(tenantId, userId, proposalBinding);
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
            CalendarDtos.EventSummary result = occurrenceProjector.summary(
                    tenantId, userId, personPublicId, existing, false, locale);
            mailProposals.complete(
                    tenantId, userId, result.eventId(), correlationId, proposalBinding);
            completeDwaion(tenantId, userId, correlationId, result,
                    dwaionBinding, dwaionIdentity);
            return result;
        }
        mailProposals.validateNew(tenantId, userId, proposalBinding, request);
        CalendarRepository.PolicyRow policy = validateEvent(
                tenantId, request.startsAt(), request.endsAt(), request.timeZone(),
                request.type(), request.description(), request.recurrence(),
                request.recurrenceUntil(), request.attendees());
        CalendarRepository.ResourceRow resource = eventValidation.validateResource(
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
        CalendarDtos.EventSummary result = occurrenceProjector.summary(
                tenantId, userId, personPublicId, created, false, locale);
        mailProposals.complete(
                tenantId, userId, result.eventId(), correlationId, proposalBinding);
        completeDwaion(tenantId, userId, correlationId, result,
                dwaionBinding, dwaionIdentity);
        return result;
    }

    private void completeDwaion(
            Long tenantId,
            Long userId,
            String correlationId,
            CalendarDtos.EventSummary result,
            PlatformDwaionHandoff.Binding binding,
            PlatformDwaionHandoff.Identity identity) {
        if (binding == null) return;
        if (dwaionHandoffs == null) throw PlatformDwaionHandoff.unavailable();
        dwaionHandoffs.committed(
                tenantId, userId, binding, identity,
                PlatformDwaionHandoff.Effect.forBinding(
                        binding, result.eventId(), result.version(), result.status().name()),
                correlationId);
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
        if (request.editScope() == CalendarDtos.RecurrenceEditScope.THIS_OCCURRENCE) {
            if (occurrenceCommands == null) {
                throw new BaseException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        "Calendar occurrence commands are unavailable.");
            }
            return occurrenceCommands.updateOccurrence(
                    tenantId, userId, personPublicId, eventId, locale,
                    correlationId, verifiedGroupRefs, request);
        }
        if (request.originalStartsAt() != null || request.idempotencyKey() != null) {
            throw invalid("Occurrence command fields require THIS_OCCURRENCE scope.");
        }
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
        CalendarRepository.ResourceRow resource = eventValidation.validateResource(
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
        if (scheduleChanged && occurrenceCommands != null) {
            occurrenceCommands.discardOverridesAfterSeriesScheduleChange(tenantId, eventId);
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
        return CalendarInvitationResponseCommand.respond(
                repository, roomAccessGuard, occurrenceProjector,
                tenantId, userId, personPublicId, eventId, locale,
                correlationId, verifiedGroupRefs, request);
    }

    @Transactional(readOnly = true)
    public List<CalendarDtos.ResourceSummary> resources(
            Long tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            String locale) {
        eventValidation.validateRange(from, to);
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
        eventValidation.validateRange(from, to);
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
        eventValidation.validateRange(from, to);
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
        eventValidation.validateRange(from, to);
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
        eventValidation.validateRange(request.from(), request.to());
        eventValidation.validateRange(request.roomStartsAt(), request.roomEndsAt());
        return schedulingEvaluator.evaluate(
                tenantId, currentUserId, currentPersonPublicId,
                verifiedGroupRefs, locale, request);
    }

    @Transactional(readOnly = true)
    public CalendarDtos.AdminOverview adminOverview(Long tenantId, String locale) {
        return administration.adminOverview(tenantId, locale);
    }

    @Transactional(readOnly = true)
    public CalendarDtos.Policy policy(Long tenantId) {
        return administration.policy(tenantId);
    }

    @Transactional(readOnly = true)
    public List<CalendarDtos.BookingSummary> pendingBookings(Long tenantId, String locale) {
        return administration.pendingBookings(tenantId, locale);
    }

    @Transactional
    public CalendarDtos.BookingSummary decideBooking(
            Long tenantId,
            Long actorId,
            UUID bookingId,
            String locale,
            String correlationId,
            CalendarDtos.BookingDecisionRequest request) {
        return administration.decideBooking(
                tenantId, actorId, bookingId, locale, correlationId, request);
    }

    @Transactional
    public CalendarDtos.Policy updatePolicy(
            Long tenantId,
            Long actorId,
            String correlationId,
            CalendarDtos.PolicyRequest request) {
        return administration.updatePolicy(tenantId, actorId, correlationId, request);
    }

    @Transactional
    public CalendarDtos.ResourceSummary saveResource(
            Long tenantId,
            Long actorId,
            UUID resourceId,
            String locale,
            String correlationId,
            CalendarDtos.ResourceRequest request) {
        return administration.saveResource(
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
        return administration.saveResource(
                tenantId, actorId, resourceId, locale, correlationId, request, true);
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
        return eventValidation.validateEvent(
                tenantId, startsAt, endsAt, timeZone, type, description,
                recurrence, recurrenceUntil, attendees);
    }

    /** Sum event minutes only inside the requested reporting interval. */
    private int minutes(
            List<CalendarDtos.EventSummary> events, EventType type, OffsetDateTime from, OffsetDateTime to) {
        return CalendarHomeTimeAccounting.minutes(events, type, from, to);
    }

    private LocalDate startOfWeek(LocalDate date, int weekStart) {
        int delta = Math.floorMod(date.getDayOfWeek().getValue() - weekStart, 7);
        return date.minusDays(delta);
    }

    private CalendarDtos.ResourceSummary resource(CalendarRepository.ResourceRow value) {
        return new CalendarDtos.ResourceSummary(
                value.resourceId(), value.code(), value.name(), value.nameKo(), value.nameEn(),
                value.type(), value.site(), value.floor(), value.capacity(), value.features(),
                value.timeZone(), value.approvalRequired(),
                value.state(), value.available(), value.version());
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

}
