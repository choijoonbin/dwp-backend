package com.dwp.services.approval.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApprovalDtos {

    private ApprovalDtos() {
    }

    public record ApprovalMetrics(
            int pending,
            int dueToday,
            int overdue,
            int needsInformation,
            int myRequestsInFlight,
            double averageCycleHours,
            double slaCompliancePercent) {
    }

    public record TaskSummary(
            UUID taskId,
            UUID requestId,
            String requestNumber,
            String title,
            String summary,
            String workflowNameKo,
            String workflowNameEn,
            String stepKey,
            String stepName,
            int stepSequence,
            String requesterName,
            String requesterOrgName,
            String status,
            String priority,
            String dataClassification,
            int riskScore,
            Instant submittedAt,
            Instant dueAt,
            long version) {
    }

    public record TimelineEvent(
            UUID eventId,
            String eventType,
            String actorType,
            String actorId,
            String outcome,
            String message,
            Instant occurredAt) {
    }

    public record TaskDetail(
            TaskSummary task,
            Map<String, Object> payload,
            List<TimelineEvent> timeline,
            boolean canClaim,
            boolean canDecide,
            boolean selfApprovalBlocked) {
    }

    public record RequestSummary(
            UUID requestId,
            String requestNumber,
            String title,
            String summary,
            String workflowNameKo,
            String workflowNameEn,
            String currentStepKey,
            String currentStepName,
            Integer currentStepSequence,
            int totalSteps,
            String status,
            String priority,
            String dataClassification,
            String latestInformationRequest,
            Instant submittedAt,
            Instant dueAt,
            Instant completedAt,
            long version) {
    }

    public record RequestDetail(
            RequestSummary request,
            UUID workflowId,
            UUID formId,
            Map<String, Object> payload,
            List<TimelineEvent> timeline) {
    }

    public record StageMetric(String stage, int count, int atRisk) {
    }

    public record DecisionInsight(
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
            ApprovalMetrics metrics,
            List<TaskSummary> focusQueue,
            List<RequestSummary> recentRequests,
            List<StageMetric> flow,
            List<DecisionInsight> insights,
            boolean administrator,
            AdminPulse adminPulse) {
    }

    public record AdminPulse(
            int publishedWorkflows,
            int draftWorkflows,
            int activeRequests,
            int overdueTasks,
            int failedIntegrations,
            List<AssuranceSignal> assurance) {
    }

    public record AssuranceSignal(
            String key,
            String state,
            int exceptions) {
    }

    public record WorkflowSummary(
            UUID workflowId,
            String workflowKey,
            String nameKo,
            String nameEn,
            String descriptionKo,
            String descriptionEn,
            String category,
            String dataClassification,
            String lifecycleState,
            int currentVersion,
            int slaMinutes,
            boolean allowSelfApproval,
            String ownerGroupRef,
            long version,
            Instant updatedAt) {
    }

    public record WorkflowDetail(
            WorkflowSummary workflow,
            Map<String, Object> definition,
            String definitionHash) {
    }

    public record FormCategorySummary(
            UUID categoryId,
            String categoryKey,
            UUID parentCategoryId,
            String nameKo,
            String nameEn,
            String descriptionKo,
            String descriptionEn,
            String iconKey,
            int sortOrder,
            String lifecycleState,
            int formCount,
            long version) {
    }

    public record FormRouteSummary(
            UUID bindingId,
            UUID workflowId,
            String workflowKey,
            String workflowNameKo,
            String workflowNameEn,
            String workflowLifecycleState,
            int workflowVersion,
            int slaMinutes,
            String bindingType,
            int priority) {
    }

    public record FormSummary(
            UUID formId,
            String formKey,
            UUID categoryId,
            String categoryKey,
            String categoryNameKo,
            String categoryNameEn,
            String nameKo,
            String nameEn,
            String descriptionKo,
            String descriptionEn,
            String ownerGroupRef,
            String formKind,
            String lifecycleState,
            int currentVersion,
            int fieldCount,
            int routeCount,
            long usageCount,
            long version,
            Instant updatedAt) {
    }

    public record FormDetail(
            FormSummary form,
            Map<String, Object> schema,
            String schemaHash,
            List<FormRouteSummary> routes) {
    }

    public record RequestTemplate(
            WorkflowSummary workflow,
            Map<String, Object> routeDefinition,
            FormDetail form) {
    }

    public record PolicySummary(
            UUID policyId,
            String policyKey,
            String nameKo,
            String nameEn,
            String policyType,
            String enforcementMode,
            String severity,
            String lifecycleState,
            Map<String, Object> rule,
            long version,
            boolean pendingReview,
            String pendingEnforcementMode,
            String pendingSeverity,
            String pendingLifecycleState,
            Map<String, Object> pendingRule,
            String pendingChangeReason,
            Long pendingBy,
            Instant pendingAt) {
    }

    public record PolicyVersionSummary(
            UUID policyVersionId,
            int versionNumber,
            String enforcementMode,
            String severity,
            String lifecycleState,
            Map<String, Object> rule,
            String changeReason,
            Long submittedBy,
            Instant submittedAt,
            Long publishedBy,
            Instant publishedAt,
            String reviewComment) {
    }

    public record OperationSignal(
            String key,
            String state,
            String titleKo,
            String titleEn,
            String detailKo,
            String detailEn,
            int count) {
    }

    public record OperationsResponse(
            Instant generatedAt,
            List<OperationSignal> signals,
            List<TaskSummary> breachedTasks,
            List<IntegrationDeliverySummary> integrationDeliveries) {
    }

    public record IntegrationDeliverySummary(
            UUID outboxId,
            UUID eventId,
            UUID requestId,
            String eventType,
            String status,
            int attemptCount,
            int manualRetryCount,
            Instant availableAt,
            Instant publishedAt,
            String lastError,
            Instant createdAt,
            Instant lastRetriedAt) {
    }

    public record SignatureProviderSummary(
            UUID providerId,
            String providerKey,
            String displayName,
            String providerType,
            String lifecycleState,
            Map<String, Object> capabilities,
            boolean credentialConfigured,
            Instant lastHealthCheckedAt,
            long version) {
    }

    public record DelegationSummary(
            UUID delegationId,
            long delegatorUserId,
            long delegateUserId,
            UUID delegatePersonPublicId,
            String delegateDisplayName,
            String delegateEmail,
            String scopeType,
            String workflowKey,
            Instant startsAt,
            Instant endsAt,
            String lifecycleState,
            String reason,
            long version,
            String direction) {
    }

    public record DelegationCandidate(
            long userId,
            UUID personPublicId,
            String displayName,
            String email,
            String jobTitle) {
    }

    public record DecisionRequest(
            @NotBlank String decision,
            @Size(max = 2000) String comment,
            @NotNull Long expectedVersion) {
    }

    public record VersionedActionRequest(@NotNull Long expectedVersion) {
    }

    public record InformationResponseRequest(
            @NotBlank @Size(max = 2000) String message,
            Map<String, Object> payload,
            @NotNull Long expectedVersion) {
    }

    public record CreateRequest(
            @NotNull UUID workflowId,
            @NotNull UUID formId,
            @NotNull @Size(max = 300) String title,
            @NotNull @Size(max = 2000) String summary,
            @NotBlank String priority,
            Map<String, Object> payload) {
    }

    public record UpdateDraftRequest(
            @NotNull UUID workflowId,
            @NotNull UUID formId,
            @NotNull @Size(max = 300) String title,
            @NotNull @Size(max = 2000) String summary,
            @NotBlank String priority,
            Map<String, Object> payload,
            @NotNull Long expectedVersion) {
    }

    public record CreateDelegationRequest(
            @NotNull Long delegateUserId,
            @NotBlank String scopeType,
            String workflowKey,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record PublishWorkflowRequest(@NotNull Long expectedVersion) {
    }

    public record WorkflowStepInput(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String key,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "ANY") String mode,
            @NotBlank @Size(max = 160) String candidateRole,
            @NotNull @Min(15) @Max(525600) Integer slaMinutes) {
    }

    public record CreateWorkflowDraftRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,99}") String workflowKey,
            @NotBlank @Size(max = 200) String nameKo,
            @NotBlank @Size(max = 200) String nameEn,
            @NotBlank @Size(max = 1000) String descriptionKo,
            @NotBlank @Size(max = 1000) String descriptionEn,
            @NotBlank String category,
            @NotBlank String dataClassification,
            @NotNull @Min(15) @Max(525600) Integer slaMinutes,
            @NotBlank @Size(max = 160) String ownerGroupRef,
            @NotEmpty @Size(max = 20) List<@Valid WorkflowStepInput> steps) {
    }

    public record UpdateWorkflowDraftRequest(
            @NotBlank @Size(max = 200) String nameKo,
            @NotBlank @Size(max = 200) String nameEn,
            @NotBlank @Size(max = 1000) String descriptionKo,
            @NotBlank @Size(max = 1000) String descriptionEn,
            @NotBlank String category,
            @NotBlank String dataClassification,
            @NotNull @Min(15) @Max(525600) Integer slaMinutes,
            @NotBlank @Size(max = 160) String ownerGroupRef,
            @NotEmpty @Size(max = 20) List<@Valid WorkflowStepInput> steps,
            @NotNull Long expectedVersion) {
    }

    public record FormFieldInput(
            @NotBlank @Pattern(regexp = "[a-z][A-Za-z0-9_]{1,79}") String key,
            @NotBlank @Size(max = 160) String labelKo,
            @NotBlank @Size(max = 160) String labelEn,
            @Size(max = 500) String helpKo,
            @Size(max = 500) String helpEn,
            @NotBlank String type,
            boolean required,
            @Size(max = 50) List<@NotBlank @Size(max = 160) String> options) {
    }

    public record CreateFormCategoryRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,99}") String categoryKey,
            UUID parentCategoryId,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @Size(max = 600) String descriptionKo,
            @Size(max = 600) String descriptionEn,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,79}") String iconKey,
            @NotNull @Min(0) @Max(10000) Integer sortOrder) {
    }

    public record UpdateFormCategoryRequest(
            UUID parentCategoryId,
            @NotBlank @Size(max = 160) String nameKo,
            @NotBlank @Size(max = 160) String nameEn,
            @Size(max = 600) String descriptionKo,
            @Size(max = 600) String descriptionEn,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,79}") String iconKey,
            @NotNull @Min(0) @Max(10000) Integer sortOrder,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String lifecycleState,
            @NotNull Long expectedVersion) {
    }

    public record CreateFormDraftRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,99}") String formKey,
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 200) String nameKo,
            @NotBlank @Size(max = 200) String nameEn,
            @NotBlank @Size(max = 1000) String descriptionKo,
            @NotBlank @Size(max = 1000) String descriptionEn,
            @NotBlank @Size(max = 160) String ownerGroupRef,
            @NotNull UUID defaultWorkflowId,
            @NotEmpty @Size(max = 50) List<@Valid FormFieldInput> fields) {
    }

    public record UpdateFormDraftRequest(
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 200) String nameKo,
            @NotBlank @Size(max = 200) String nameEn,
            @NotBlank @Size(max = 1000) String descriptionKo,
            @NotBlank @Size(max = 1000) String descriptionEn,
            @NotBlank @Size(max = 160) String ownerGroupRef,
            @NotNull UUID defaultWorkflowId,
            @NotEmpty @Size(max = 50) List<@Valid FormFieldInput> fields,
            @NotNull Long expectedVersion) {
    }

    public record PublishFormRequest(@NotNull Long expectedVersion) {
    }

    public record UpdatePolicyRequest(
            @NotBlank String enforcementMode,
            @NotBlank String severity,
            @NotBlank String lifecycleState,
            @NotNull Map<String, Object> rule,
            @NotBlank @Size(min = 10, max = 1000) String changeReason,
            @NotNull Long expectedVersion) {
    }

    public record PublishPolicyRequest(
            @NotNull Long expectedVersion,
            @NotBlank @Size(min = 10, max = 1000) String reviewComment) {
    }
}
