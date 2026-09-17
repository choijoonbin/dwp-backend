package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceRoomAccessPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarCollaborationMapper.eventAfter;
import static com.dwp.services.platform.calendar.CalendarCollaborationMapper.eventCapabilities;
import static com.dwp.services.platform.calendar.CalendarCollaborationMapper.snapshot;

@Service
class CalendarResourceRestoreService {

    private static final int MAX_OCCURRENCES = 4000;

    private final CalendarCollaborationRepository collaboration;
    private final CalendarRestoreRepository restoreRepository;
    private final CalendarRepository calendar;
    private final CalendarRetentionRepository retention;
    private final WorkplaceRoomAccessPort roomAccess;

    CalendarResourceRestoreService(
            CalendarCollaborationRepository collaboration,
            CalendarRestoreRepository restoreRepository,
            CalendarRepository calendar,
            CalendarRetentionRepository retention,
            WorkplaceRoomAccessPort roomAccess) {
        this.collaboration = collaboration;
        this.restoreRepository = restoreRepository;
        this.calendar = calendar;
        this.retention = retention;
        this.roomAccess = roomAccess;
    }

    @Transactional
    CalendarRecoveryDtos.RestoreEventResponse restoreEvent(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID eventId,
            String correlationId,
            CalendarDtos.VersionRequest request) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        CalendarCollaborationRepository.EventDecision event = lockedManager(
                tenantId, actorId, actorPersonPublicId, verifiedGroupRefs, eventId);
        long version = request.version();
        if (event.deletedAt() == null) {
            throw conflict("The event is not available for restoration.");
        }
        if (event.version() != version) {
            throw conflict("The event changed. Refresh and try again.");
        }

        CalendarCollaborationRepository.EventMutation saved = collaboration
                .restoreEvent(tenantId, actorId, eventId, version)
                .orElseThrow(() -> conflict(
                        "The event retention window expired or the event changed."));
        retention.removeTombstone(tenantId, eventId);
        CalendarCollaborationRepository.EventDecision restored = eventAfter(event, saved);
        List<CalendarRecoveryDtos.RestoreResourceResult> resources = restoreRepository
                .restorableResourceBookings(tenantId, eventId).stream()
                .map(booking -> assess(
                        tenantId, actorId, verifiedGroupRefs, eventId, booking))
                .toList();
        RestoreSummary summary = summarize(resources);
        calendar.audit(
                tenantId,
                actorId,
                eventId,
                "calendar.event.restored",
                correlationId,
                snapshot(
                        "deletedAt", event.deletedAt(),
                        "purgeAfter", event.purgeAfter(),
                        "legalHold", event.legalHold(),
                        "version", event.version()),
                snapshot(
                        "deletedAt", saved.deletedAt(),
                        "restoreOutcome", summary.outcome().name(),
                        "restoreReason", summary.reason().name(),
                        "resourceCount", resources.size(),
                        "version", saved.version()));
        return response(restored, summary, resources);
    }

    @Transactional
    CalendarRecoveryDtos.RestoreEventResponse rebookResource(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID eventId,
            String correlationId,
            CalendarRecoveryDtos.RestoreResourceBookingRequest request) {
        requireActor(tenantId, actorId, actorPersonPublicId);
        CalendarCollaborationRepository.EventDecision event = lockedManager(
                tenantId, actorId, actorPersonPublicId, verifiedGroupRefs, eventId);
        if (event.deletedAt() != null || "CANCELLED".equals(event.status())) {
            throw conflict("Restore the event before rebooking its resource.");
        }

        restoreRepository.lockResourceRebookCommand(
                tenantId, actorId, request.idempotencyKey());
        String fingerprint = fingerprint(eventId, request);
        CalendarRestoreRepository.ResourceRebookCommandRow replay = restoreRepository
                .resourceRebookCommand(tenantId, actorId, request.idempotencyKey())
                .orElse(null);
        if (replay != null) {
            requireReplayMatch(replay, eventId, request.resourceId(), fingerprint);
            if (event.version() != replay.eventVersion()) {
                throw conflict("The event changed after the resource rebook command.");
            }
            return response(
                    event,
                    new RestoreSummary(replay.outcome(), replay.reason()),
                    List.of(new CalendarRecoveryDtos.RestoreResourceResult(
                            replay.resourceId(),
                            replay.bookingVersion(),
                            replay.outcome(),
                            replay.reason(),
                            false)));
        }
        if (event.version() != request.eventVersion()) {
            throw conflict("The event changed. Refresh and try again.");
        }

        calendar.lockResource(tenantId, request.resourceId());
        CalendarRestoreRepository.RestoreBookingRow booking = restoreRepository
                .restorableResourceBookingForUpdate(
                        tenantId, eventId, request.resourceId())
                .orElseThrow(() -> conflict(
                        "The prior trash-cancelled resource booking is unavailable."));
        if (booking.bookingVersion() != request.bookingVersion()) {
            throw conflict("The resource booking changed. Refresh and try again.");
        }

        CalendarRecoveryDtos.RestoreResourceResult eligibility = assess(
                tenantId, actorId, verifiedGroupRefs, eventId, booking);
        if (!eligibility.canRebook()) {
            saveReceipt(
                    tenantId, actorId, eventId, request, fingerprint,
                    eligibility.outcome(), eligibility.reason(),
                    event.version(), booking.bookingVersion());
            auditRebook(
                    tenantId, actorId, eventId, correlationId, request.resourceId(),
                    eligibility.outcome(), eligibility.reason(), booking.bookingVersion(), false);
            return response(
                    event,
                    new RestoreSummary(eligibility.outcome(), eligibility.reason()),
                    List.of(eligibility));
        }

        long savedBookingVersion = restoreRepository.rebookResource(
                        tenantId,
                        actorId,
                        eventId,
                        request.resourceId(),
                        booking.bookingVersion(),
                        booking.approvalRequired())
                .orElseThrow(() -> conflict(
                        "The resource booking changed. Refresh and try again."));
        boolean additionalResourcesRemain = !restoreRepository
                .restorableResourceBookings(tenantId, eventId).isEmpty();
        CalendarRecoveryDtos.RestoreOutcome outcome = booking.approvalRequired()
                ? CalendarRecoveryDtos.RestoreOutcome.EVENT_AND_RESOURCE_REBOOK_REQUESTED
                : !additionalResourcesRemain
                ? CalendarRecoveryDtos.RestoreOutcome.EVENT_AND_RESOURCES_RESTORED
                : CalendarRecoveryDtos.RestoreOutcome.EVENT_AND_RESOURCES_PARTIALLY_RESTORED;
        CalendarRecoveryDtos.RestoreReason reason = booking.approvalRequired()
                ? CalendarRecoveryDtos.RestoreReason.RESOURCE_APPROVAL_REQUIRED
                : !additionalResourcesRemain
                ? CalendarRecoveryDtos.RestoreReason.RESOURCE_REBOOKED
                : CalendarRecoveryDtos.RestoreReason.ADDITIONAL_RESOURCES_REQUIRE_REBOOK;
        List<CalendarRecoveryDtos.RestoreResourceResult> resources = List.of(
                new CalendarRecoveryDtos.RestoreResourceResult(
                request.resourceId(),
                savedBookingVersion,
                outcome,
                reason,
                false));
        saveReceipt(
                tenantId, actorId, eventId, request, fingerprint,
                outcome, reason, event.version(), savedBookingVersion);
        auditRebook(
                tenantId, actorId, eventId, correlationId, request.resourceId(),
                outcome, reason, savedBookingVersion, true);
        return response(event, new RestoreSummary(outcome, reason), resources);
    }

    private CalendarRecoveryDtos.RestoreResourceResult assess(
            Long tenantId,
            Long actorId,
            String verifiedGroupRefs,
            UUID eventId,
            CalendarRestoreRepository.RestoreBookingRow booking) {
        if (!"CANCELLED".equals(booking.bookingStatus())) {
            return result(booking, CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_UNAVAILABLE,
                    CalendarRecoveryDtos.RestoreReason.RESOURCE_NOT_AVAILABLE, false);
        }
        try {
            roomAccess.requireBook(
                    tenantId, actorId, verifiedGroupRefs, booking.resourceId());
        } catch (BaseException exception) {
            if (exception.getErrorCode() != ErrorCode.FORBIDDEN) throw exception;
            return result(
                    booking,
                    CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_ACCESS_REVOKED,
                    CalendarRecoveryDtos.RestoreReason.RESOURCE_ACCESS_REVOKED,
                    false);
        }
        if (booking.resourceState() != CalendarTypes.ResourceState.AVAILABLE
                || (calendar.isWorkplaceManagedResource(tenantId, booking.resourceId())
                && !calendar.isWorkplaceResourceBookable(tenantId, booking.resourceId()))) {
            return result(
                    booking,
                    CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_UNAVAILABLE,
                    CalendarRecoveryDtos.RestoreReason.RESOURCE_NOT_AVAILABLE,
                    false);
        }

        List<BookingWindow> windows;
        try {
            windows = eligibleWindows(tenantId, booking);
        } catch (RestorePolicyException exception) {
            return result(
                    booking,
                    CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_UNAVAILABLE,
                    exception.reason(),
                    false);
        }
        int bufferMinutes = calendar.policy(tenantId).defaultBufferMinutes();
        boolean conflict = windows.stream().anyMatch(window ->
                calendar.facilityClosureConflict(
                        tenantId, booking.resourceId(),
                        window.startsAt(), window.endsAt())
                        || calendar.resourceConflict(
                        tenantId,
                        booking.resourceId(),
                        window.startsAt().minusMinutes(bufferMinutes),
                        window.endsAt().plusMinutes(bufferMinutes),
                        eventId));
        if (conflict) {
            return result(
                    booking,
                    CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_CONFLICT,
                    CalendarRecoveryDtos.RestoreReason.RESOURCE_TIME_CONFLICT,
                    false);
        }
        return result(
                booking,
                CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_REBOOK_REQUIRED,
                CalendarRecoveryDtos.RestoreReason.EXPLICIT_REBOOK_REQUIRED,
                true);
    }

    private List<BookingWindow> eligibleWindows(
            Long tenantId,
            CalendarRestoreRepository.RestoreBookingRow booking) {
        CalendarRepository.PolicyRow policy = calendar.policy(tenantId);
        Duration duration = Duration.between(booking.startsAt(), booking.endsAt());
        long durationMinutes = duration.toMinutes();
        if (duration.isNegative() || duration.isZero()
                || durationMinutes < policy.minimumEventMinutes()
                || durationMinutes > policy.maximumEventMinutes()) {
            throw policy(CalendarRecoveryDtos.RestoreReason.RESOURCE_POLICY_BLOCKED);
        }
        try {
            ZoneId eventZone = ZoneId.of(booking.timeZone());
            ZoneId resourceZone = ZoneId.of(booking.resourceTimeZone());
            if (booking.resourceType() == CalendarTypes.ResourceType.ROOM
                    && (booking.eventType() != CalendarTypes.EventType.MEETING
                    || booking.allDay()
                    || !eventZone.equals(resourceZone)
                    || booking.attendeeCount() > booking.capacity())) {
                throw policy(CalendarRecoveryDtos.RestoreReason.RESOURCE_POLICY_BLOCKED);
            }
            List<BookingWindow> future = futureWindows(booking, eventZone);
            LocalDate maximumDate = LocalDate.now(resourceZone)
                    .plusDays(policy.maximumAdvanceDays());
            for (BookingWindow window : future) {
                ZonedDateTime localStart = window.startsAt().atZoneSameInstant(resourceZone);
                ZonedDateTime localEnd = window.endsAt().atZoneSameInstant(resourceZone);
                if (localStart.toLocalDate().isAfter(maximumDate)
                        || (booking.resourceType() == CalendarTypes.ResourceType.ROOM
                        && (!localStart.toLocalDate().equals(localEnd.toLocalDate())
                        || localStart.toLocalTime().isBefore(policy.workingDayStart())
                        || localEnd.toLocalTime().isAfter(policy.workingDayEnd())))) {
                    throw policy(CalendarRecoveryDtos.RestoreReason.RESOURCE_POLICY_BLOCKED);
                }
            }
            return future;
        } catch (DateTimeException exception) {
            throw policy(CalendarRecoveryDtos.RestoreReason.RESOURCE_POLICY_BLOCKED);
        }
    }

    private List<BookingWindow> futureWindows(
            CalendarRestoreRepository.RestoreBookingRow booking,
            ZoneId eventZone) {
        List<BookingWindow> result = new ArrayList<>();
        OffsetDateTime current = booking.startsAt();
        Duration duration = Duration.between(booking.startsAt(), booking.endsAt());
        LocalDate lastDate = booking.recurrenceUntil() == null
                ? current.atZoneSameInstant(eventZone).toLocalDate()
                : booking.recurrenceUntil();
        int guard = 0;
        while (!current.atZoneSameInstant(eventZone).toLocalDate().isAfter(lastDate)
                && guard++ < MAX_OCCURRENCES) {
            OffsetDateTime end = current.plus(duration);
            if (end.isAfter(OffsetDateTime.now())) {
                result.add(new BookingWindow(current, end));
            }
            if (booking.recurrence() == CalendarTypes.RecurrencePattern.NONE) break;
            current = increment(
                    current,
                    booking.recurrence(),
                    booking.recurrenceInterval(),
                    eventZone);
        }
        if (guard >= MAX_OCCURRENCES || result.isEmpty()) {
            throw policy(CalendarRecoveryDtos.RestoreReason.EVENT_TIME_IS_PAST);
        }
        return result;
    }

    private OffsetDateTime increment(
            OffsetDateTime value,
            CalendarTypes.RecurrencePattern recurrence,
            int interval,
            ZoneId zone) {
        ZonedDateTime local = value.atZoneSameInstant(zone);
        return switch (recurrence) {
            case DAILY -> local.plusDays(interval).toOffsetDateTime();
            case WEEKLY -> local.plusWeeks(interval).toOffsetDateTime();
            case MONTHLY -> local.plusMonths(interval).toOffsetDateTime();
            case NONE -> value;
        };
    }

    private RestoreSummary summarize(List<CalendarRecoveryDtos.RestoreResourceResult> resources) {
        if (resources.isEmpty()) {
            return new RestoreSummary(
                    CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_NO_PRIOR_RESOURCE,
                    CalendarRecoveryDtos.RestoreReason.NO_PRIOR_RESOURCE);
        }
        CalendarRecoveryDtos.RestoreResourceResult primary = resources.stream()
                .filter(value -> value.outcome()
                        == CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_ACCESS_REVOKED)
                .findFirst()
                .or(() -> resources.stream().filter(value -> value.outcome()
                        == CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_CONFLICT)
                        .findFirst())
                .or(() -> resources.stream().filter(value -> value.outcome()
                        == CalendarRecoveryDtos.RestoreOutcome.EVENT_ONLY_RESOURCE_UNAVAILABLE)
                        .findFirst())
                .orElse(resources.getFirst());
        return new RestoreSummary(primary.outcome(), primary.reason());
    }

    private CalendarRecoveryDtos.RestoreResourceResult result(
            CalendarRestoreRepository.RestoreBookingRow booking,
            CalendarRecoveryDtos.RestoreOutcome outcome,
            CalendarRecoveryDtos.RestoreReason reason,
            boolean canRebook) {
        return new CalendarRecoveryDtos.RestoreResourceResult(
                booking.resourceId(), booking.bookingVersion(), outcome, reason, canRebook);
    }

    private CalendarRecoveryDtos.RestoreEventResponse response(
            CalendarCollaborationRepository.EventDecision event,
            RestoreSummary summary,
            List<CalendarRecoveryDtos.RestoreResourceResult> resources) {
        return new CalendarRecoveryDtos.RestoreEventResponse(
                eventCapabilities(event),
                summary.outcome(),
                summary.reason(),
                event.version(),
                resources);
    }

    private CalendarCollaborationRepository.EventDecision lockedManager(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID eventId) {
        UUID calendarId = collaboration.eventCalendarId(tenantId, eventId)
                .orElseThrow(this::notFound);
        if (!collaboration.lockCalendar(tenantId, calendarId)) throw notFound();
        CalendarCollaborationRepository.EventDecision event = collaboration
                .eventDecisionForUpdate(
                        tenantId,
                        actorId,
                        actorPersonPublicId,
                        CalendarVerifiedGroups.databaseArray(verifiedGroupRefs),
                        eventId)
                .orElseThrow(this::notFound);
        if (!calendarId.equals(event.calendarId())) throw notFound();
        if (!event.canManage()) throw forbidden();
        return event;
    }

    private void requireActor(Long tenantId, Long actorId, UUID actorPersonPublicId) {
        if (tenantId == null || tenantId < 1
                || actorId == null || actorId < 1
                || actorPersonPublicId == null
                || !collaboration.verifiedActor(
                tenantId, actorId, actorPersonPublicId)) {
            throw forbidden();
        }
    }

    private void requireReplayMatch(
            CalendarRestoreRepository.ResourceRebookCommandRow replay,
            UUID eventId,
            UUID resourceId,
            String fingerprint) {
        if (!eventId.equals(replay.eventId())
                || !resourceId.equals(replay.resourceId())
                || !fingerprint.equals(replay.requestFingerprint())) {
            throw conflict("The idempotency key was used for a different resource rebook.");
        }
    }

    private void saveReceipt(
            Long tenantId,
            Long actorId,
            UUID eventId,
            CalendarRecoveryDtos.RestoreResourceBookingRequest request,
            String fingerprint,
            CalendarRecoveryDtos.RestoreOutcome outcome,
            CalendarRecoveryDtos.RestoreReason reason,
            long eventVersion,
            long bookingVersion) {
        restoreRepository.saveResourceRebookCommand(
                tenantId,
                actorId,
                request.idempotencyKey(),
                eventId,
                request.resourceId(),
                fingerprint,
                outcome,
                reason,
                eventVersion,
                bookingVersion);
    }

    private void auditRebook(
            Long tenantId,
            Long actorId,
            UUID eventId,
            String correlationId,
            UUID resourceId,
            CalendarRecoveryDtos.RestoreOutcome outcome,
            CalendarRecoveryDtos.RestoreReason reason,
            long bookingVersion,
            boolean accepted) {
        calendar.audit(
                tenantId,
                actorId,
                eventId,
                accepted
                        ? "calendar.event.resource.rebooked"
                        : "calendar.event.resource.rebook.rejected",
                correlationId,
                snapshot("resourceId", resourceId),
                snapshot(
                        "resourceId", resourceId,
                        "restoreOutcome", outcome.name(),
                        "restoreReason", reason.name(),
                        "bookingVersion", bookingVersion));
    }

    private String fingerprint(
            UUID eventId, CalendarRecoveryDtos.RestoreResourceBookingRequest request) {
        String canonical = eventId + "|" + request.resourceId() + "|"
                + request.eventVersion() + "|" + request.bookingVersion();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private RestorePolicyException policy(CalendarRecoveryDtos.RestoreReason reason) {
        return new RestorePolicyException(reason);
    }

    void cancelBookingsForTrash(Long tenantId, Long actorId, UUID eventId) {
        restoreRepository.cancelResourceBookingsForTrash(tenantId, actorId, eventId);
    }

    private BaseException forbidden() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "The current member cannot manage this calendar resource.");
    }

    private BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private record RestoreSummary(
            CalendarRecoveryDtos.RestoreOutcome outcome,
            CalendarRecoveryDtos.RestoreReason reason) {
    }

    private record BookingWindow(OffsetDateTime startsAt, OffsetDateTime endsAt) {
    }

    private static final class RestorePolicyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final CalendarRecoveryDtos.RestoreReason reason;

        private RestorePolicyException(CalendarRecoveryDtos.RestoreReason reason) {
            this.reason = reason;
        }

        private CalendarRecoveryDtos.RestoreReason reason() {
            return reason;
        }
    }
}
