package com.dwp.services.approval.policyimpact;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApprovalPolicyImpactDtos {
    private ApprovalPolicyImpactDtos() { }
    @Schema(name="ApprovalPolicyImpactRules", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Rules(String enforcementMode, String severity, String lifecycleState, Map<String, Object> rule) {
        public Rules { rule = Map.copyOf(rule); }
    }
    @Schema(name="ApprovalPolicyImpactHead", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Head(UUID policyId, String policyKey, long rowVersion, UUID publishedVersionId,
            Integer publishedVersion, Rules current, Rules pending, Long pendingBy, Instant pendingAt,
            String metadataProvenance, Instant capturedAt) {
        public Head(UUID policyId, String policyKey, long rowVersion, UUID publishedVersionId,
                Integer publishedVersion, Rules current, Rules pending, Long pendingBy, Instant pendingAt) {
            this(policyId, policyKey, rowVersion, publishedVersionId, publishedVersion,
                    current, pending, pendingBy, pendingAt, "UNRECORDED_HISTORICAL_METADATA", null);
        }
    }
    @Schema(name="ApprovalPolicyImpactDiff", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Diff(String path, String kind, Object current, Object proposed) { }
    @Schema(name="ApprovalPolicyImpactRow", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Row(UUID id, UUID workflowVersionId, UUID requestId, long version, String definition) { }
    @Schema(name="ApprovalPolicyImpactPage", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Page(List<Row> rows, boolean truncated) {
        public Page { rows = List.copyOf(rows); }
    }
    @Schema(name="ApprovalPolicyImpactEffect", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Effect(List<String> reasons, boolean constraintChanged, boolean pinConflict,
            boolean configurationOnly, boolean unknown) {
        public Effect { reasons = List.copyOf(reasons); }
    }
    @Schema(name="ApprovalPolicyImpactItem", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Item(UUID id, UUID workflowVersionId, UUID requestId, long version, Effect effect) { }
    @Schema(name="ApprovalPolicyImpactCounts", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Counts(int examined, int constraintChanged, int pinConflict, int configurationOnly,
            int unknown, boolean complete, String countKind) { }
    @Schema(name="ApprovalPolicyImpactFamily", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Family(Counts counts, List<Item> items) {
        public Family { items = List.copyOf(items); }
    }
    @Schema(name="ApprovalPolicyImpactRuntimeDifference", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record RuntimeDifference(boolean currentFrozenPinConflict, boolean publishInvalidatesPins,
            boolean constraintChanged, boolean proposedUnavailable, int immutableStages, int immutableTimers,
            String currentPolicySha256, String proposedPolicySha256) { }
    @Schema(name="ApprovalPolicyImpactResult", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Result(String status, Head policy, String sourceDigest, List<Diff> semanticDiff,
            Family workflows, Family requests, Family tasks, Instant observedAt,
            ApprovalPolicyImpactAuthority.Window authority) {
        public Result { semanticDiff = List.copyOf(semanticDiff); }
    }
}
