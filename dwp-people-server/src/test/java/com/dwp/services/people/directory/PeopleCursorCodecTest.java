package com.dwp.services.people.directory;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeopleCursorCodecTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void roundTripsTenantAndFilterBoundCursor() {
        PeopleCursorCodec codec = codec(CLOCK);
        String fingerprint = codec.fingerprint("Kim", "ACTIVE", "2026-08-10");

        String cursor = codec.encode(7L, 41L, fingerprint);

        assertThat(codec.decode(cursor, 7L, fingerprint)).isEqualTo(41L);
    }

    @Test
    void rejectsCrossTenantAndChangedFilterReplay() {
        PeopleCursorCodec codec = codec(CLOCK);
        String fingerprint = codec.fingerprint("Kim", "ACTIVE", "2026-08-10");
        String cursor = codec.encode(7L, 41L, fingerprint);

        assertThatThrownBy(() -> codec.decode(cursor, 8L, fingerprint))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> codec.decode(
                cursor, 7L, codec.fingerprint("Park", "ACTIVE", "2026-08-10")))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void rejectsExpiredAndTamperedCursor() {
        PeopleCursorCodec codec = codec(CLOCK);
        String fingerprint = codec.fingerprint(null, null, "2026-08-10");
        String cursor = codec.encode(1L, 3L, fingerprint);
        PeopleCursorCodec later = codec(Clock.offset(CLOCK, Duration.ofMinutes(16)));

        assertThatThrownBy(() -> later.decode(cursor, 1L, fingerprint))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> codec.decode(cursor + "x", 1L, fingerprint))
                .isInstanceOf(BaseException.class);
    }

    private PeopleCursorCodec codec(Clock clock) {
        return new PeopleCursorCodec(
                "people-cursor-unit-test-secret-value",
                Duration.ofMinutes(15),
                new ObjectMapper(),
                clock);
    }
}
