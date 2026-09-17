package com.dwp.services.approval.policyautomation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PolicyGovernanceModels {
    private PolicyGovernanceModels() {
    }

    interface StrictInput {
        @JsonAnySetter
        default void rejectUnknown(String key, Object value) {
            throw PolicyAutomationRejected.invalid(
                    "Unknown policy-governance input field: " + key);
        }
    }

    public enum SimulationOutcome {
        PASS, WARNING, BLOCKED
    }

    public enum ReviewDisposition {
        APPROVED, CHANGES_REQUESTED, REJECTED
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record SimulationCommand(
            @NotNull UUID simulationId,
            @NotNull UUID revisionId,
            @Min(1) @Max(9_007_199_254_740_991L) long expectedVersion,
            @NotNull Map<String, Object> scenario) implements StrictInput {
    }

    public record SimulationReceipt(
            UUID simulationId,
            UUID policyId,
            UUID revisionId,
            long policyVersion,
            SimulationOutcome outcome,
            List<String> blockers,
            List<String> warnings,
            Map<String, Object> result,
            String definitionSha256,
            Instant createdAt) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ReviewCommand(
            @NotNull UUID reviewId,
            @NotNull UUID revisionId,
            @Min(1) @Max(9_007_199_254_740_991L) long expectedVersion,
            @NotNull ReviewDisposition disposition,
            @NotBlank @Size(min = 10, max = 2000) String comment,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}")
            String evidenceSha256) implements StrictInput {
    }

    public record ReviewReceipt(
            UUID reviewId,
            UUID policyId,
            UUID revisionId,
            long reviewedPolicyVersion,
            long committedPolicyVersion,
            ReviewDisposition disposition,
            String comment,
            String evidenceSha256,
            long checkerUserId,
            UUID checkerPersonPublicId,
            Instant createdAt) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record FreezeCommand(
            boolean active,
            @Min(1) @Max(9_007_199_254_740_991L) long expectedVersion,
            @NotBlank @Size(min = 10, max = 2000) String reason)
            implements StrictInput {
    }

    public record FreezeState(
            UUID policyId,
            boolean active,
            String reason,
            long changedBy,
            Instant changedAt,
            long version,
            long policyVersion) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ExportCommand(
            @NotNull UUID exportId,
            @NotNull UUID revisionId,
            @Min(1) @Max(9_007_199_254_740_991L) long expectedVersion)
            implements StrictInput {
    }

    public record PolicyExport(
            UUID exportId,
            UUID policyId,
            UUID revisionId,
            long policyVersion,
            Map<String, Object> manifest,
            String manifestSha256,
            long requestedBy,
            Instant createdAt) {
    }

    public record RevisionDiff(
            UUID policyId,
            UUID fromRevisionId,
            UUID toRevisionId,
            String fromDefinitionSha256,
            String toDefinitionSha256,
            List<String> changedFields,
            Map<String, Object> fromDefinition,
            Map<String, Object> toDefinition,
            Instant generatedAt) {
    }
}
