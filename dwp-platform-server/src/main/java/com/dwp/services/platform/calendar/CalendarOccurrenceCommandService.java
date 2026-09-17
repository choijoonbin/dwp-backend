package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.EventImportance;
import static com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern;

@Service
final class CalendarOccurrenceCommandService {

    private static final int MAX_OCCURRENCES = 4000;

    private final CalendarRepository repository;
    private final CalendarOccurrenceRepository occurrences;
    private final CalendarOccurrenceProjector projector;
    private final CalendarEventValidation validation;

    CalendarOccurrenceCommandService(
            CalendarRepository repository,
            CalendarOccurrenceRepository occurrences,
            CalendarOccurrenceProjector projector,
            CalendarSchedulingHorizon schedulingHorizon) {
        this.repository = repository;
        this.occurrences = occurrences;
        this.projector = projector;
        this.validation = new CalendarEventValidation(repository, projector, schedulingHorizon);
    }

    CalendarDtos.EventSummary updateOccurrence(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.UpdateEventRequest request) {
        requireCommand(request);
        CalendarRepository.EventRow before = CalendarRepositoryRouting.event(
                        repository, tenantId, actorId, actorPersonPublicId,
                        verifiedGroupRefs, eventId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!CalendarAccessPolicy.canEdit(before)) {
            throw new BaseException(ErrorCode.FORBIDDEN, "This event is read-only.");
        }
        requireSeriesOwnedFields(before, tenantId, request);
        requireOccurrence(before, request.originalStartsAt());

        occurrences.lockCommand(tenantId, actorId, request.idempotencyKey());
        String fingerprint = fingerprint(eventId, request);
        CalendarOccurrenceRepository.CommandReceipt replay = occurrences.receipt(
                tenantId, actorId, request.idempotencyKey()).orElse(null);
        if (replay != null) {
            requireReplay(replay, eventId, request.originalStartsAt(), fingerprint, before.version());
            CalendarOccurrenceRepository.OverrideRow override = occurrences.override(
                            tenantId, eventId, request.originalStartsAt())
                    .orElseThrow(() -> conflict("The occurrence edit receipt is incomplete."));
            if (override.version() != replay.overrideVersion()) {
                throw conflict("The occurrence changed after this command completed.");
            }
            return projector.summaryForOccurrence(
                    tenantId, actorId, actorPersonPublicId, before, locale,
                    override.originalStartsAt(), override);
        }
        if (before.version() != request.version()) {
            throw conflict("The recurring series changed. Refresh and try again.");
        }

        validation.validateEvent(
                tenantId, request.startsAt(), request.endsAt(), request.timeZone(),
                request.type(), request.description(), RecurrencePattern.NONE,
                null, request.attendees());
        if (occurrences.advanceEventVersion(
                tenantId, eventId, request.version(), actorId) == 0) {
            throw conflict("The recurring series changed. Refresh and try again.");
        }
        long overrideVersion = occurrences.upsertModified(
                tenantId, actorId, eventId, request.originalStartsAt(), request);
        long eventVersion = request.version() + 1;
        occurrences.saveReceipt(
                tenantId, actorId, request.idempotencyKey(), eventId,
                request.originalStartsAt(), fingerprint, eventVersion, overrideVersion);
        repository.audit(
                tenantId, actorId, eventId, "calendar.event.occurrence.updated", correlationId,
                snapshot(
                        "originalStartsAt", request.originalStartsAt(),
                        "seriesVersion", request.version()),
                snapshot(
                        "startsAt", request.startsAt(),
                        "endsAt", request.endsAt(),
                        "title", request.title(),
                        "seriesVersion", eventVersion,
                        "overrideVersion", overrideVersion));

        CalendarRepository.EventRow updated = CalendarRepositoryRouting.event(
                        repository, tenantId, actorId, actorPersonPublicId,
                        verifiedGroupRefs, eventId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        CalendarOccurrenceRepository.OverrideRow override = occurrences.override(
                        tenantId, eventId, request.originalStartsAt())
                .orElseThrow(() -> conflict("The occurrence edit did not materialize."));
        return projector.summaryForOccurrence(
                tenantId, actorId, actorPersonPublicId, updated, locale,
                override.originalStartsAt(), override);
    }

    int discardOverridesAfterSeriesScheduleChange(long tenantId, UUID eventId) {
        return occurrences.deleteOverrides(tenantId, eventId);
    }

    private void requireCommand(CalendarDtos.UpdateEventRequest request) {
        if (request.editScope() != CalendarDtos.RecurrenceEditScope.THIS_OCCURRENCE
                || request.originalStartsAt() == null
                || request.idempotencyKey() == null) {
            throw invalid("Occurrence scope, original start, and idempotency key are required.");
        }
    }

    private void requireSeriesOwnedFields(
            CalendarRepository.EventRow before,
            long tenantId,
            CalendarDtos.UpdateEventRequest request) {
        if (before.recurrence() == RecurrencePattern.NONE) {
            throw invalid("A non-recurring event has no occurrence scope.");
        }
        if (!Objects.equals(before.timeZone(), request.timeZone())
                || before.recurrence() != request.recurrence()
                || before.recurrenceInterval() != request.recurrenceInterval()
                || !Objects.equals(before.recurrenceUntil(), request.recurrenceUntil())
                || before.type() != request.type()) {
            throw invalid("Time zone, recurrence, and event type are owned by the series.");
        }
        UUID currentResource = before.resource() == null ? null : before.resource().resourceId();
        if (currentResource != null || request.resourceId() != null) {
            throw invalid("Resource-backed recurring events must be edited as a series.");
        }
        if (!attendees(tenantId, before.eventId()).equals(attendees(request.attendees()))) {
            throw invalid("Attendees are owned by the recurring series.");
        }
    }

    private void requireOccurrence(
            CalendarRepository.EventRow event,
            OffsetDateTime requestedOriginalStart) {
        OffsetDateTime cursor = event.startsAt();
        int guard = 0;
        while (guard++ < MAX_OCCURRENCES) {
            if (cursor.isEqual(requestedOriginalStart)) return;
            if (cursor.isAfter(requestedOriginalStart)
                    || (event.recurrenceUntil() != null
                    && cursor.toLocalDate().isAfter(event.recurrenceUntil()))) break;
            cursor = projector.increment(
                    cursor, event.recurrence(), event.recurrenceInterval(), event.timeZone());
        }
        throw invalid("The selected occurrence does not belong to this recurring series.");
    }

    private List<String> attendees(long tenantId, UUID eventId) {
        return repository.attendees(tenantId, eventId).stream()
                .map(value -> attendee(
                        value.userId(), value.personPublicId(), value.email(),
                        value.name(), value.type().name()))
                .sorted()
                .toList();
    }

    private List<String> attendees(List<CalendarDtos.AttendeeInput> values) {
        return values.stream()
                .map(value -> attendee(
                        value.userId(), value.personPublicId(), value.email(),
                        value.name(), value.type().name()))
                .sorted()
                .toList();
    }

    private String attendee(Long userId, UUID personId, String email, String name, String type) {
        return Objects.toString(userId, "") + "|" + Objects.toString(personId, "") + "|"
                + email.trim().toLowerCase(Locale.ROOT) + "|" + name.trim() + "|" + type;
    }

    private String fingerprint(UUID eventId, CalendarDtos.UpdateEventRequest request) {
        List<String> values = new ArrayList<>();
        values.add(eventId.toString());
        values.add(request.originalStartsAt().toInstant().toString());
        values.add(request.title().trim());
        values.add(Objects.toString(request.description(), ""));
        values.add(request.type().name());
        values.add(request.startsAt().toInstant().toString());
        values.add(request.endsAt().toInstant().toString());
        values.add(request.timeZone());
        values.add(Boolean.toString(request.allDay()));
        values.add(Objects.toString(request.location(), ""));
        values.add(Objects.toString(request.conferenceUrl(), ""));
        values.add(request.visibility().name());
        values.add(Boolean.toString(request.responseRequired()));
        values.add((request.importance() == null
                ? EventImportance.NORMAL : request.importance()).name());
        values.addAll(attendees(request.attendees()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    String.join("\u001f", values).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void requireReplay(
            CalendarOccurrenceRepository.CommandReceipt replay,
            UUID eventId,
            OffsetDateTime originalStartsAt,
            String fingerprint,
            long currentEventVersion) {
        if (!replay.eventId().equals(eventId)
                || !replay.originalStartsAt().isEqual(originalStartsAt)
                || !replay.fingerprint().equals(fingerprint)) {
            throw conflict("The idempotency key belongs to another occurrence command.");
        }
        if (replay.eventVersion() != currentEventVersion) {
            throw conflict("The recurring series changed after this command completed.");
        }
    }

    private Map<String, Object> snapshot(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
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
