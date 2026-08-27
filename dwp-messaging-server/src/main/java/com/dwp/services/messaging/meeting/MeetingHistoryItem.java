package com.dwp.services.messaging.meeting;

import java.time.OffsetDateTime;
import java.util.UUID;

record MeetingHistoryItem(
        UUID sessionId,
        UUID conversationId,
        String provider,
        String lifecycleState,
        long startedBy,
        String startedByName,
        OffsetDateTime startedAt,
        Long endedBy,
        String endedByName,
        OffsetDateTime endedAt,
        long version) {
}
