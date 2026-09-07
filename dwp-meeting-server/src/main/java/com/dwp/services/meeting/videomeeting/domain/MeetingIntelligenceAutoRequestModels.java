package com.dwp.services.meeting.videomeeting.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

final class MeetingIntelligenceAutoRequestModels {

    private MeetingIntelligenceAutoRequestModels() {
    }

    enum RequestState {
        PENDING, RUNNING, SUCCEEDED, FAILED
    }

    record AutoRequest(
            UUID requestId,
            long tenantId,
            UUID meetingId,
            UUID sourceArtifactId,
            String sourceSha256,
            UUID contentNoticeId,
            long expectedContentPlanVersion,
            long requestedBy,
            String outputLanguage,
            String processingRegion,
            String intelligenceIdempotencyKey,
            RequestState state,
            UUID executionFence,
            OffsetDateTime leaseExpiresAt,
            int executionGeneration,
            int attemptCount,
            OffsetDateTime availableAt,
            UUID runId,
            String lastFailureCode,
            OffsetDateTime completedAt,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    record RunBinding(
            UUID runId,
            long tenantId,
            UUID meetingId,
            UUID sourceArtifactId,
            String sourceSha256,
            UUID contentNoticeId,
            String outputLanguage,
            String idempotencyKey,
            String requestSha256,
            long requestedBy,
            String state,
            String failureCode,
            UUID reportId) {
    }
}
