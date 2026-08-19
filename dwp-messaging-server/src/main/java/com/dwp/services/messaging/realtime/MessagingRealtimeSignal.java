package com.dwp.services.messaging.realtime;

import java.util.UUID;

/** Content-free wake-up hint. PostgreSQL remains authoritative for event data and ACL checks. */
public record MessagingRealtimeSignal(
        long tenantId,
        UUID conversationId,
        String eventSequence) {

    public MessagingRealtimeSignal {
        if (tenantId < 1) {
            throw new IllegalArgumentException("Messaging realtime signal tenant must be positive.");
        }
        if (eventSequence == null || !eventSequence.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException(
                    "Messaging realtime signal sequence must be a positive decimal string.");
        }
        try {
            if (Long.parseLong(eventSequence) < 1) {
                throw new IllegalArgumentException(
                        "Messaging realtime signal sequence must be positive.");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Messaging realtime signal sequence exceeds the supported range.", exception);
        }
    }

    public long sequence() {
        return Long.parseLong(eventSequence);
    }

    static MessagingRealtimeSignal from(MessagingRealtimeEvent event) {
        return new MessagingRealtimeSignal(
                event.tenantId(), event.conversationId(), Long.toString(event.sequence()));
    }
}
