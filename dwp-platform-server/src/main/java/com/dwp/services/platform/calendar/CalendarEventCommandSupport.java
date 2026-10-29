package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoff;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoffOutboxRepository;
import com.dwp.services.platform.mail.MailProposalHandoffBinding;
import com.dwp.services.platform.mail.MailProposalOutcomePort;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
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

/** Event create, update, and recurrence-split command orchestration shared by CalendarService. */
abstract class CalendarEventCommandSupport {

    final CalendarRepository repository;
    final CalendarOccurrenceProjector occurrenceProjector;
    final CalendarEventValidation eventValidation;
    final CalendarRoomAccessGuard roomAccessGuard;
    final RoomBookingPolicyService roomBookingPolicy;
    final CalendarMailProposalBridge mailProposals;
    final CalendarOccurrenceCommandService occurrenceCommands;
    PlatformDwaionHandoffOutboxRepository dwaionHandoffs;
    CalendarSchedulingV2TokenVerifier schedulingV2Tokens;

    CalendarEventCommandSupport(
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
        this.roomBookingPolicy = roomBookingPolicy;
        this.mailProposals = new CalendarMailProposalBridge(mailProposalOutcomes);
        this.occurrenceCommands = occurrenceCommands;
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

    final void completeDwaion(
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

    final CalendarDtos.EventSummary splitFollowing(
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

    final List<java.time.DayOfWeek> semanticRecurrenceDays(
            List<java.time.DayOfWeek> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().distinct().sorted().toList();
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

    final Map<String, Object> eventSnapshot(CalendarRepository.EventRow value) {
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

    final CalendarDtos.CreateEventRequest splitCreate(
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

    final boolean sameAttendees(
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

    final String attendeeKey(
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

    final String splitFingerprint(
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

    final void consumeSchedulingToken(
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

    final boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    final BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    final BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

}
