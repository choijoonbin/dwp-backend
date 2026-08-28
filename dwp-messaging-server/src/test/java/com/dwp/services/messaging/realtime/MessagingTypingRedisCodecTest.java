package com.dwp.services.messaging.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagingTypingRedisCodecTest {

    private final MessagingTypingRedisCodec codec =
            new MessagingTypingRedisCodec(new ObjectMapper().findAndRegisterModules());

    @Test
    void roundTripsTheBoundedEphemeralContract() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T10:24:00.123456789Z");
        MessagingTypingSignal signal = new MessagingTypingSignal(
                UUID.randomUUID(), 1, UUID.randomUUID(), 101, true, now, now.plusSeconds(8));
        String payload = codec.encode(signal);

        assertThat(payload).contains("\"changedAt\":\"2026-08-28T10:24:00.123456789Z\"");
        assertThat(codec.decode(payload.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(signal);
    }

    @Test
    void rejectsUnexpectedContentFields() {
        assertThatThrownBy(() -> codec.decode(
                "{\"signalId\":\"00000000-0000-0000-0000-000000000001\","
                        .concat("\"tenantId\":1,\"conversationId\":\"00000000-0000-0000-0000-000000000002\",")
                        .concat("\"userId\":2,\"started\":true,\"changedAt\":\"2026-08-19T00:00:00Z\",")
                        .concat("\"expiresAt\":\"2026-08-19T00:00:08Z\",\"messageBody\":\"forbidden\"}")
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
    }
}
