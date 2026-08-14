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
            Map<String, Object> payload) {
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
            int failedIntegrations) {
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

    public record FormSummary(
            UUID formId,
            String formKey,
            String nameKo,
            String nameEn,
            String lifecycleState,
            int currentVersion,
            int fieldCount,
            long version,
            Instant updatedAt) {
    }

    public record FormDetail(
            FormSummary form,
            Map<String, Object> schema,
            String schemaHash) {
    }

    public record RequestTemplate(
            WorkflowSummary workflow,
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
            long version) {
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
            List<TaskSummary> breachedTasks) {
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
            String scopeType,
            String workflowKey,
            Instant startsAt,
            Instant endsAt,
            String lifecycleState,
            String reason,
            long version) {
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
            @NotNull Long expectedVersion) {
    }

    public record CreateRequest(
            @NotNull UUID workflowId,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 2000) String summary,
            @NotBlank String priority,
            Map<String, Object> payload) {
    }

    public record UpdateDraftRequest(
            @NotNull UUID workflowId,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 2000) String summary,
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
            @NotBlank @Size(max = 1000) String reason) {
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

    public record UpdateFormDraftRequest(
            @NotBlank @Size(max = 200) String nameKo,
            @NotBlank @Size(max = 200) String nameEn,
            @NotEmpty @Size(max = 50) List<@Valid FormFieldInput> fields,
            @NotNull Long expectedVersion) {
    }

    public record UpdatePolicyRequest(
            @NotBlank String enforcementMode,
            @NotBlank String severity,
            @NotBlank String lifecycleState,
            @NotNull Map<String, Object> rule,
            @NotNull Long expectedVersion) {
    }
}
