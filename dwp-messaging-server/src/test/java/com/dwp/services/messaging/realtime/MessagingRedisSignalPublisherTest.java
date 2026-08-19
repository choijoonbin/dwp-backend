package com.dwp.services.messaging.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagingRedisSignalPublisherTest {

    @Test
    void publishesTheHintToItsStableShard() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        MessagingRedisChannels channels =
                new MessagingRedisChannels("dwp.messaging.signal.v1", "dwp.messaging.typing.v1", 8);
        MessagingRedisSignalCodec codec =
                new MessagingRedisSignalCodec(new com.fasterxml.jackson.databind.ObjectMapper());
        MessagingRedisSignalPublisher publisher =
                new MessagingRedisSignalPublisher(redis, channels, codec, true);
        MessagingRealtimeSignal signal = new MessagingRealtimeSignal(1, UUID.randomUUID(), "42");

        publisher.publish(signal);

        verify(redis).convertAndSend(channels.channel(1, signal.conversationId()), codec.encode(signal));
    }

    @Test
    void redisFailureDoesNotEscapeOrAffectTheCommittedMutation() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.convertAndSend(anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("offline"));
        MessagingRedisSignalPublisher publisher = new MessagingRedisSignalPublisher(
                redis,
                new MessagingRedisChannels("signal.valid", "typing.valid", 1),
                new MessagingRedisSignalCodec(new com.fasterxml.jackson.databind.ObjectMapper()),
                true);

        assertThatCode(() -> publisher.publish(
                new MessagingRealtimeSignal(1, null, "1"))).doesNotThrowAnyException();
    }

    @Test
    void disabledRedisModeKeepsLocalOnlyDeliveryWithoutPublishing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        MessagingRedisSignalPublisher publisher = new MessagingRedisSignalPublisher(
                redis,
                new MessagingRedisChannels("signal.valid", "typing.valid", 1),
                new MessagingRedisSignalCodec(new com.fasterxml.jackson.databind.ObjectMapper()),
                false);

        publisher.publish(new MessagingRealtimeSignal(1, null, "1"));

        verify(redis, never()).convertAndSend(anyString(), anyString());
    }
}
