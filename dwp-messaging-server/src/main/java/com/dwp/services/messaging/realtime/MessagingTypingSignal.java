package com.dwp.services.messaging.realtime;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Ephemeral presence hint. It never participates in the durable messaging event cursor. */
public record MessagingTypingSignal(
        UUID signalId,
        long tenantId,
        UUID conversationId,
        long userId,
        boolean started,
        OffsetDateTime changedAt,
        OffsetDateTime expiresAt) {

    public MessagingTypingSignal {
        if (signalId == null || conversationId == null || changedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("Messaging typing signal fields are required.");
        }
        if (tenantId < 1 || userId < 1) {
            throw new IllegalArgumentException("Messaging typing signal identity must be positive.");
        }
        if (started && !expiresAt.isAfter(changedAt)) {
            throw new IllegalArgumentException("Started typing signals require a future expiry.");
        }
    }
}
