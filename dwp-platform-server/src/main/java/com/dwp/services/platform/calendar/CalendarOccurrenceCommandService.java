package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
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
    private final CalendarRecurrenceEngine recurrence = new CalendarRecurrenceEngine();
    private CalendarSchedulingV2TokenVerifier schedulingV2Tokens;

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

    @Autowired(required = false)
    void setSchedulingV2Tokens(CalendarSchedulingV2TokenVerifier schedulingV2Tokens) {
        this.schedulingV2Tokens = schedulingV2Tokens;
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
        return updateOccurrence(
                tenantId, actorId, actorPersonPublicId, eventId, locale,
                correlationId, verifiedGroupRefs, request, null);
    }

    CalendarDtos.EventSummary updateOccurrence(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.UpdateEventRequest request,
            String hardRecheckToken) {
        requireCommand(request);
        CalendarRepository.EventRow before = CalendarRepositoryRouting.event(
                        repository, tenantId, actorId, actorPersonPublicId,
                        verifiedGroupRefs, eventId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!CalendarAccessPolicy.canEdit(before)) {
            throw new BaseException(ErrorCode.FORBIDDEN, "This event is read-only.");
        }
        if (hardRecheckToken != null && !hardRecheckToken.isBlank()
                && !Objects.equals(before.organizerPersonPublicId(), actorPersonPublicId)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Calendar hard-recheck evidence is limited to the event organizer.");
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
        consumeSchedulingToken(
                tenantId, actorId, actorPersonPublicId, hardRecheckToken,
                request, eventId);
        if (occurrences.advanceEventVersion(
                tenantId, eventId, request.version(), actorId,
                actorPersonPublicId, verifiedGroupRefs) == 0) {
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

    void lockSplit(long tenantId, long actorId, UUID idempotencyKey) {
        occurrences.lockCommand(tenantId, actorId, idempotencyKey);
    }

    CalendarOccurrenceRepository.SplitReceipt splitReceipt(
            long tenantId, long actorId, UUID idempotencyKey) {
        return occurrences.splitReceipt(tenantId, actorId, idempotencyKey).orElse(null);
    }

    int advanceSeriesVersion(
            long tenantId,
            UUID eventId,
            long expectedVersion,
            long actorId,
            UUID actorPersonPublicId,
            String verifiedGroupRefs) {
        return occurrences.advanceEventVersion(
                tenantId, eventId, expectedVersion, actorId,
                actorPersonPublicId, verifiedGroupRefs);
    }

    int closeSeriesBefore(
            long tenantId,
            UUID eventId,
            long advancedVersion,
            OffsetDateTime originalStartsAt,
            java.time.LocalDate until,
            boolean splitAtFirst,
            long actorId) {
        return occurrences.closeSeriesBefore(
                tenantId, eventId, advancedVersion, originalStartsAt,
                until, splitAtFirst, actorId);
    }

    int discardOverridesAtOrAfter(
            long tenantId, UUID eventId, OffsetDateTime originalStartsAt) {
        return occurrences.deleteOverridesAtOrAfter(tenantId, eventId, originalStartsAt);
    }

    void saveSplitReceipt(
            long tenantId,
            long actorId,
            UUID idempotencyKey,
            UUID sourceEventId,
            UUID resultingEventId,
            String fingerprint,
            long resultingSourceVersion) {
        occurrences.saveSplitReceipt(
                tenantId, actorId, idempotencyKey, sourceEventId,
                resultingEventId, fingerprint, resultingSourceVersion);
    }

    void cancelOccurrence(
            Long tenantId,
            Long actorId,
            UUID actorPersonPublicId,
            UUID eventId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            CalendarDtos.CancelOccurrenceRequest request) {
        CalendarRepository.EventRow before = CalendarRepositoryRouting.event(
                        repository, tenantId, actorId, actorPersonPublicId,
                        verifiedGroupRefs, eventId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!CalendarAccessPolicy.canEdit(before)) {
            throw new BaseException(ErrorCode.FORBIDDEN, "This event is read-only.");
        }
        requireOccurrence(before, request.originalStartsAt());
        occurrences.lockCommand(tenantId, actorId, request.idempotencyKey());
        String fingerprint = cancelFingerprint(eventId, request.originalStartsAt());
        CalendarOccurrenceRepository.CommandReceipt replay = occurrences.receipt(
                tenantId, actorId, request.idempotencyKey()).orElse(null);
        if (replay != null) {
            requireReplay(
                    replay, eventId, request.originalStartsAt(), fingerprint, before.version());
            CalendarOccurrenceRepository.OverrideRow override = occurrences.override(
                            tenantId, eventId, request.originalStartsAt())
                    .orElseThrow(() -> conflict("The occurrence cancellation is incomplete."));
            if (!"CANCELLED".equals(override.kind())
                    || override.version() != replay.overrideVersion()) {
                throw conflict("The cancelled occurrence changed after this command completed.");
            }
            return;
        }
        if (before.version() != request.version()) {
            throw conflict("The recurring series changed. Refresh and try again.");
        }
        if (occurrences.advanceEventVersion(
                tenantId, eventId, request.version(), actorId,
                actorPersonPublicId, verifiedGroupRefs) == 0) {
            throw conflict("The recurring series changed. Refresh and try again.");
        }
        long overrideVersion = occurrences.upsertCancelled(
                tenantId, actorId, eventId, request.originalStartsAt());
        long eventVersion = request.version() + 1;
        occurrences.saveReceipt(
                tenantId, actorId, request.idempotencyKey(), eventId,
                request.originalStartsAt(), fingerprint, eventVersion, overrideVersion);
        repository.audit(
                tenantId, actorId, eventId, "calendar.event.occurrence.cancelled", correlationId,
                snapshot(
                        "originalStartsAt", request.originalStartsAt(),
                        "seriesVersion", request.version()),
                snapshot(
                        "overrideKind", "CANCELLED",
                        "seriesVersion", eventVersion,
                        "overrideVersion", overrideVersion));
    }

    private void requireCommand(CalendarDtos.UpdateEventRequest request) {
        if (request.editScope() != CalendarDtos.RecurrenceEditScope.THIS_OCCURRENCE
                || request.originalStartsAt() == null
                || request.idempotencyKey() == null) {
            throw invalid("Occurrence scope, original start, and idempotency key are required.");
        }
    }

    private void consumeSchedulingToken(
            long tenantId,
            long actorId,
            UUID actorPersonPublicId,
            String token,
            CalendarDtos.UpdateEventRequest request,
            UUID eventId) {
        if (token == null || token.isBlank()) return;
        if (schedulingV2Tokens == null) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Calendar hard-recheck validation is unavailable.");
        }
        schedulingV2Tokens.consume(
                tenantId, actorId, actorPersonPublicId, token,
                request.startsAt(), request.endsAt(), null,
                request.timeZone(), request.type(), request.allDay(),
                RecurrencePattern.NONE, 1, null, null,
                repository.policy(tenantId).version(), request.attendees(), eventId);
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
                || !Objects.equals(before.recurrenceDays(), normalizedDays(request.recurrenceDays()))
                || !Objects.equals(before.recurrenceCount(), request.recurrenceCount())
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

    void requireOccurrence(
            CalendarRepository.EventRow event,
            OffsetDateTime requestedOriginalStart) {
        CalendarRecurrenceEngine.Rule rule = recurrence.rule(
                event.recurrence(), event.recurrenceInterval(), event.recurrenceUntil(),
                event.recurrenceCount(), recurrenceDays(event.recurrenceDays()), event.timeZone());
        if (recurrence.contains(
                event.startsAt(), requestedOriginalStart, rule, MAX_OCCURRENCES)) return;
        throw invalid("The selected occurrence does not belong to this recurring series.");
    }

    private EnumSet<java.time.DayOfWeek> recurrenceDays(
            List<java.time.DayOfWeek> values) {
        return values == null || values.isEmpty()
                ? EnumSet.noneOf(java.time.DayOfWeek.class)
                : EnumSet.copyOf(values);
    }

    private List<String> attendees(long tenantId, UUID eventId) {
        return repository.attendees(tenantId, eventId).stream()
                .map(value -> attendee(
                        value.userId(), value.personPublicId(), value.email(),
                        value.name(), value.type().name()))
                .sorted()
                .toList();
    }

    private List<java.time.DayOfWeek> normalizedDays(List<java.time.DayOfWeek> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().distinct().sorted().toList();
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
        values.add(request.recurrenceDays() == null ? "" : request.recurrenceDays().stream()
                .distinct().sorted().map(Enum::name).collect(java.util.stream.Collectors.joining(",")));
        values.add(Objects.toString(request.recurrenceCount(), ""));
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

    private String cancelFingerprint(UUID eventId, OffsetDateTime originalStartsAt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((
                    "CANCEL\u001f" + eventId + "\u001f" + originalStartsAt.toInstant())
                    .getBytes(StandardCharsets.UTF_8)));
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
