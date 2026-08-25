package com.dwp.services.auth.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AccessReviewDtos {

    private AccessReviewDtos() {
    }

    public record CreateCampaignRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotBlank @Pattern(regexp = "TENANT|ROLE|GROUP") String scopeType,
            Long scopeRef,
            @NotBlank @Pattern(regexp = "TENANT_ADMIN|NAMED_REVIEWER") String reviewerStrategy,
            Long reviewerUserId,
            @NotNull @Future Instant dueAt) {
    }

    public record CampaignSummary(
            UUID campaignId,
            String name,
            String description,
            String scopeType,
            Long scopeRef,
            String reviewerStrategy,
            Long reviewerUserId,
            String lifecycleState,
            Instant dueAt,
            Instant activatedAt,
            Instant completedAt,
            long totalItems,
            long pendingItems,
            long approvedItems,
            long revokedItems,
            long manualRemediationItems,
            long version) {
    }

    public record CampaignItems(
            CampaignSummary campaign,
            List<ItemSummary> items) {
    }

    public record ItemSummary(
            UUID itemId,
            Long subjectUserId,
            String subjectDisplayName,
            String subjectEmail,
            Long roleId,
            String roleCode,
            String roleName,
            String accessSourceType,
            Long accessSourceId,
            String sourceKey,
            String sourceDisplayName,
            Instant assignmentCreatedAt,
            Instant subjectLastSignInAt,
            boolean privileged,
            String recommendation,
            String recommendationReason,
            Long reviewerUserId,
            String decision,
            String decisionReason,
            Long decidedBy,
            Instant decidedAt,
            String remediationState,
            long version) {
    }

    /**
     * Privacy-bounded projection for the assigned Work surface. Internal campaign and
     * review-item identifiers are intentionally omitted; callers retain only workItemRef.
     */
    public record WorkItemDetail(
            UUID workItemRef,
            String campaignName,
            Instant dueAt,
            Long subjectUserId,
            String subjectDisplayName,
            String subjectEmail,
            Long roleId,
            String roleCode,
            String roleName,
            String accessSourceType,
            String sourceKey,
            String sourceDisplayName,
            Instant assignmentCreatedAt,
            Instant subjectLastSignInAt,
            boolean privileged,
            String recommendation,
            String recommendationReason,
            String decision,
            String decisionReason,
            Instant decidedAt,
            String remediationState,
            long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record DecisionRequest(
            @NotBlank @Pattern(regexp = "APPROVE|REVOKE") String decision,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotNull @Min(0) Long version) {
    }
}
