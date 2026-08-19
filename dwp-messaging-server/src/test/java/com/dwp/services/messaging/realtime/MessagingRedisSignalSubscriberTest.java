package com.dwp.services.messaging.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagingRedisSignalSubscriberTest {

    @Test
    void delegatesValidatedWakeUpsToTheLocalStream() {
        MessagingRedisSignalCodec codec = mock(MessagingRedisSignalCodec.class);
        MessagingStreamService streams = mock(MessagingStreamService.class);
        MessagingRealtimeSignal signal = new MessagingRealtimeSignal(1, UUID.randomUUID(), "12");
        Message message = message("{}");
        when(codec.decode(message.getBody())).thenReturn(signal);

        new MessagingRedisSignalSubscriber(codec, streams).onMessage(message, null);

        verify(streams).wakeUp(signal);
    }

    @Test
    void rejectsMalformedSignalsWithoutTouchingConnectedStreams() {
        MessagingRedisSignalCodec codec = mock(MessagingRedisSignalCodec.class);
        MessagingStreamService streams = mock(MessagingStreamService.class);
        Message message = message("invalid");
        when(codec.decode(message.getBody())).thenThrow(new IllegalArgumentException("invalid"));

        new MessagingRedisSignalSubscriber(codec, streams).onMessage(message, null);

        verify(streams, never()).wakeUp(org.mockito.ArgumentMatchers.any());
    }

    private Message message(String body) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
