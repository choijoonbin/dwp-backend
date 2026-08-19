package com.dwp.services.messaging.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagingTypingRedisSubscriberTest {

    @Test
    void delegatesValidatedSignalsAndRejectsMalformedPayloads() {
        MessagingTypingRedisCodec codec = mock(MessagingTypingRedisCodec.class);
        MessagingTypingService typing = mock(MessagingTypingService.class);
        Message validMessage = message("{}");
        OffsetDateTime now = OffsetDateTime.now();
        MessagingTypingSignal signal = new MessagingTypingSignal(
                UUID.randomUUID(), 1, UUID.randomUUID(), 101, true, now, now.plusSeconds(8));
        when(codec.decode(validMessage.getBody())).thenReturn(signal);
        MessagingTypingRedisSubscriber subscriber =
                new MessagingTypingRedisSubscriber(codec, typing);

        subscriber.onMessage(validMessage, null);

        verify(typing).acceptRemote(signal);

        Message malformed = message("invalid");
        when(codec.decode(malformed.getBody())).thenThrow(new IllegalArgumentException("invalid"));
        subscriber.onMessage(malformed, null);
        verify(typing, times(1)).acceptRemote(signal);
    }

    private Message message(String body) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
