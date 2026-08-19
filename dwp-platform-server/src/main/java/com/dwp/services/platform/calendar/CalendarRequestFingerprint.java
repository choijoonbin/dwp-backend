package com.dwp.services.platform.calendar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

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
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
}
