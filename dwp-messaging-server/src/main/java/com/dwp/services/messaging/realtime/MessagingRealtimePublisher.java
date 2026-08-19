package com.dwp.services.messaging.realtime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class MessagingRealtimePublisher {

    private final MessagingStreamService streams;
    private final MessagingRedisSignalPublisher redisSignals;

    public MessagingRealtimePublisher(
            MessagingStreamService streams,
            MessagingRedisSignalPublisher redisSignals) {
        this.streams = streams;
        this.redisSignals = redisSignals;
    }

    public void publishAfterCommit(MessagingRealtimeEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(event);
                }
            });
            return;
        }
        publish(event);
    }

    private void publish(MessagingRealtimeEvent event) {
        MessagingRealtimeSignal signal = MessagingRealtimeSignal.from(event);
        streams.wakeUp(signal);
        redisSignals.publish(signal);
    }
}
