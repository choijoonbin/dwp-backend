package com.dwp.services.platform.calendar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;

final class CalendarRequestFingerprint {

    private CalendarRequestFingerprint() {
    }

    static String create(CalendarDtos.CreateEventRequest request) {
        StringBuilder canonical = new StringBuilder(1024);
        append(canonical, normalized(request.title()));
        append(canonical, normalizedNullable(request.description()));
        append(canonical, request.type().name());
        append(canonical, request.startsAt().toInstant().toString());
        append(canonical, request.endsAt().toInstant().toString());
        append(canonical, request.timeZone().trim());
        append(canonical, Boolean.toString(request.allDay()));
        append(canonical, normalizedNullable(request.location()));
        append(canonical, normalizedNullable(request.conferenceUrl()));
        append(canonical, request.visibility().name());
        append(canonical, request.recurrence().name());
        append(canonical, Integer.toString(request.recurrenceInterval()));
        append(canonical, request.recurrenceUntil() == null
                ? null : request.recurrenceUntil().toString());
        append(canonical, Boolean.toString(request.responseRequired()));
        append(canonical, request.calendarId() == null
                ? null : request.calendarId().toString());
        append(canonical, (request.importance() == null
                ? CalendarTypes.EventImportance.NORMAL : request.importance()).name());
        request.attendees().stream()
                .sorted(Comparator
                        .comparing((CalendarDtos.AttendeeInput value) ->
                                value.email().trim().toLowerCase(java.util.Locale.ROOT))
                        .thenComparing(value -> value.type().name())
                        .thenComparing(value -> normalized(value.name()))
                        .thenComparing(value -> String.valueOf(value.personPublicId()))
                        .thenComparing(value -> String.valueOf(value.userId())))
                .forEach(value -> {
                    append(canonical, value.userId() == null ? null : value.userId().toString());
                    append(canonical, value.personPublicId() == null
                            ? null : value.personPublicId().toString());
                    append(canonical, value.email().trim().toLowerCase(java.util.Locale.ROOT));
                    append(canonical, normalized(value.name()));
                    append(canonical, value.type().name());
                });
        append(canonical, request.resourceId() == null ? null : request.resourceId().toString());
        return sha256(canonical);
    }

    static String scheduling(
            UUID currentPersonPublicId,
            CalendarDtos.SchedulingEvaluationRequest request) {
        StringBuilder canonical = new StringBuilder(512);
        append(canonical, currentPersonPublicId == null
                ? null : currentPersonPublicId.toString());
        request.personIds().stream()
                .filter(personId -> !personId.equals(currentPersonPublicId))
                .distinct()
                .sorted()
                .forEach(personId -> append(canonical, personId.toString()));
        append(canonical, request.from().toInstant().toString());
        append(canonical, request.to().toInstant().toString());
        append(canonical, request.roomStartsAt().toInstant().toString());
        append(canonical, request.roomEndsAt().toInstant().toString());
        append(canonical, Integer.toString(request.durationMinutes()));
        append(canonical, request.timeZone().trim());
        return sha256(canonical);
    }

    private static void append(StringBuilder value, String item) {
        if (item == null) {
            value.append("-1:");
        } else {
            value.append(item.length()).append(':').append(item);
        }
        value.append('\n');
    }

    private static String normalized(String value) {
        return value.trim();
    }

    private static String normalizedNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sha256(StringBuilder canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
