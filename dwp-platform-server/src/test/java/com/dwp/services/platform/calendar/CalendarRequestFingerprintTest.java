package com.dwp.services.platform.calendar;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarRequestFingerprintTest {

    @Test
    void attendeeOrderAndPersistedStringNormalizationAreCanonical() {
        UUID key = UUID.randomUUID();
        CalendarDtos.AttendeeInput first = attendee("B@EXAMPLE.COM", " User B ");
        CalendarDtos.AttendeeInput second = attendee("a@example.com", "User A");

        CalendarDtos.CreateEventRequest left = request(
                key, " Meeting ", "  Agenda  ", List.of(first, second));
        CalendarDtos.CreateEventRequest right = request(
                key, "Meeting", "Agenda", List.of(second, first));

        assertThat(CalendarRequestFingerprint.create(left))
                .isEqualTo(CalendarRequestFingerprint.create(right))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void materialPayloadChangesProduceDifferentFingerprints() {
        UUID key = UUID.randomUUID();
        CalendarDtos.CreateEventRequest left = request(key, "Meeting", "Agenda", List.of());
        CalendarDtos.CreateEventRequest right = request(key, "Different", "Agenda", List.of());

        assertThat(CalendarRequestFingerprint.create(left))
                .isNotEqualTo(CalendarRequestFingerprint.create(right));
    }

    private CalendarDtos.CreateEventRequest request(
            UUID key,
            String title,
            String description,
            List<CalendarDtos.AttendeeInput> attendees) {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-08-20T10:00:00+09:00");
        return new CalendarDtos.CreateEventRequest(
                title, description, CalendarTypes.EventType.MEETING,
                startsAt, startsAt.plusHours(1), "Asia/Seoul", false,
                "HQ", null, CalendarTypes.EventVisibility.DEFAULT,
                CalendarTypes.RecurrencePattern.NONE, 1, null, true,
                attendees, UUID.fromString("11111111-1111-1111-1111-111111111111"), key);
    }

    private CalendarDtos.AttendeeInput attendee(String email, String name) {
        return new CalendarDtos.AttendeeInput(
                null, UUID.randomUUID(), email, name, CalendarTypes.AttendeeType.REQUIRED);
    }
}
