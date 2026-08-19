package com.dwp.services.space.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SpaceDtos {

    private SpaceDtos() {
    }

    public record HomeMetrics(
            int mySpaces,
            int discoverableSpaces,
            int pendingRequests,
            int reviewQueue,
            int unreadSignals) {
    }

    public record Insight(
            String key,
            String tone,
            String titleKo,
            String titleEn,
            String detailKo,
            String detailEn,
            String route) {
    }

    public record HomeResponse(
            Instant generatedAt,
            HomeMetrics metrics,
            List<SpaceSummary> focusSpaces,
            List<ActivitySummary> recentActivity,
            List<TemplateSummary> recommendedTemplates,
            List<Insight> insights,
            boolean canCreate,
            boolean canAdminister) {
    }

    public record SpaceSummary(
            UUID spaceId,
            String spaceKey,
            String nameKo,
            String nameEn,
            String summaryKo,
            String summaryEn,
            String purposeType,
            String visibility,
            String dataClassification,
            String memberRole,
            int memberCount,
            int contentCount,
            int unreadCount,
            String iconKey,
            String accentToken,
            String coverAssetUrl,
            String lifecycleState,
            Instant lastActivityAt,
            long version) {
    }

    public record SpaceDetail(
            SpaceSummary space,
            String contentPolicy,
            String appPolicy,
            String aiPolicy,
            boolean canContribute,
            boolean canModerate,
            boolean canManage,
            List<ContentSummary> featuredContent,
            List<AppBindingSummary> apps,
            List<ActivitySummary> activity) {
    }

    public record TemplateSummary(
            UUID templateId,
            String templateKey,
            String nameKo,
            String nameEn,
            String descriptionKo,
            String descriptionEn,
            String purposeType,
            String creationMode,
            String defaultVisibility,
            String defaultDataClassification,
            String iconKey,
            String accentToken,
            String lifecycleState,
            int currentVersion,
            long version) {
    }

    public record RequestSummary(
            UUID requestId,
            UUID templateId,
            String templateNameKo,
            String templateNameEn,
            long requesterUserId,
            String requesterName,
            String requestedKey,
            String requestedName,
            String requestedSummary,
            String requestedVisibility,
            String justification,
            String decisionMode,
            String riskLevel,
            Map<String, Object> policyEvidence,
            String status,
            String decisionNote,
            Instant createdAt,
            Instant decidedAt,
            long version) {
    }

    public record MemberSummary(
            UUID membershipId,
            String principalType,
            String principalRef,
            String memberRole,
            String membershipSource,
            String lifecycleState,
            Instant validFrom,
            Instant validUntil,
            long version) {
    }

    public record AccessRequestSummary(
            UUID accessRequestId,
            UUID spaceId,
            String spaceKey,
            String spaceNameKo,
            String spaceNameEn,
            long requesterUserId,
            String requesterName,
            String requestedRole,
            String justification,
            String decisionMode,
            String status,
            String decisionNote,
            Instant createdAt,
            Instant decidedAt,
            long version) {
    }

    public record ContentSummary(
            UUID contentId,
            String contentType,
            String title,
            String summary,
            String route,
            String dataClassification,
            String lifecycleState,
            long authorUserId,
            String authorName,
            int currentRevision,
            Instant publishedAt,
            Instant updatedAt) {
    }

    public record AppBindingSummary(
            UUID bindingId,
            String appKey,
            String displayNameKo,
            String displayNameEn,
            String launchTarget,
            String iconKey,
            String dataAccessScope,
            String lifecycleState) {
    }

    public record ActivitySummary(
            UUID activityId,
            String spaceKey,
            String spaceNameKo,
            String spaceNameEn,
            String activityType,
            String actorType,
            String actorName,
            String objectType,
            String titleKo,
            String titleEn,
            String route,
            Instant occurredAt) {
    }

    public record LifecycleReviewSummary(
            UUID lifecycleReviewId,
            UUID spaceId,
            String spaceKey,
            String spaceNameKo,
            String spaceNameEn,
            String reviewType,
            Instant dueAt,
            String status,
            String recommendation,
            Map<String, Object> evidence) {
    }

    public record PublicationReviewSummary(
            UUID reviewId,
            UUID spaceId,
            String spaceKey,
            String spaceNameKo,
            String spaceNameEn,
            UUID contentId,
            String contentTitle,
            String contentType,
            String dataClassification,
            String reviewerStrategy,
            String status,
            Instant createdAt) {
    }

    public record AdminMetrics(
            int activeSpaces,
            int restrictedSpaces,
            int pendingCreationRequests,
            int pendingPublicationReviews,
            int overdueLifecycleReviews,
            int activeMemberships) {
    }

    public record AdminOverview(
            Instant generatedAt,
            AdminMetrics metrics,
            List<RequestSummary> priorityRequests,
            List<PublicationReviewSummary> publicationQueue,
            List<LifecycleReviewSummary> lifecycleQueue,
            List<SpaceSummary> portfolio) {
    }

    public record OperationsMetrics(
            int queuedDeliveries,
            int deadLetters,
            int openFindings,
            int highRiskFindings,
            int ownerlessSpaces,
            int overdueReviews,
            int synchronizedLast24Hours) {
    }

    public record ReconciliationRunSummary(
            UUID runId,
            String triggerType,
            String lifecycleState,
            int plannedCount,
            int expiredCount,
            int findingCount,
            Long requestedBy,
            Map<String, Object> summary,
            Instant startedAt,
            Instant completedAt) {
    }

    public record ReconciliationFindingSummary(
            UUID findingId,
            UUID spaceId,
            UUID membershipId,
            String findingType,
            String severity,
            String lifecycleState,
            String targetType,
            String targetRef,
            String title,
            Map<String, Object> evidence,
            Instant firstDetectedAt,
            Instant lastDetectedAt) {
    }

    public record EntitlementSyncSummary(
            UUID syncItemId,
            UUID spaceId,
            UUID membershipId,
            String principalType,
            String principalRef,
            String resourceKey,
            String permissionCode,
            String desiredState,
            String deliveryState,
            int attemptCount,
            Instant nextAttemptAt,
            String externalState,
            String lastError,
            Instant lastAttemptAt,
            Instant synchronizedAt) {
    }

    public record OperationsDashboard(
            Instant generatedAt,
            boolean entitlementProviderConfigured,
            OperationsMetrics metrics,
            List<ReconciliationRunSummary> recentRuns,
            List<ReconciliationFindingSummary> findings,
            List<EntitlementSyncSummary> deliveries) {
    }

    public record CreateSpaceRequest(
            @NotNull UUID templateId,
            @NotBlank
            @Pattern(regexp = "[a-z][a-z0-9-]{2,99}") String requestedKey,
            @NotBlank @Size(max = 200) String requestedName,
            @NotBlank @Size(max = 1200) String requestedSummary,
            @NotBlank
            @Pattern(regexp = "OPEN|REQUEST|PRIVATE|HIDDEN") String requestedVisibility,
            @NotBlank @Size(min = 10, max = 2000) String justification) {
    }

    public record RequestDecision(
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(min = 5, max = 2000) String note,
            @NotNull Long expectedVersion) {
    }

    public record CreateContentRequest(
            @NotBlank
            @Pattern(regexp = "PAGE|POST|FILE|LINK|CANVAS|DECISION|APP_EMBED") String contentType,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 2000) String summary,
            @NotBlank
            @Pattern(regexp = "PUBLIC|INTERNAL|CONFIDENTIAL|RESTRICTED") String dataClassification,
            @NotNull Map<String, Object> content) {
    }

    public record ReviewDecision(
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(min = 5, max = 2000) String note) {
    }

    public record UpdatePolicyRequest(
            @NotBlank
            @Pattern(regexp = "OPEN_PUBLISH|OWNER_REVIEW|COMPLIANCE_REVIEW") String contentPolicy,
            @NotBlank
            @Pattern(regexp = "OWNER_MANAGED|OWNER_REVIEW|ADMIN_REVIEW") String appPolicy,
            @NotBlank
            @Pattern(regexp = "DISABLED|MEMBER_SCOPED|RESTRICTED_SCOPED") String aiPolicy,
            @NotNull Long expectedVersion) {
    }

    public record SaveTemplateRequest(
            @NotBlank
            @Pattern(regexp = "[a-z][a-z0-9-]{2,99}") String templateKey,
            @NotBlank @Size(max = 200) String nameKo,
            @NotBlank @Size(max = 200) String nameEn,
            @NotBlank @Size(max = 1000) String descriptionKo,
            @NotBlank @Size(max = 1000) String descriptionEn,
            @NotBlank
            @Pattern(regexp = "PROJECT|COMMUNITY|OPERATIONS|KNOWLEDGE|LEADERSHIP") String purposeType,
            @NotBlank @Pattern(regexp = "AUTO|POLICY|APPROVAL") String creationMode,
            @NotBlank
            @Pattern(regexp = "OPEN|REQUEST|PRIVATE|HIDDEN") String defaultVisibility,
            @NotBlank
            @Pattern(regexp = "PUBLIC|INTERNAL|CONFIDENTIAL|RESTRICTED")
            String defaultDataClassification,
            @NotNull List<String> allowedContentTypes,
            @NotNull List<String> defaultApps,
            @NotBlank @Size(max = 60) String iconKey,
            @NotBlank @Size(max = 30) String accentToken,
            @NotBlank @Pattern(regexp = "DRAFT|PUBLISHED|RETIRED") String lifecycleState,
            Long expectedVersion) {
    }

    public record LifecycleDecision(
            @NotBlank
            @Pattern(regexp = "KEEP|ARCHIVE|DELETE|REVIEW_ACCESS") String recommendation,
            @NotBlank @Size(min = 5, max = 2000) String note) {
    }

    public record CreateAccessRequest(
            @NotBlank @Pattern(regexp = "VIEWER|CONTRIBUTOR") String requestedRole,
            @NotBlank @Size(min = 10, max = 2000) String justification) {
    }

    public record AccessDecision(
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(min = 5, max = 2000) String note,
            @NotNull Long expectedVersion) {
    }

    public record SaveMemberRequest(
            @NotBlank @Pattern(regexp = "USER|GROUP") String principalType,
            @NotBlank @Size(max = 200) String principalRef,
            @NotBlank
            @Pattern(regexp = "VIEWER|CONTRIBUTOR|EDITOR|MODERATOR|OWNER|GUEST") String memberRole,
            Instant validUntil) {
    }

    public record UpdateMemberRequest(
            @NotBlank
            @Pattern(regexp = "VIEWER|CONTRIBUTOR|EDITOR|MODERATOR|OWNER|GUEST") String memberRole,
            Instant validUntil,
            @NotNull Long expectedVersion) {
    }

    public record RecoverOwnerRequest(
            @NotNull UUID personPublicId,
            @NotBlank @Size(min = 10, max = 1000) String reason) {
    }
}
