package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.ResponseStatus;

final class CalendarInvitationResponseCommand {

    private CalendarInvitationResponseCommand() {
    }

    static CalendarDtos.EventSummary respond(
            CalendarRepository repository,
            CalendarRoomAccessGuard roomAccessGuard,
            CalendarOccurrenceProjector occurrenceProjector,
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.RespondRequest request) {
        if (request.response() == ResponseStatus.NEEDS_ACTION) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A final attendance response is required.");
        }
        CalendarRepository.EventRow authorized = CalendarRepositoryRouting.event(
                        repository, tenantId, actorId, actorPersonPublicId,
                        verifiedGroupRefs, eventId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        roomAccessGuard.requireView(
                tenantId, actorId, verifiedGroupRefs, authorized.resource());

        CalendarInvitationResponseRepository responses = repository.invitationResponses();
        String fingerprint = fingerprint(eventId, actorPersonPublicId, request);
        responses.lockCommand(tenantId, actorId, request.idempotencyKey());
        CalendarInvitationResponseRepository.ResponseReceipt receipt = responses.receipt(
                tenantId, actorId, request.idempotencyKey()).orElse(null);
        CalendarInvitationResponseRepository.LockedResponseState locked = responses.lockState(
                        tenantId, actorId, actorPersonPublicId, eventId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "The attendee record was not found."));

        if (receipt != null) {
            requireExactReplay(
                    receipt, locked, actorPersonPublicId, eventId,
                    request.expectedVersion(), fingerprint);
            return summary(
                    repository, occurrenceProjector, tenantId, actorId,
                    actorPersonPublicId, verifiedGroupRefs, eventId, locale);
        }
        if (locked.eventVersion() != request.expectedVersion()) {
            throw conflict("The event changed. Refresh and try again.");
        }
        long responseVersion = responses.updateAttendee(
                tenantId, locked.attendeeId(), locked.responseVersion(), request.response());
        if (responseVersion < 0) {
            throw conflict("The invitation response changed. Refresh and try again.");
        }
        long eventVersion = responses.updateEventVersion(
                tenantId, actorId, eventId, request.expectedVersion());
        if (eventVersion < 0) {
            throw conflict("The event changed. Refresh and try again.");
        }
        if (responses.insertReceipt(
                tenantId, actorId, actorPersonPublicId, request.idempotencyKey(),
                eventId, fingerprint, request.expectedVersion(), eventVersion,
                request.response(), responseVersion) != 1) {
            throw conflict("The invitation response receipt could not be recorded.");
        }
        repository.audit(
                tenantId, actorId, eventId, "calendar.attendee.responded", correlationId,
                Map.of(
                        "response", locked.response().name(),
                        "eventVersion", locked.eventVersion()),
                Map.of(
                        "response", request.response().name(),
                        "eventVersion", eventVersion));
        return summary(
                repository, occurrenceProjector, tenantId, actorId,
                actorPersonPublicId, verifiedGroupRefs, eventId, locale);
    }

    private static void requireExactReplay(
            CalendarInvitationResponseRepository.ResponseReceipt receipt,
            CalendarInvitationResponseRepository.LockedResponseState locked,
            UUID actorPersonPublicId,
            UUID eventId,
            long expectedEventVersion,
            String fingerprint) {
        if (!receipt.eventId().equals(eventId)
                || !Objects.equals(receipt.actorPersonPublicId(), actorPersonPublicId)
                || receipt.expectedEventVersion() != expectedEventVersion
                || !receipt.requestFingerprint().equals(fingerprint)) {
            throw conflict("The idempotency key was already used with a different response intent.");
        }
        if (locked.eventVersion() != receipt.resultEventVersion()) {
            throw conflict("The event changed after this response was recorded.");
        }
        if (locked.response() != receipt.resultResponse()
                || locked.responseVersion() != receipt.resultAttendeeResponseVersion()) {
            throw conflict("The invitation response changed after this response was recorded.");
        }
    }

    private static CalendarDtos.EventSummary summary(
            CalendarRepository repository,
            CalendarOccurrenceProjector occurrenceProjector,
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs,
            UUID eventId,
            String locale) {
        CalendarRepository.EventRow current = CalendarRepositoryRouting.event(
                        repository, tenantId, actorId, actorPersonPublicId,
                        verifiedGroupRefs, eventId, korean(locale))
                .orElseThrow(() -> conflict("The invitation response state is unavailable."));
        return occurrenceProjector.summary(
                tenantId, actorId, actorPersonPublicId, current, false, locale);
    }

    static String fingerprint(
            UUID eventId,
            UUID actorPersonPublicId,
            CalendarDtos.RespondRequest request) {
        String canonical = eventId + "\n"
                + (actorPersonPublicId == null ? "-" : actorPersonPublicId) + "\n"
                + request.response().name() + "\n"
                + request.expectedVersion();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean korean(String locale) {
        return locale != null && locale.toLowerCase(java.util.Locale.ROOT).startsWith("ko");
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
