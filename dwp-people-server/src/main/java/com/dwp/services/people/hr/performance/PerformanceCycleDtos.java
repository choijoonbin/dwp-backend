package com.dwp.services.people.hr.performance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PerformanceCycleDtos {

    private PerformanceCycleDtos() {
    }

    public record StageInput(
            @NotBlank @Size(max = 80) String stageKey,
            @NotBlank @Size(max = 32) String stageType,
            @Min(1) int sequenceNo,
            @NotNull Instant opensAt,
            @NotNull Instant closesAt,
            boolean required,
            @NotNull Map<String, Object> stageConfig) {
    }

    public record CreateCycleRequest(
            @NotNull UUID commandId,
            @NotBlank @Size(max = 100) String cycleKey,
            @NotBlank @Size(max = 240) String displayName,
            @NotNull UUID retentionPolicyId,
            @NotNull Instant effectiveFrom,
            Instant effectiveTo,
            @NotBlank @Size(max = 80) String timezoneId,
            @NotNull UUID policyVersionId,
            UUID populationRuleVersionId,
            @NotEmpty List<@Valid StageInput> stages) {
    }

    public record UpdateCycleRequest(
            @NotNull UUID commandId,
            @Min(0) long expectedRevision,
            @NotBlank @Size(max = 240) String displayName,
            @NotNull UUID retentionPolicyId,
            @NotNull Instant effectiveFrom,
            Instant effectiveTo,
            @NotBlank @Size(max = 80) String timezoneId,
            @NotNull UUID policyVersionId,
            UUID populationRuleVersionId,
            @NotEmpty List<@Valid StageInput> stages) {
    }

    public record ValidateCycleRequest(
            @NotNull UUID commandId,
            @Min(0) long expectedRevision) {
    }

    public record PreviewPopulationRequest(
            @NotNull UUID commandId,
            @Min(0) long expectedRevision,
            @NotNull Instant asOf) {
    }

    public record PublishCycleRequest(
            @NotNull UUID commandId,
            @Min(0) long expectedRevision,
            @NotNull UUID populationPreviewId,
            @Positive long expectedWorkforceOwnerRevision,
            @NotNull UUID publicationApprovalRef,
            @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record CycleCollection(
            List<CycleSummary> cycles,
            List<String> allowedActions) {
    }

    public record CycleSummary(
            UUID cycleId,
            String cycleKey,
            String displayName,
            String lifecycleState,
            Integer activeVersionNo,
            long aggregateVersion,
            Instant effectiveFrom,
            Instant effectiveTo,
            List<String> allowedActions) {
    }

    public record CycleDetail(
            UUID cycleId,
            String cycleKey,
            String displayName,
            String lifecycleState,
            Integer activeVersionNo,
            long aggregateVersion,
            UUID retentionPolicyId,
            Instant createdAt,
            Long createdBy,
            Instant updatedAt,
            Long updatedBy,
            CycleVersion version,
            List<String> allowedActions) {
    }

    public record CycleVersion(
            UUID cycleVersionId,
            int versionNo,
            String versionState,
            long aggregateVersion,
            Instant effectiveFrom,
            Instant effectiveTo,
            String timezoneId,
            UUID policyVersionId,
            UUID populationRuleVersionId,
            String contentHash,
            Long authoredBy,
            Instant publishedAt,
            Long publishedBy,
            List<CycleStage> stages) {
    }

    public record CycleStage(
            UUID stageId,
            String stageKey,
            String stageType,
            int sequenceNo,
            Instant opensAt,
            Instant closesAt,
            boolean required,
            Map<String, Object> stageConfig) {
    }

    public record PopulationPreview(
            UUID populationPreviewId,
            UUID cycleVersionId,
            UUID workforceSnapshotId,
            long workforceSnapshotRevision,
            UUID populationRuleVersionId,
            long sourceCycleAggregateVersion,
            String state,
            int participantCount,
            int reviewerAssignmentCount,
            String contentHash,
            long aggregateVersion,
            Instant createdAt,
            Instant expiresAt,
            List<PreviewMember> members) {
    }

    public record PreviewMember(
            UUID participantRef,
            UUID primaryAssignmentRef,
            String workforceStatus,
            UUID organizationRef,
            UUID reviewerAssignmentRef,
            UUID jobProfileRef,
            UUID gradeRef,
            String eligibilityCode) {
    }

    public record CommandReceipt(
            UUID receiptId,
            String commandType,
            String originatingAction,
            UUID aggregateId,
            long expectedAggregateVersion,
            Long appliedAggregateVersion,
            String state,
            UUID resultRef,
            String errorCode,
            Instant acceptedAt,
            Instant completedAt) {
    }

    public record CycleCommandResult(
            CycleDetail cycle,
            CommandReceipt receipt) {
    }

    public record PreviewCommandResult(
            PopulationPreview preview,
            CommandReceipt receipt) {
    }
}
