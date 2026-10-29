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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
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
public class CalendarService extends CalendarEventCommandSupport {

    private final CalendarHomeQuery homeQuery;
    private final CalendarSchedulingEvaluator schedulingEvaluator;
    private final CalendarAdministrationOperations administration;

    @Autowired(required = false)
    void setDwaionHandoffs(PlatformDwaionHandoffOutboxRepository dwaionHandoffs) {
        this.dwaionHandoffs = dwaionHandoffs;
    }

    @Autowired(required = false)
    void setSchedulingV2Tokens(CalendarSchedulingV2TokenVerifier schedulingV2Tokens) {
        this.schedulingV2Tokens = schedulingV2Tokens;
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
        super(repository, roomAccess, schedulingHorizon, roomBookingPolicy,
                mailProposalOutcomes, occurrenceProjector, occurrenceCommands);
        this.homeQuery = new CalendarHomeQuery(
                repository, occurrenceProjector, eventValidation, roomAccessGuard);
        this.schedulingEvaluator = new CalendarSchedulingEvaluator(repository, roomAccessGuard);
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
        return homeQuery.home(
                tenantId, userId, personPublicId, timeZone, locale, verifiedGroupRefs);
    }

    @Override
    @Transactional
    public CalendarDtos.EventSummary create(
            Long tenantId, Long userId, UUID personPublicId, String organizerName,
            String locale, String correlationId,
            CalendarDtos.CreateEventRequest request) {
        return super.create(tenantId, userId, personPublicId, organizerName,
                locale, correlationId, request);
    }

    @Override
    @Transactional
    public CalendarDtos.EventSummary create(
            Long tenantId, Long userId, UUID personPublicId, String organizerName,
            String locale, String correlationId, String verifiedGroupRefs,
            CalendarDtos.CreateEventRequest request) {
        return super.create(tenantId, userId, personPublicId, organizerName,
                locale, correlationId, verifiedGroupRefs, request);
    }

    @Override
    @Transactional
    public CalendarDtos.EventSummary create(
            Long tenantId, Long userId, UUID personPublicId, String organizerName,
            String locale, String correlationId, String verifiedGroupRefs,
            CalendarDtos.CreateEventRequest request,
            MailProposalHandoffBinding proposalBinding) {
        return super.create(tenantId, userId, personPublicId, organizerName, locale,
                correlationId, verifiedGroupRefs, request, proposalBinding);
    }

    @Override
    @Transactional
    public CalendarDtos.EventSummary create(
            Long tenantId, Long userId, UUID personPublicId, String organizerName,
            String locale, String correlationId, String verifiedGroupRefs,
            CalendarDtos.CreateEventRequest request,
            MailProposalHandoffBinding proposalBinding,
            PlatformDwaionHandoff.Binding dwaionBinding,
            PlatformDwaionHandoff.Identity dwaionIdentity) {
        return super.create(tenantId, userId, personPublicId, organizerName, locale,
                correlationId, verifiedGroupRefs, request, proposalBinding,
                dwaionBinding, dwaionIdentity);
    }

    @Override
    @Transactional
    public CalendarDtos.EventSummary create(
            Long tenantId, Long userId, UUID personPublicId, String organizerName,
            String locale, String correlationId, String verifiedGroupRefs,
            CalendarDtos.CreateEventRequest request,
            MailProposalHandoffBinding proposalBinding,
            PlatformDwaionHandoff.Binding dwaionBinding,
            PlatformDwaionHandoff.Identity dwaionIdentity,
            String hardRecheckToken) {
        return super.create(tenantId, userId, personPublicId, organizerName, locale,
                correlationId, verifiedGroupRefs, request, proposalBinding,
                dwaionBinding, dwaionIdentity, hardRecheckToken);
    }

    @Override
    @Transactional
    public CalendarDtos.EventSummary update(
            Long tenantId, Long userId, UUID personPublicId, UUID eventId,
            String locale, String correlationId,
            CalendarDtos.UpdateEventRequest request) {
        return super.update(tenantId, userId, personPublicId, eventId,
                locale, correlationId, request);
    }

    @Override
    @Transactional
    public CalendarDtos.EventSummary update(
            Long tenantId, Long userId, UUID personPublicId, UUID eventId,
            String locale, String correlationId, String verifiedGroupRefs,
            CalendarDtos.UpdateEventRequest request) {
        return super.update(tenantId, userId, personPublicId, eventId,
                locale, correlationId, verifiedGroupRefs, request);
    }

    @Override
    @Transactional
    public CalendarDtos.EventSummary update(
            Long tenantId, Long userId, UUID personPublicId, UUID eventId,
            String locale, String correlationId, String verifiedGroupRefs,
            CalendarDtos.UpdateEventRequest request, String hardRecheckToken) {
        return super.update(tenantId, userId, personPublicId, eventId, locale,
                correlationId, verifiedGroupRefs, request, hardRecheckToken);
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
    public void cancelOccurrence(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.CancelOccurrenceRequest request) {
        if (occurrenceCommands == null) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Calendar occurrence commands are unavailable.");
        }
        occurrenceCommands.cancelOccurrence(
                tenantId, userId, personPublicId, eventId, locale,
                correlationId, verifiedGroupRefs, request);
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

    private CalendarDtos.ResourceSummary resource(CalendarRepository.ResourceRow value) {
        return new CalendarDtos.ResourceSummary(
                value.resourceId(), value.code(), value.name(), value.nameKo(), value.nameEn(),
                value.type(), value.site(), value.floor(), value.capacity(), value.features(),
                value.timeZone(), value.approvalRequired(),
                value.state(), value.available(), value.version());
    }

}
