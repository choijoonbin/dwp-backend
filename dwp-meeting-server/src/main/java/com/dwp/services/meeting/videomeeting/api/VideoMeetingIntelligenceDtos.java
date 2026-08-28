package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ContentGrant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReport;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReview;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceRun;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReportView;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Analysis;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class VideoMeetingIntelligenceDtos {

    private VideoMeetingIntelligenceDtos() {
    }

    public record CreateRunCommand(
            @NotNull UUID sourceArtifactId,
            @NotBlank @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$") String outputLanguage,
            @PositiveOrZero long expectedContentPlanVersion) {
    }

    public record ReviewCommand(
            @PositiveOrZero long expectedVersion,
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(max = 48)
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,47}$") String reasonCode) {
    }

    public record VersionCommand(@PositiveOrZero long expectedVersion) {
    }

    public record GrantCommand(
            @NotBlank @Pattern(regexp = "VIEW|REVIEW|MANAGE") String permission,
            @Future OffsetDateTime expiresAt,
            @NotBlank @Size(max = 48)
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,47}$") String reasonCode) {
    }

    public record RunResponse(
            UUID runId,
            UUID meetingId,
            UUID sourceArtifactId,
            String state,
            String analysisProfile,
            String outputLanguage,
            String processingRegion,
            String providerCode,
            String providerModel,
            String schemaVersion,
            OffsetDateTime requestedAt,
            OffsetDateTime completedAt,
            String failureCode,
            long version,
            UUID reportId) {

        public static RunResponse from(IntelligenceRun run, UUID reportId) {
            return new RunResponse(
                    run.runId(), run.meetingId(), run.sourceArtifactId(), run.state().name(),
                    run.analysisProfile(), run.outputLanguage(), run.processingRegion(),
                    run.providerCode(), run.providerModel(), run.schemaVersion(),
                    run.requestedAt(), run.completedAt(), run.failureCode(), run.version(), reportId);
        }
    }

    public record ReportResponse(
            UUID reportId,
            UUID meetingId,
            UUID runId,
            String state,
            String audience,
            String schemaVersion,
            OffsetDateTime retentionUntil,
            boolean legalHold,
            OffsetDateTime approvedAt,
            OffsetDateTime publishedAt,
            long version,
            boolean canCurrentViewerReview,
            Analysis analysis,
            List<ReviewResponse> reviews) {

        public static ReportResponse from(ReportView view) {
            return from(view, false);
        }

        public static ReportResponse from(
                ReportView view, boolean canCurrentViewerReview) {
            IntelligenceReport report = view.report();
            return new ReportResponse(
                    report.reportId(), report.meetingId(), report.runId(),
                    report.state().name(), report.audience().name(), report.schemaVersion(),
                    report.retentionUntil(), report.legalHold(), report.approvedAt(),
                    report.publishedAt(), report.version(), canCurrentViewerReview,
                    view.payload(),
                    view.reviews().stream().map(ReviewResponse::from).toList());
        }
    }

    public record ReviewResponse(
            UUID reviewId,
            long reviewedReportVersion,
            String decision,
            String reasonCode,
            OffsetDateTime reviewedAt,
            long reviewedBy) {

        public static ReviewResponse from(IntelligenceReview review) {
            return new ReviewResponse(
                    review.reviewId(), review.reviewedReportVersion(),
                    review.decision().name(), review.reasonCode(),
                    review.reviewedAt(), review.reviewedBy());
        }
    }

    public record GrantResponse(
            UUID aclId,
            UUID reportId,
            long principalUserId,
            String permission,
            OffsetDateTime grantedAt,
            long grantedBy,
            OffsetDateTime expiresAt,
            String reasonCode) {

        public static GrantResponse from(ContentGrant grant) {
            return new GrantResponse(
                    grant.aclId(), grant.reportId(), grant.principalUserId(),
                    grant.permission().name(), grant.grantedAt(), grant.grantedBy(),
                    grant.expiresAt(), grant.reasonCode());
        }
    }
}
