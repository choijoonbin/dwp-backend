package com.dwp.services.messaging.meeting;

import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public final class MeetingDtos {

    private MeetingDtos() {
    }

    public record CapabilityResponse(
            boolean available,
            String provider,
            String unavailableReason,
            boolean audio,
            boolean video,
            boolean screenShare,
            boolean participantList,
            int tokenTtlSeconds) {

        static CapabilityResponse from(MeetingProviderCapability capability) {
            return new CapabilityResponse(
                    capability.available(),
                    capability.provider(),
                    capability.unavailableReason(),
                    capability.audio(),
                    capability.video(),
                    capability.screenShare(),
                    capability.participantList(),
                    capability.tokenTtlSeconds());
        }
    }

    public record SessionResponse(
            UUID sessionId,
            UUID conversationId,
            String provider,
            String lifecycleState,
            long startedBy,
            OffsetDateTime startedAt,
            Long endedBy,
            OffsetDateTime endedAt,
            long version) {

        static SessionResponse from(MeetingSession session) {
            return new SessionResponse(
                    session.sessionId(),
                    session.conversationId(),
                    session.provider(),
                    session.lifecycleState(),
                    session.startedBy(),
                    session.startedAt(),
                    session.endedBy(),
                    session.endedAt(),
                    session.version());
        }
    }

    public record CurrentMeetingResponse(SessionResponse session) {
    }

    public record HistoryItemResponse(
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
            long durationSeconds,
            long version) {

        static HistoryItemResponse from(MeetingHistoryItem item) {
            long duration = item.endedAt() == null
                    ? 0
                    : Math.max(0, Duration.between(item.startedAt(), item.endedAt()).toSeconds());
            return new HistoryItemResponse(
                    item.sessionId(), item.conversationId(), item.provider(), item.lifecycleState(),
                    item.startedBy(), item.startedByName(), item.startedAt(), item.endedBy(),
                    item.endedByName(), item.endedAt(), duration, item.version());
        }
    }

    public record HistoryResponse(List<HistoryItemResponse> items) {
    }

    public record JoinTokenResponse(
            UUID sessionId,
            String provider,
            String serverUrl,
            String participantToken,
            OffsetDateTime expiresAt) {
    }
}
