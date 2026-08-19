package com.dwp.services.messaging.realtime;

import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.UUID;

@Service
public class MessagingEventRecorder {

    private final MessagingRealtimeRepository events;
    private final MessagingRealtimePublisher publisher;

    public MessagingEventRecorder(
            MessagingRealtimeRepository events,
            MessagingRealtimePublisher publisher) {
        this.events = events;
        this.publisher = publisher;
    }

    public void conversationEvent(
            MessagingRequestContext.Subject actor,
            String eventType,
            UUID conversationId,
            UUID messageId,
            Map<String, Object> payload) {
        record(actor, null, eventType, conversationId, messageId, payload);
    }

    public void privateEvent(
            MessagingRequestContext.Subject actor,
            String eventType,
            UUID conversationId,
            UUID messageId,
            Map<String, Object> payload) {
        record(actor, actor.userId(), eventType, conversationId, messageId, payload);
    }

    public void tenantEvent(
            MessagingRequestContext.Subject actor,
            String eventType,
            Map<String, Object> payload) {
        record(actor, null, eventType, null, null, payload);
    }

    private void record(
            MessagingRequestContext.Subject actor,
            Long audienceUserId,
            String eventType,
            UUID conversationId,
            UUID messageId,
            Map<String, Object> payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Messaging domain events must be recorded inside the owning database transaction.");
        }
        // msg_realtime_events is the canonical durable domain log. The caller's transaction
        // commits the domain mutation and this append together; in-memory fan-out starts after commit.
        MessagingRealtimeEvent event = events.append(
                actor.tenantId(), audienceUserId, conversationId, messageId,
                actor.userId(), eventType, payload);
        publisher.publishAfterCommit(event);
    }
}
