package com.dwp.services.messaging.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagingRedisSignalCodecTest {

    private final MessagingRedisSignalCodec codec =
            new MessagingRedisSignalCodec(new ObjectMapper().findAndRegisterModules());

    @Test
    void roundTripsOnlyTheContentFreeDurableWakeUpContract() {
        MessagingRealtimeSignal signal =
                new MessagingRealtimeSignal(1, UUID.randomUUID(), "9007199254740993");

        String payload = codec.encode(signal);

        assertThat(codec.decode(payload.getBytes(StandardCharsets.UTF_8))).isEqualTo(signal);
        assertThat(payload).doesNotContain("payload", "messageId", "eventType");
    }

    @Test
    void rejectsUnknownFieldsAndInvalidSequenceRepresentations() {
        assertThatThrownBy(() -> codec.decode(
                "{\"tenantId\":1,\"conversationId\":null,\"eventSequence\":\"1\",\"body\":\"secret\"}"
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
        assertThatThrownBy(() -> codec.decode(
                "{\"tenantId\":1,\"conversationId\":null,\"eventSequence\":1}"
                        .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimal string");
    }
}
