package com.dwp.services.meeting.videomeeting.api;

import java.time.OffsetDateTime;
import java.util.Map;

public final class VideoMeetingAdminIntelligenceDtos {

    private VideoMeetingAdminIntelligenceDtos() {
    }

    public record ReadinessSignal(String state, String reason) {

        public static ReadinessSignal ready() {
            return new ReadinessSignal("READY", null);
        }

        public static ReadinessSignal blocked(String reason) {
            return new ReadinessSignal("BLOCKED", reason);
        }

        public static ReadinessSignal connectionRequired(String reason) {
            return new ReadinessSignal("CONNECTION_REQUIRED", reason);
        }

        public static ReadinessSignal notVerified(String reason) {
            return new ReadinessSignal("NOT_VERIFIED", reason);
        }
    }

    public record RetentionReadiness(
            int meetingDays,
            int artifactDays,
            int chatDays,
            boolean intelligenceWorkerReady,
            Map<String, ReadinessSignal> signals) {
    }

    public record ReadinessResponse(
            String readinessVersion,
            OffsetDateTime observedAt,
            String recordingPolicy,
            String providerCode,
            String providerModel,
            String processingRegion,
            Map<String, ReadinessSignal> capabilities,
            Map<String, ReadinessSignal> dependencies,
            Map<String, ReadinessSignal> governance,
            RetentionReadiness retention) {
    }
}
