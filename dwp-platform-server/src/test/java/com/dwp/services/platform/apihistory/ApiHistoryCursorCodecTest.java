package com.dwp.services.platform.apihistory;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiHistoryCursorCodecTest {

    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

    private final ApiHistoryCursorCodec codec = new ApiHistoryCursorCodec(
            "api-history-cursor-secret-at-least-32-bytes",
            Duration.ofMinutes(15),
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void roundTripsATenantAndFilterBoundCursor() {
        UUID historyId = UUID.randomUUID();
        String cursor = codec.encode(7L, NOW.minusSeconds(10), historyId, "filter-v1");

        ApiHistoryCursorCodec.CursorPosition decoded = codec.decode(cursor, 7L, "filter-v1");

        assertThat(decoded.historyId()).isEqualTo(historyId);
        assertThat(decoded.occurredAt()).isEqualTo(NOW.minusSeconds(10));
    }

    @Test
    void rejectsTamperingAndCrossTenantReuse() {
        String cursor = codec.encode(7L, NOW, UUID.randomUUID(), "filter-v1");

        assertThatThrownBy(() -> codec.decode(cursor + "x", 7L, "filter-v1"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> codec.decode(cursor, 8L, "filter-v1"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> codec.decode(cursor, 7L, "different-filter"))
                .isInstanceOf(BaseException.class);
    }
}
