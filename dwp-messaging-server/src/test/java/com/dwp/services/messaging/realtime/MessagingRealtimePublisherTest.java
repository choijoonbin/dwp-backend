package com.dwp.services.messaging.realtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MessagingRealtimePublisherTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void dispatchesOnlyAfterTheDatabaseTransactionCommits() {
        MessagingStreamService streams = mock(MessagingStreamService.class);
        MessagingRedisSignalPublisher redisSignals = mock(MessagingRedisSignalPublisher.class);
        MessagingRealtimePublisher publisher = new MessagingRealtimePublisher(streams, redisSignals);
        MessagingRealtimeEvent event = event();
        MessagingRealtimeSignal signal = MessagingRealtimeSignal.from(event);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(event);

        verify(streams, never()).wakeUp(signal);
        verify(redisSignals, never()).publish(signal);
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(streams).wakeUp(signal);
        verify(redisSignals).publish(signal);
    }

    @Test
    void dispatchesImmediatelyWhenThereIsNoTransaction() {
        MessagingStreamService streams = mock(MessagingStreamService.class);
        MessagingRedisSignalPublisher redisSignals = mock(MessagingRedisSignalPublisher.class);
        MessagingRealtimePublisher publisher = new MessagingRealtimePublisher(streams, redisSignals);
        MessagingRealtimeEvent event = event();
        MessagingRealtimeSignal signal = MessagingRealtimeSignal.from(event);

        publisher.publishAfterCommit(event);

        verify(streams).wakeUp(signal);
        verify(redisSignals).publish(signal);
    }

    private MessagingRealtimeEvent event() {
        return new MessagingRealtimeEvent(
                12, UUID.randomUUID(), 1, null, UUID.randomUUID(), UUID.randomUUID(),
                7L, 100, "messaging.message.updated", Map.of("version", 2), OffsetDateTime.now());
    }
}
