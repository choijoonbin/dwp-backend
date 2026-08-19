package com.dwp.services.messaging.realtime;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record MessagingRealtimeEvent(
        long sequence,
        UUID eventId,
        long tenantId,
        Long audienceUserId,
        UUID conversationId,
        UUID messageId,
        Long messageSequence,
        long actorUserId,
        String eventType,
        Map<String, Object> payload,
        OffsetDateTime occurredAt) {

    public String cursor() {
        return Long.toString(sequence);
    }
}
