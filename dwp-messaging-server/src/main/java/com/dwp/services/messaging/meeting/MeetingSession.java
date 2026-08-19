package com.dwp.services.messaging.meeting;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MeetingSession(
        UUID sessionId,
        long tenantId,
        UUID conversationId,
        String provider,
        String roomName,
        String lifecycleState,
        long startedBy,
        OffsetDateTime startedAt,
        Long endedBy,
        OffsetDateTime endedAt,
        long version) {

    public boolean active() {
        return "ACTIVE".equals(lifecycleState);
    }
}
