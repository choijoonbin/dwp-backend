package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarEventsV2CursorTest {

    private static final String SECRET =
            "calendar-events-v2-test-secret-at-least-32-bytes";
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");

    @Test
    void opaqueCursorRoundTripsAndRejectsPlaintextTamperingAndScopeDrift() {
        CalendarEventsV2Cursor codec = new CalendarEventsV2Cursor(
                SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        CalendarEventsV2Cursor.Position position = new CalendarEventsV2Cursor.Position(
                OffsetDateTime.parse("2026-10-28T09:00:00Z"),
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                OffsetDateTime.parse("2026-10-28T09:00:00Z"));

        String token = codec.encode("scope-a", position);

        assertThat(token).doesNotContain(position.eventId().toString());
        assertThat(codec.decode(token, "scope-a")).isEqualTo(position);
        assertThatThrownBy(() -> codec.decode("2026-10-28T09:00:00Z", "scope-a"))
                .isInstanceOf(BaseException.class);
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> codec.decode(tampered, "scope-a"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> codec.decode(token, "scope-b"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void expiredCursorFailsClosed() {
        CalendarEventsV2Cursor issuer = new CalendarEventsV2Cursor(
                SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        String token = issuer.encode("scope", new CalendarEventsV2Cursor.Position(
                OffsetDateTime.parse("2026-10-28T09:00:00Z"),
                UUID.randomUUID(), OffsetDateTime.parse("2026-10-28T09:00:00Z")));
        CalendarEventsV2Cursor expired = new CalendarEventsV2Cursor(
                SECRET, Clock.fixed(NOW.plusSeconds(16 * 60), ZoneOffset.UTC));

        assertThatThrownBy(() -> expired.decode(token, "scope"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void productionRequiresAtLeastThirtyTwoSecretBytes() {
        assertThatThrownBy(() -> new CalendarEventsV2Cursor(
                "", "production", Clock.fixed(NOW, ZoneOffset.UTC),
                new java.security.SecureRandom()).validateProductionReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32-byte");
        assertThatThrownBy(() -> new CalendarEventsV2Cursor(
                "x".repeat(31), "prod", Clock.fixed(NOW, ZoneOffset.UTC),
                new java.security.SecureRandom()).validateProductionReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32-byte");

        new CalendarEventsV2Cursor(
                "x".repeat(32), "production", Clock.fixed(NOW, ZoneOffset.UTC),
                new java.security.SecureRandom()).validateProductionReadiness();
    }
}
