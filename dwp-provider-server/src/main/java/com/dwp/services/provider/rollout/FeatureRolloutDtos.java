package com.dwp.services.provider.rollout;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FeatureRolloutDtos {

    private FeatureRolloutDtos() {
    }

    public record FeatureFlag(
            UUID featureFlagId,
            String featureKey,
            String displayName,
            String description,
            String ownerService,
            String valueType,
            JsonNode defaultValue,
            JsonNode configurationSchema,
            String riskTier,
            String lifecycleState,
            long version) {
    }

    public record CreateFeatureFlagRequest(
            @NotBlank @Size(max = 160)
            @Pattern(regexp = "^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*){1,7}$")
            String featureKey,
            @NotBlank @Size(max = 240) String displayName,
            @NotBlank @Size(max = 1200) String description,
            @NotBlank @Size(max = 120) String ownerService,
            @NotBlank @Pattern(regexp = "BOOLEAN|STRING|NUMBER|JSON") String valueType,
            @NotNull JsonNode defaultValue,
            @NotNull JsonNode configurationSchema,
            @NotBlank @Pattern(regexp = "L1|L2|L3") String riskTier) {
    }

    public record Stage(
            UUID rolloutStageId,
            int stageOrder,
            String stageName,
            BigDecimal exposurePercentage,
            int minimumObservationMinutes,
            JsonNode healthGate,
            String lifecycleState,
            Instant startedAt,
            Instant completedAt) {
    }

    public record Approval(
            UUID approvalId,
            String lifecycleState,
            Long requestedBy,
            Instant requestedAt,
            Long decidedBy,
            Instant decidedAt,
            String decisionReason) {
    }

    public record Rollout(
            UUID rolloutRevisionId,
            UUID featureFlagId,
            String featureKey,
            int revisionNumber,
            String name,
            String lifecycleState,
            JsonNode rolloutValue,
            JsonNode targeting,
            String strategy,
            Integer currentStageOrder,
            UUID previousRevisionId,
            UUID rollbackOfRevisionId,
            String justification,
            Long requestedBy,
            Long approvedBy,
            Instant submittedAt,
            Instant approvedAt,
            Instant activatedAt,
            Instant completedAt,
            Instant pausedAt,
            long version,
            List<Stage> stages,
            Approval approval,
            boolean externalExecutionEnabled) {
    }

    public record CreateRolloutRequest(
            @NotBlank @Size(max = 240) String name,
            @NotNull JsonNode rolloutValue,
            @NotNull JsonNode targeting,
            @NotBlank @Pattern(regexp = "RING|PERCENTAGE|ALL_AT_ONCE") String strategy,
            @NotBlank @Size(max = 1200) String justification,
            @NotEmpty @Size(max = 20) List<@Valid StageRequest> stages) {
    }

    public record StageRequest(
            @NotBlank @Size(max = 160) String stageName,
            @NotNull @DecimalMin("0.01") @DecimalMax("100.00")
            BigDecimal exposurePercentage,
            @Min(0) int minimumObservationMinutes,
            @NotNull JsonNode healthGate) {
    }

    public record VersionedReasonRequest(
            @Min(0) long version,
            @NotBlank @Size(max = 1200) String reason) {
    }

    public record AdvanceRequest(
            @Min(0) long version,
            @NotBlank @Size(max = 1200) String reason,
            @NotNull JsonNode observedHealth) {
    }

    public record ApprovalDecisionRequest(
            @Min(0) long version,
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @NotBlank @Size(max = 1200) String reason) {
    }

    public record Evaluation(
            String featureKey,
            UUID providerTenantId,
            String tenantKey,
            JsonNode value,
            String reasonCode,
            UUID rolloutRevisionId,
            Integer revisionNumber,
            BigDecimal exposurePercentage,
            int deterministicBucket,
            boolean externalExecutionEnabled,
            Instant evaluatedAt) {
    }
}
