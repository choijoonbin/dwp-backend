package com.dwp.services.auth.scim;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimCursorCodecTest {

    private static final String SECRET = "scim-cursor-test-secret-with-at-least-32-characters";
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

    @Test
    void bindsCursorToTenantResourceCountAndFilter() {
        ScimCursorCodec codec = codec(Clock.fixed(NOW, ZoneOffset.UTC));
        String cursor = codec.encode("Users", 3L, 100, 50, "userName eq \"a@example.com\"");

        assertThat(codec.decode(cursor, "Users", 3L, 50, "userName eq \"a@example.com\""))
                .isEqualTo(100);
        assertInvalid(() -> codec.decode(cursor, "Users", 4L, 50, "userName eq \"a@example.com\""));
        assertInvalid(() -> codec.decode(cursor, "Groups", 3L, 50, "userName eq \"a@example.com\""));
        assertInvalid(() -> codec.decode(cursor, "Users", 3L, 25, "userName eq \"a@example.com\""));
        assertInvalid(() -> codec.decode(cursor, "Users", 3L, 50, "externalId eq \"worker-1\""));
    }

    @Test
    void rejectsTamperedAndExpiredCursor() {
        ScimCursorCodec codec = codec(Clock.fixed(NOW, ZoneOffset.UTC));
        String cursor = codec.encode("Groups", 2L, 50, 50, null);
        String tampered = (cursor.charAt(0) == 'A' ? 'B' : 'A') + cursor.substring(1);

        assertInvalid(() -> codec.decode(tampered, "Groups", 2L, 50, null));

        ScimCursorCodec expiredCodec = codec(Clock.offset(
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(16)));
        assertInvalid(() -> expiredCodec.decode(cursor, "Groups", 2L, 50, null));
    }

    private ScimCursorCodec codec(Clock clock) {
        return new ScimCursorCodec(SECRET, 900, clock);
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ScimException.class,
                        error -> assertThat(error.scimType()).isEqualTo("invalidValue"));
    }
}
