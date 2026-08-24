package com.dwp.services.approval.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Closed, default-deny response schemas for non-management Approval read profiles. */
public final class ApprovalProjectionDtos {

    private ApprovalProjectionDtos() {
    }

    @Schema(name = "ApprovalOversightAdminPulseV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record OversightAdminPulseV1(
            int publishedWorkflows,
            int draftWorkflows,
            int activeRequests,
            int overdueTasks,
            int failedIntegrations,
            List<OversightAssuranceSignalV1> assurance) {
    }

    @Schema(name = "ApprovalOversightAssuranceSignalV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record OversightAssuranceSignalV1(String key, String state, int exceptions) {
    }

    @Schema(name = "ApprovalOversightWorkflowV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record OversightWorkflowV1(
            UUID workflowId,
            String workflowKey,
            String nameKo,
            String nameEn,
            String category,
            String dataClassification,
            String lifecycleState,
            int currentVersion,
            int slaMinutes,
            long version,
            Instant updatedAt) {
    }

    @Schema(name = "ApprovalOversightFormV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record OversightFormV1(
            UUID formId,
            String formKey,
            UUID categoryId,
            String categoryKey,
            String categoryNameKo,
            String categoryNameEn,
            String nameKo,
            String nameEn,
            String formKind,
            String lifecycleState,
            int currentVersion,
            int fieldCount,
            int routeCount,
            long usageCount,
            long version,
            Instant updatedAt) {
    }

    @Schema(name = "ApprovalOversightFormCategoryV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record OversightFormCategoryV1(
            UUID categoryId,
            String categoryKey,
            UUID parentCategoryId,
            String nameKo,
            String nameEn,
            String iconKey,
            int sortOrder,
            String lifecycleState,
            int formCount,
            long version) {
    }

    @Schema(name = "ApprovalOversightPolicyV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record OversightPolicyV1(
            UUID policyId,
            String policyKey,
            String nameKo,
            String nameEn,
            String policyType,
            String enforcementMode,
            String severity,
            String lifecycleState,
            long version,
            boolean pendingReview,
            String pendingEnforcementMode,
            String pendingSeverity,
            String pendingLifecycleState,
            Instant pendingAt) {
    }

    @Schema(name = "ApprovalOversightPolicyVersionV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record OversightPolicyVersionV1(
            UUID policyVersionId,
            int versionNumber,
            String enforcementMode,
            String severity,
            String lifecycleState,
            Instant submittedAt,
            Instant publishedAt) {
    }

    @Schema(name = "ApprovalOversightOperationsV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record OversightOperationsV1(
            Instant generatedAt,
            List<OversightOperationSignalV1> signals,
            List<OversightIntegrationDeliveryV1> integrationDeliveries) {
    }

    @Schema(name = "ApprovalOversightOperationSignalV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record OversightOperationSignalV1(
            String key,
            String state,
            String titleKo,
            String titleEn,
            int count) {
    }

    @Schema(name = "ApprovalOversightIntegrationDeliveryV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record OversightIntegrationDeliveryV1(
            UUID outboxId,
            String eventType,
            String status,
            int attemptCount,
            int manualRetryCount,
            Instant availableAt,
            Instant publishedAt,
            Instant createdAt,
            Instant lastRetriedAt) {
    }

    @Schema(name = "ApprovalAuditorOperationsV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record AuditorOperationsV1(
            Instant generatedAt,
            List<AuditorOperationSignalV1> signals,
            List<AuditorIntegrationDeliveryV1> integrationDeliveries) {
    }

    @Schema(name = "ApprovalAuditorOperationSignalV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record AuditorOperationSignalV1(String key, String state, int count) {
    }

    @Schema(name = "ApprovalAuditorIntegrationDeliveryV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record AuditorIntegrationDeliveryV1(
            String eventType,
            String status,
            int attemptCount,
            int manualRetryCount,
            Instant availableAt,
            Instant publishedAt) {
    }

    @Schema(name = "ApprovalOversightSignatureV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record OversightSignatureV1(
            UUID providerId,
            String providerKey,
            String displayName,
            String providerType,
            String lifecycleState,
            boolean credentialConfigured,
            Instant lastHealthCheckedAt,
            long version) {
    }

    /** Full-management remains read-only and must never expose credential capabilities. */
    @Schema(name = "ApprovalFullManagementSignatureV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record FullManagementSignatureV1(
            UUID providerId,
            String providerKey,
            String displayName,
            String providerType,
            String lifecycleState,
            boolean credentialConfigured,
            Instant lastHealthCheckedAt,
            long version) {
    }
}
