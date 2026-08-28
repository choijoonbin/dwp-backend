package com.dwp.services.meeting.videomeeting.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class VideoMeetingIntelligenceModels {

    public static final String PROFILE = "STANDARD_RECAP_V1";
    public static final String SCHEMA_VERSION = "meeting-intelligence-v1";
    public static final String PROMPT_VERSION = "governed-recap-v1";

    private VideoMeetingIntelligenceModels() {
    }

    public enum RunState {
        RUNNING, SUCCEEDED, FAILED
    }

    public enum ReportState {
        DRAFT, APPROVED, PUBLISHED, REJECTED, DELETED
    }

    public enum Audience {
        PRIVATE_REVIEWERS, MEETING_PARTICIPANTS
    }

    public enum ReviewDecision {
        APPROVE, REJECT
    }

    public enum ContentPermission {
        VIEW, REVIEW, MANAGE
    }

    public record SourceArtifact(
            UUID artifactId,
            long tenantId,
            UUID meetingId,
            String artifactState,
            String sha256,
            OffsetDateTime retentionUntil,
            boolean serverSideProcessingAllowed,
            String processingRegion,
            UUID contentNoticeId,
            String consentSnapshotSha256) {
    }

    public record ConsentEvidence(
            int required,
            int acknowledged,
            String snapshotSha256) {

        public boolean complete() {
            return required > 0 && required == acknowledged;
        }
    }

    public record IntelligenceRun(
            UUID runId,
            long tenantId,
            UUID meetingId,
            UUID sourceArtifactId,
            String sourceSha256,
            UUID contentNoticeId,
            String consentSnapshotSha256,
            String analysisProfile,
            String outputLanguage,
            String processingRegion,
            UUID executionFence,
            OffsetDateTime leaseExpiresAt,
            int attemptCount,
            RunState state,
            String providerCode,
            String providerModel,
            String promptVersion,
            String schemaVersion,
            String idempotencyKey,
            String requestSha256,
            OffsetDateTime requestedAt,
            long requestedBy,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            String failureCode,
            long version) {
    }

    public record IntelligenceReport(
            UUID reportId,
            long tenantId,
            UUID meetingId,
            UUID runId,
            ReportState state,
            Audience audience,
            String encryptedPayload,
            String payloadSha256,
            String sourceSha256,
            String schemaVersion,
            OffsetDateTime retentionUntil,
            boolean legalHold,
            OffsetDateTime approvedAt,
            Long approvedBy,
            OffsetDateTime publishedAt,
            Long publishedBy,
            OffsetDateTime deletedAt,
            Long deletedBy,
            long version,
            long createdBy) {

        public boolean expiredAt(OffsetDateTime now) {
            return !legalHold && !retentionUntil.isAfter(now);
        }
    }

    public record IntelligenceReview(
            UUID reviewId,
            UUID reportId,
            long reviewedReportVersion,
            String reviewedPayloadSha256,
            ReviewDecision decision,
            String reasonCode,
            OffsetDateTime reviewedAt,
            long reviewedBy) {
    }

    public record ContentGrant(
            UUID aclId,
            UUID reportId,
            long principalUserId,
            ContentPermission permission,
            OffsetDateTime grantedAt,
            long grantedBy,
            OffsetDateTime expiresAt,
            OffsetDateTime revokedAt,
            String reasonCode) {
    }

    public record StoredRun(String requestSha256, IntelligenceRun run) {
    }

    public record RetentionHealth(
            OffsetDateTime lastAttemptAt,
            OffsetDateTime lastSuccessAt,
            OffsetDateTime lastFailureAt,
            String lastFailureCode,
            UUID activeFence,
            OffsetDateTime activeLeaseExpiresAt,
            long version) {
    }

    public record RetentionPurgeResult(int deletedCount, boolean overdueRemaining) {
    }

    public record ReportView(
            IntelligenceReport report,
            com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Analysis
                    payload,
            List<IntelligenceReview> reviews) {
    }
}
