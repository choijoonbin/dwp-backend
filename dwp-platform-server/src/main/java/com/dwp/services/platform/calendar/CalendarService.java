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
public class CalendarService {

    private final CalendarRepository repository;
    private final CalendarOccurrenceProjector occurrenceProjector;
    private final CalendarEventValidation eventValidation;
    private final CalendarHomeQuery homeQuery;
    private final CalendarRoomAccessGuard roomAccessGuard;
    private final CalendarSchedulingEvaluator schedulingEvaluator;
    private final RoomBookingPolicyService roomBookingPolicy;
    private final CalendarMailProposalBridge mailProposals;
    private final CalendarOccurrenceCommandService occurrenceCommands;
    private final CalendarAdministrationOperations administration;
    private PlatformDwaionHandoffOutboxRepository dwaionHandoffs;
    private CalendarSchedulingV2TokenVerifier schedulingV2Tokens;

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
        this.repository = repository;
        this.occurrenceProjector = occurrenceProjector;
        this.eventValidation = new CalendarEventValidation(
                repository, occurrenceProjector, schedulingHorizon);
        this.roomAccessGuard = new CalendarRoomAccessGuard(roomAccess);
        this.homeQuery = new CalendarHomeQuery(
                repository, occurrenceProjector, eventValidation, roomAccessGuard);
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
        return homeQuery.home(
                tenantId, userId, personPublicId, timeZone, locale, verifiedGroupRefs);
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
        return create(
                tenantId, userId, personPublicId, organizerName, locale,
                correlationId, verifiedGroupRefs, request, proposalBinding,
                dwaionBinding, dwaionIdentity, null);
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
            PlatformDwaionHandoff.Identity dwaionIdentity,
            String hardRecheckToken) {
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
        eventValidation.validateRecurrence(
                request.startsAt(), request.timeZone(), request.recurrence(),
                request.recurrenceInterval(), request.recurrenceUntil(),
                request.recurrenceCount(), request.recurrenceDays());
        CalendarRepository.PolicyRow policy = validateEvent(
                tenantId, request.startsAt(), request.endsAt(), request.timeZone(),
                request.type(), request.description(), request.recurrence(),
                request.recurrenceInterval(), request.recurrenceUntil(),
                request.recurrenceCount(), request.recurrenceDays(), request.attendees());
        CalendarRepository.ResourceRow resource = eventValidation.validateResource(
                tenantId, request.resourceId(), request.startsAt(), request.endsAt(), null,
                request.timeZone(), request.recurrence(), request.recurrenceInterval(),
                request.recurrenceUntil(), request.recurrenceDays(),
                request.recurrenceCount(), locale);
        roomBookingPolicy.validateLockedCreate(tenantId, resource, policy, request);
        roomAccessGuard.requireBook(tenantId, userId, verifiedGroupRefs, resource);
        UUID calendarId = request.calendarId() == null
                ? repository.ensurePersonalCalendar(tenantId, userId, personPublicId)
                : CalendarAccessPolicy.writableCalendar(
                        repository, tenantId, userId, personPublicId,
                        verifiedGroupRefs, request.calendarId());
        if (request.calendarId() != null) {
            boolean delegatedCreate = repository.lockActiveCreateDelegation(
                    tenantId, calendarId, personPublicId);
            boolean directCreate = repository.hasDirectCreateAccess(
                    tenantId, userId, personPublicId, verifiedGroupRefs, calendarId);
            if (!delegatedCreate && !directCreate) {
                throw new BaseException(
                        ErrorCode.FORBIDDEN,
                        "Calendar CREATE delegation is not active.");
            }
        }
        UUID eventId = UUID.randomUUID();
        consumeSchedulingToken(
                tenantId, userId, personPublicId, hardRecheckToken,
                request.startsAt(), request.endsAt(), request.resourceId(),
                request.timeZone(), request.type(), request.allDay(),
                request.recurrence(), request.recurrenceInterval(), request.recurrenceUntil(),
                resource == null ? null : resource.version(), policy.version(),
                request.attendees(), eventId);
        repository.insertEvent(
                tenantId, userId, personPublicId, organizerName,
                calendarId, requestFingerprint, request, eventId);
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
        return update(
                tenantId, userId, personPublicId, eventId, locale,
                correlationId, verifiedGroupRefs, request, null);
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
            CalendarDtos.UpdateEventRequest request,
            String hardRecheckToken) {
        if (request.editScope() == CalendarDtos.RecurrenceEditScope.THIS_OCCURRENCE) {
            if (occurrenceCommands == null) {
                throw new BaseException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        "Calendar occurrence commands are unavailable.");
            }
            return occurrenceCommands.updateOccurrence(
                    tenantId, userId, personPublicId, eventId, locale,
                    correlationId, verifiedGroupRefs, request, hardRecheckToken);
        }
        if (request.editScope() == CalendarDtos.RecurrenceEditScope.THIS_AND_FOLLOWING) {
            return splitFollowing(
                    tenantId, userId, personPublicId, eventId, locale,
                    correlationId, verifiedGroupRefs, request, hardRecheckToken);
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
        if (hardRecheckToken != null && !hardRecheckToken.isBlank()
                && !Objects.equals(before.organizerPersonPublicId(), personPublicId)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Calendar hard-recheck evidence is limited to the event organizer.");
        }
        roomAccessGuard.requireBook(tenantId, userId, verifiedGroupRefs, before.resource());
        eventValidation.validateRecurrence(
                request.startsAt(), request.timeZone(), request.recurrence(),
                request.recurrenceInterval(), request.recurrenceUntil(),
                request.recurrenceCount(), request.recurrenceDays());
        CalendarRepository.PolicyRow policy = validateEvent(
                tenantId, request.startsAt(), request.endsAt(), request.timeZone(),
                request.type(), request.description(), request.recurrence(),
                request.recurrenceInterval(), request.recurrenceUntil(),
                request.recurrenceCount(), request.recurrenceDays(), request.attendees());
        CalendarRepository.ResourceRow resource = eventValidation.validateResource(
                tenantId, request.resourceId(), request.startsAt(), request.endsAt(), eventId,
                request.timeZone(), request.recurrence(), request.recurrenceInterval(),
                request.recurrenceUntil(), request.recurrenceDays(),
                request.recurrenceCount(), locale);
        roomBookingPolicy.validateLockedUpdate(tenantId, resource, policy, eventId, request);
        if (before.resource() == null
                || resource == null
                || !before.resource().resourceId().equals(resource.resourceId())) {
            roomAccessGuard.requireBook(tenantId, userId, verifiedGroupRefs, resource);
        }
        consumeSchedulingToken(
                tenantId, userId, personPublicId, hardRecheckToken,
                request.startsAt(), request.endsAt(), request.resourceId(),
                request.timeZone(), request.type(), request.allDay(),
                request.recurrence(), request.recurrenceInterval(), request.recurrenceUntil(),
                resource == null ? null : resource.version(), policy.version(),
                request.attendees(), eventId);
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
                || !Objects.equals(before.recurrenceUntil(), request.recurrenceUntil())
                || !semanticRecurrenceDays(before.recurrenceDays()).equals(
                        semanticRecurrenceDays(request.recurrenceDays()))
                || !Objects.equals(before.recurrenceCount(), request.recurrenceCount());
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

    private CalendarDtos.EventSummary splitFollowing(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.UpdateEventRequest request,
            String hardRecheckToken) {
        if (occurrenceCommands == null || request.originalStartsAt() == null
                || request.idempotencyKey() == null) {
            throw invalid(
                    "THIS_AND_FOLLOWING requires originalStartsAt and an idempotency key.");
        }
        String fingerprint = splitFingerprint(eventId, request.originalStartsAt(), request);
        occurrenceCommands.lockSplit(tenantId, userId, request.idempotencyKey());
        CalendarOccurrenceRepository.SplitReceipt replay = occurrenceCommands.splitReceipt(
                tenantId, userId, request.idempotencyKey());
        if (replay != null) {
            if (!replay.sourceEventId().equals(eventId)
                    || !replay.fingerprint().equals(fingerprint)) {
                throw conflict("The idempotency key belongs to another series split.");
            }
            CalendarRepository.EventRow existing = CalendarRepositoryRouting.event(
                            repository, tenantId, userId, personPublicId, verifiedGroupRefs,
                            replay.resultingEventId(), korean(locale))
                    .orElseThrow(() -> conflict("The series split result is unavailable."));
            return occurrenceProjector.summary(
                    tenantId, userId, personPublicId, existing, false, locale);
        }
        CalendarRepository.EventRow source = CalendarRepositoryRouting.event(
                        repository, tenantId, userId, personPublicId,
                        verifiedGroupRefs, eventId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!CalendarAccessPolicy.canEdit(source)) {
            throw new BaseException(ErrorCode.FORBIDDEN, "This event is read-only.");
        }
        if (source.recurrence() == RecurrencePattern.NONE) {
            throw invalid("A non-recurring event cannot be split.");
        }
        if (hardRecheckToken != null && !hardRecheckToken.isBlank()
                && !Objects.equals(source.organizerPersonPublicId(), personPublicId)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Calendar hard-recheck evidence is limited to the event organizer.");
        }
        occurrenceCommands.requireOccurrence(source, request.originalStartsAt());
        if (!sameAttendees(tenantId, eventId, request.attendees())) {
            throw invalid("THIS_AND_FOLLOWING preserves the series attendees.");
        }
        UUID sourceResource = source.resource() == null
                ? null : source.resource().resourceId();
        if (!Objects.equals(sourceResource, request.resourceId())) {
            throw invalid("THIS_AND_FOLLOWING preserves the series resource.");
        }
        eventValidation.validateRecurrence(
                request.startsAt(), request.timeZone(), request.recurrence(),
                request.recurrenceInterval(), request.recurrenceUntil(),
                request.recurrenceCount(), request.recurrenceDays());
        CalendarRepository.PolicyRow policy = validateEvent(
                tenantId, request.startsAt(), request.endsAt(), request.timeZone(),
                request.type(), request.description(), request.recurrence(),
                request.recurrenceInterval(), request.recurrenceUntil(),
                request.recurrenceCount(), request.recurrenceDays(), request.attendees());
        CalendarRepository.ResourceRow resource = eventValidation.validateResource(
                tenantId, request.resourceId(), request.startsAt(), request.endsAt(), eventId,
                request.timeZone(), request.recurrence(), request.recurrenceInterval(),
                request.recurrenceUntil(), request.recurrenceDays(),
                request.recurrenceCount(), locale);
        roomAccessGuard.requireBook(tenantId, userId, verifiedGroupRefs, resource);
        roomBookingPolicy.validateLockedUpdate(
                tenantId, resource, policy, eventId, request);

        CalendarDtos.CreateEventRequest following = splitCreate(source, request);
        if (source.version() != request.version()) {
            throw conflict("The recurring series changed. Refresh and try again.");
        }
        UUID followingEventId = UUID.randomUUID();
        consumeSchedulingToken(
                tenantId, userId, personPublicId, hardRecheckToken,
                request.startsAt(), request.endsAt(), request.resourceId(),
                request.timeZone(), request.type(), request.allDay(),
                request.recurrence(), request.recurrenceInterval(), request.recurrenceUntil(),
                resource == null ? null : resource.version(), policy.version(),
                request.attendees(), followingEventId);
        if (occurrenceCommands.advanceSeriesVersion(
                tenantId, eventId, request.version(), userId,
                personPublicId, verifiedGroupRefs) == 0) {
            throw conflict("The recurring series changed. Refresh and try again.");
        }
        long sourceVersion = request.version() + 1;
        ZoneId sourceZone = ZoneId.of(source.timeZone());
        LocalDate splitDate = request.originalStartsAt()
                .atZoneSameInstant(sourceZone).toLocalDate();
        boolean splitAtFirst = request.originalStartsAt().isEqual(source.startsAt());
        LocalDate sourceUntil = splitAtFirst ? null : splitDate.minusDays(1);
        if (occurrenceCommands.closeSeriesBefore(
                tenantId, eventId, sourceVersion, request.originalStartsAt(),
                sourceUntil, splitAtFirst, userId) == 0) {
            throw conflict("The recurring series could not be split safely.");
        }
        if (splitAtFirst && resource != null) {
            repository.cancelBookings(tenantId, userId, eventId);
        }
        repository.insertSplitEvent(
                tenantId, userId, source, fingerprint, following, followingEventId);
        if (resource != null) {
            repository.insertBooking(
                    tenantId, userId, followingEventId, resource,
                    request.startsAt(), request.endsAt());
        }
        int discardedOverrides = occurrenceCommands.discardOverridesAtOrAfter(
                tenantId, eventId, request.originalStartsAt());
        occurrenceCommands.saveSplitReceipt(
                tenantId, userId, request.idempotencyKey(), eventId,
                followingEventId, fingerprint, sourceVersion);
        repository.audit(
                tenantId, userId, eventId, "calendar.event.series.split", correlationId,
                eventSnapshot(source), Map.of(
                        "recurrenceUntil", sourceUntil == null ? "" : sourceUntil.toString(),
                        "resultingEventId", followingEventId,
                        "discardedOverrides", discardedOverrides,
                        "version", sourceVersion));
        repository.audit(
                tenantId, userId, followingEventId,
                "calendar.event.series.created_from_split", correlationId,
                Map.of("sourceEventId", eventId), Map.of(
                        "startsAt", request.startsAt(),
                        "endsAt", request.endsAt(),
                        "recurrence", request.recurrence().name()));
        CalendarRepository.EventRow created = CalendarRepositoryRouting.event(
                        repository, tenantId, userId, personPublicId, verifiedGroupRefs,
                        followingEventId, korean(locale))
                .orElseThrow(() -> conflict("The series split result is unavailable."));
        return occurrenceProjector.summary(
                tenantId, userId, personPublicId, created, false, locale);
    }

    private List<java.time.DayOfWeek> semanticRecurrenceDays(
            List<java.time.DayOfWeek> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().distinct().sorted().toList();
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

    CalendarRepository.PolicyRow validateEvent(
            Long tenantId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            EventType type,
            String description,
            RecurrencePattern recurrence,
            int recurrenceInterval,
            LocalDate recurrenceUntil,
            Integer recurrenceCount,
            List<java.time.DayOfWeek> recurrenceDays,
            List<CalendarDtos.AttendeeInput> attendees) {
        eventValidation.validateRecurrence(
                startsAt, timeZone, recurrence, recurrenceInterval,
                recurrenceUntil, recurrenceCount, recurrenceDays);
        return eventValidation.validateEvent(
                tenantId, startsAt, endsAt, timeZone, type, description,
                recurrence, recurrenceInterval, recurrenceUntil, recurrenceCount,
                recurrenceDays, attendees);
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

    private CalendarDtos.CreateEventRequest splitCreate(
            CalendarRepository.EventRow source,
            CalendarDtos.UpdateEventRequest request) {
        return new CalendarDtos.CreateEventRequest(
                request.title(), request.description(), request.type(),
                request.startsAt(), request.endsAt(), request.timeZone(), request.allDay(),
                request.location(), request.conferenceUrl(), request.visibility(),
                request.recurrence(), request.recurrenceInterval(), request.recurrenceUntil(),
                request.responseRequired(), request.attendees(), request.resourceId(),
                request.idempotencyKey(), source.calendarId(), request.importance(),
                request.recurrenceDays(), request.recurrenceCount());
    }

    private boolean sameAttendees(
            Long tenantId,
            UUID eventId,
            List<CalendarDtos.AttendeeInput> requested) {
        List<String> existing = repository.attendees(tenantId, eventId).stream()
                .map(value -> attendeeKey(
                        value.userId(), value.personPublicId(), value.email(),
                        value.name(), value.type()))
                .sorted()
                .toList();
        List<String> incoming = requested.stream()
                .map(value -> attendeeKey(
                        value.userId(), value.personPublicId(), value.email(),
                        value.name(), value.type()))
                .sorted()
                .toList();
        return existing.equals(incoming);
    }

    private String attendeeKey(
            Long userId,
            UUID personPublicId,
            String email,
            String name,
            AttendeeType type) {
        return Objects.toString(userId, "") + "\u001f"
                + Objects.toString(personPublicId, "") + "\u001f"
                + email.trim().toLowerCase(Locale.ROOT) + "\u001f"
                + name.trim() + "\u001f" + type.name();
    }

    private String splitFingerprint(
            UUID sourceEventId,
            OffsetDateTime originalStartsAt,
            CalendarDtos.UpdateEventRequest request) {
        CalendarDtos.CreateEventRequest following = new CalendarDtos.CreateEventRequest(
                request.title(), request.description(), request.type(),
                request.startsAt(), request.endsAt(), request.timeZone(), request.allDay(),
                request.location(), request.conferenceUrl(), request.visibility(),
                request.recurrence(), request.recurrenceInterval(), request.recurrenceUntil(),
                request.responseRequired(), request.attendees(), request.resourceId(),
                request.idempotencyKey(), null, request.importance(),
                request.recurrenceDays(), request.recurrenceCount());
        String canonical = "SERIES_SPLIT\u001f" + sourceEventId + "\u001f"
                + originalStartsAt.toInstant() + "\u001f"
                + CalendarRequestFingerprint.create(following);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void consumeSchedulingToken(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String token,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID resourceId,
            String timeZone,
            EventType eventType,
            boolean allDay,
            RecurrencePattern recurrence,
            int recurrenceInterval,
            LocalDate recurrenceUntil,
            Long resourceVersion,
            long policyVersion,
            List<CalendarDtos.AttendeeInput> attendees,
            UUID eventId) {
        if (token == null || token.isBlank()) return;
        if (schedulingV2Tokens == null) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Calendar hard-recheck validation is unavailable.");
        }
        schedulingV2Tokens.consume(
                tenantId, userId, personPublicId, token, startsAt, endsAt,
                resourceId, timeZone, eventType, allDay, recurrence,
                recurrenceInterval, recurrenceUntil, resourceVersion,
                policyVersion, attendees, eventId);
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
