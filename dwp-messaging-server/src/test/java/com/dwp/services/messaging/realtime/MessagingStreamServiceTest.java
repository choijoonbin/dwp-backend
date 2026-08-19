package com.dwp.services.messaging.realtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagingStreamServiceTest {

    @Test
    void acceptsZeroAndPositiveReconnectCursors() {
        assertThat(MessagingStreamService.parseCursor("0")).isZero();
        assertThat(MessagingStreamService.parseCursor("9007199254740993"))
                .isEqualTo(9_007_199_254_740_993L);
    }

    @Test
    void rejectsMalformedOrNegativeReconnectCursors() {
        assertThatThrownBy(() -> MessagingStreamService.parseCursor("-1"))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_FORMAT));
        assertThatThrownBy(() -> MessagingStreamService.parseCursor("cursor"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void durableWakeUpReplaysPersistedEventsForConnectedTenant() {
        MessagingRealtimeRepository repository = mock(MessagingRealtimeRepository.class);
        MessagingRequestContext.Subject subject = subject(101, 1);
        MessagingRealtimeEvent event = event(1, 1);
        when(repository.latestTenantSequence(1)).thenReturn(0L);
        when(repository.eventsBetween(subject, 0, 1, 200)).thenReturn(List.of(event));
        MessagingStreamService streams = new MessagingStreamService(repository, Duration.ofMinutes(1));
        streams.open(subject, "0");

        streams.wakeUp(MessagingRealtimeSignal.from(event));
        streams.wakeUp(MessagingRealtimeSignal.from(event));

        verify(repository).eventsBetween(subject, 0, 1, 200);
    }

    @Test
    void heartbeatRecoversARealtimeHintMissFromTheDurableLog() {
        MessagingRealtimeRepository repository = mock(MessagingRealtimeRepository.class);
        MessagingRequestContext.Subject subject = subject(101, 1);
        when(repository.latestTenantSequence(1)).thenReturn(0L);
        MessagingStreamService streams = new MessagingStreamService(repository, Duration.ofMinutes(1));
        streams.open(subject, "0");
        reset(repository);
        when(repository.latestTenantSequence(1)).thenReturn(1L);
        when(repository.eventsBetween(subject, 0, 1, 200)).thenReturn(List.of(event(1, 1)));

        streams.heartbeat();

        verify(repository).latestTenantSequence(1);
        verify(repository).eventsBetween(subject, 0, 1, 200);
    }

    @Test
    void typingSignalsAreAclCheckedAndDeduplicatedWithoutDurableEvents() {
        MessagingRealtimeRepository repository = mock(MessagingRealtimeRepository.class);
        MessagingRequestContext.Subject receiver = subject(202, 1);
        UUID conversationId = UUID.randomUUID();
        when(repository.latestTenantSequence(1)).thenReturn(0L);
        when(repository.isActiveConversationMember(1, conversationId, receiver.userId()))
                .thenReturn(true);
        MessagingStreamService streams = new MessagingStreamService(repository, Duration.ofMinutes(1));
        streams.open(receiver, "0");
        OffsetDateTime now = OffsetDateTime.now();
        MessagingTypingSignal signal = new MessagingTypingSignal(
                UUID.randomUUID(), 1, conversationId, 101, true, now, now.plusSeconds(8));

        streams.dispatchTyping(signal);
        streams.dispatchTyping(signal);

        verify(repository).isActiveConversationMember(1, conversationId, receiver.userId());
        verify(repository, never()).append(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap());
    }

    private MessagingRequestContext.Subject subject(long userId, long tenantId) {
        return new MessagingRequestContext.Subject(
                userId, tenantId, UUID.randomUUID(), "Test User",
                Set.of("WORKSPACE_MEMBER"), Set.of("APP.MESSAGING:VIEW"), Set.of());
    }

    private MessagingRealtimeEvent event(long sequence, long tenantId) {
        return new MessagingRealtimeEvent(
                sequence, UUID.randomUUID(), tenantId, null, UUID.randomUUID(), UUID.randomUUID(),
                1L, 101, "messaging.message.created", Map.of(), OffsetDateTime.now());
    }
}
