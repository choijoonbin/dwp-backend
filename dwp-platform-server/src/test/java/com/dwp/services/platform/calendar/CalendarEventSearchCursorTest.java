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

class CalendarEventSearchCursorTest {

    private static final String SECRET =
            "calendar-event-search-test-secret-at-least-32-bytes";
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");

    @Test
    void independentOpaqueCursorRoundTripsAndFailsClosedOnScopeOrFormatDrift() {
        CalendarEventSearchCursor codec = new CalendarEventSearchCursor(
                SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        CalendarEventSearchCursor.Position position = new CalendarEventSearchCursor.Position(
                400,
                OffsetDateTime.parse("2026-10-28T09:00:00Z"),
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                OffsetDateTime.parse("2026-10-28T08:00:00Z"));

        String token = codec.encode("identity-query-range-scope", position);

        assertThat(token).startsWith("s1.").doesNotContain(position.eventId().toString());
        assertThat(codec.decode(token, "identity-query-range-scope")).isEqualTo(position);
        assertThatThrownBy(() -> codec.decode(token, "changed-query-or-identity"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> codec.decode(token.replaceFirst("^s1", "v1"),
                "identity-query-range-scope"))
                .isInstanceOf(BaseException.class);
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> codec.decode(tampered, "identity-query-range-scope"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void cursorExpiresAndProductionRequiresConfiguredSharedSecret() {
        CalendarEventSearchCursor issuer = new CalendarEventSearchCursor(
                SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        String token = issuer.encode("scope", new CalendarEventSearchCursor.Position(
                100, OffsetDateTime.parse("2026-10-28T09:00:00Z"),
                UUID.randomUUID(), OffsetDateTime.parse("2026-10-28T09:00:00Z")));
        CalendarEventSearchCursor expired = new CalendarEventSearchCursor(
                SECRET, Clock.fixed(NOW.plusSeconds(16 * 60), ZoneOffset.UTC));

        assertThatThrownBy(() -> expired.decode(token, "scope"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> new CalendarEventSearchCursor(
                "x".repeat(31), "production", Clock.fixed(NOW, ZoneOffset.UTC),
                new java.security.SecureRandom()).validateProductionReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32-byte");
    }
}
