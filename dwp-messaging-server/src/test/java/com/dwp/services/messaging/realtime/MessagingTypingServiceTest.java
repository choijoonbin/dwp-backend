package com.dwp.services.messaging.realtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagingTypingServiceTest {

    @AfterEach
    void clearRequestContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void activeMembersPublishLocalAndTtlBoundRedisTypingState() {
        Fixture fixture = fixture(true, true);
        UUID conversationId = UUID.randomUUID();
        MessagingRequestContext.set(subject());
        when(fixture.repository.isActiveConversationMember(1, conversationId, 101)).thenReturn(true);

        fixture.service.change(conversationId, true);

        org.mockito.ArgumentCaptor<MessagingTypingSignal> signal =
                org.mockito.ArgumentCaptor.forClass(MessagingTypingSignal.class);
        verify(fixture.streams).dispatchTyping(signal.capture());
        assertThat(signal.getValue().started()).isTrue();
        assertThat(signal.getValue().expiresAt()).isAfter(signal.getValue().changedAt());
        verify(fixture.values).set(
                eq("dwp:messaging:typing:v1:1:" + conversationId + ":101"),
                eq(signal.getValue().signalId().toString()),
                eq(Duration.ofSeconds(8)));
        verify(fixture.redis).convertAndSend(
                eq(fixture.channels.typingChannel(1, conversationId)), anyString());
    }

    @Test
    void nonMembersCannotPublishTypingState() {
        Fixture fixture = fixture(true, true);
        UUID conversationId = UUID.randomUUID();
        MessagingRequestContext.set(subject());
        when(fixture.repository.isActiveConversationMember(1, conversationId, 101)).thenReturn(false);

        assertThatThrownBy(() -> fixture.service.change(conversationId, true))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(fixture.streams, never()).dispatchTyping(any());
        verify(fixture.redis, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void explicitStopRemovesTheTtlStateAndPublishesAStopSignal() {
        Fixture fixture = fixture(true, true);
        UUID conversationId = UUID.randomUUID();
        MessagingRequestContext.set(subject());
        when(fixture.repository.isActiveConversationMember(1, conversationId, 101)).thenReturn(true);

        fixture.service.change(conversationId, false);

        org.mockito.ArgumentCaptor<MessagingTypingSignal> signal =
                org.mockito.ArgumentCaptor.forClass(MessagingTypingSignal.class);
        verify(fixture.streams).dispatchTyping(signal.capture());
        assertThat(signal.getValue().started()).isFalse();
        assertThat(signal.getValue().expiresAt()).isEqualTo(signal.getValue().changedAt());
        verify(fixture.redis).delete("dwp:messaging:typing:v1:1:" + conversationId + ":101");
        verify(fixture.redis).convertAndSend(
                eq(fixture.channels.typingChannel(1, conversationId)), anyString());
    }

    @Test
    void redisFailureDegradesOnlyTypingAndKeepsTheRequestSuccessful() {
        Fixture fixture = fixture(true, true);
        UUID conversationId = UUID.randomUUID();
        MessagingRequestContext.set(subject());
        when(fixture.repository.isActiveConversationMember(1, conversationId, 101)).thenReturn(true);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("offline"))
                .when(fixture.values).set(anyString(), anyString(), any(Duration.class));
        when(fixture.redis.convertAndSend(anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("offline"));

        assertThatCode(() -> fixture.service.change(conversationId, true))
                .doesNotThrowAnyException();
        verify(fixture.streams).dispatchTyping(any());
    }

    @Test
    void remoteSignalsAreRevalidatedAndExpiredStartsAreIgnored() {
        Fixture fixture = fixture(true, true);
        UUID conversationId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        MessagingTypingSignal expired = new MessagingTypingSignal(
                UUID.randomUUID(), 1, conversationId, 101, true,
                now.minusSeconds(10), now.minusSeconds(2));

        fixture.service.acceptRemote(expired);

        verify(fixture.repository, never()).isActiveConversationMember(1, conversationId, 101);
        verify(fixture.streams, never()).dispatchTyping(expired);

        MessagingTypingSignal valid = new MessagingTypingSignal(
                UUID.randomUUID(), 1, conversationId, 101, true, now, now.plusSeconds(8));
        when(fixture.repository.isActiveConversationMember(1, conversationId, 101)).thenReturn(false);
        fixture.service.acceptRemote(valid);
        verify(fixture.streams, never()).dispatchTyping(valid);
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(boolean redisEnabled, boolean attachValues) {
        MessagingRealtimeRepository repository = mock(MessagingRealtimeRepository.class);
        MessagingStreamService streams = mock(MessagingStreamService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        if (attachValues) when(redis.opsForValue()).thenReturn(values);
        MessagingRedisChannels channels =
                new MessagingRedisChannels("signal.valid", "typing.valid", 4);
        MessagingTypingRedisCodec codec =
                new MessagingTypingRedisCodec(new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules());
        MessagingTypingService service = new MessagingTypingService(
                repository, streams, redis, channels, codec, redisEnabled,
                Duration.ofSeconds(8), "dwp:messaging:typing:v1");
        return new Fixture(repository, streams, redis, values, channels, service);
    }

    private MessagingRequestContext.Subject subject() {
        return new MessagingRequestContext.Subject(
                101, 1, UUID.randomUUID(), "Test User",
                Set.of("WORKSPACE_MEMBER"), Set.of("APP.MESSAGING:UPDATE"), Set.of());
    }

    private record Fixture(
            MessagingRealtimeRepository repository,
            MessagingStreamService streams,
            StringRedisTemplate redis,
            ValueOperations<String, String> values,
            MessagingRedisChannels channels,
            MessagingTypingService service) {
    }
}
