package com.dwp.services.auth.tenantcapabilityoverride;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TenantCapabilityOverrideDtos {

    private TenantCapabilityOverrideDtos() {
    }

    @Schema(name = "TenantCapabilityOverrideCreateRequest")
    public record CreateRequest(
            @NotBlank @Size(max = 180) String contractKey,
            @NotBlank @Pattern(regexp = "DISABLED|INHERIT") String desiredState,
            @Future Instant validTo,
            @NotBlank @Size(min = 10, max = 1000) String justification) {
    }

    public record VersionedCommand(@NotNull @PositiveOrZero Long version) {
    }

    public record DecisionCommand(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record ReasonedCommand(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record Policy(
            String contractKey,
            String productKey,
            String appResourceKey,
            String surfaceKey,
            String resolvedCapabilityCode,
            String action,
            String riskTier,
            String contractOwner,
            UUID activeBundleId,
            long activeRevision,
            String ruleKey,
            long ruleVersion,
            String overrideMode,
            Integer maxDurationDays,
            String ruleOwner,
            String reasonCode,
            String planEligibilityState,
            List<String> allowedActions) {

        public Policy {
            allowedActions = List.copyOf(allowedActions);
        }
    }

    @Schema(name = "TenantCapabilityOverrideChange")
    public record Change(
            UUID overrideChangeId,
            String contractKey,
            String productKey,
            String appResourceKey,
            String policyRuleKey,
            long policyRuleVersion,
            UUID baseBundleId,
            long baseActiveRevision,
            String desiredState,
            String lifecycleState,
            Instant validTo,
            String justification,
            Long requestedBy,
            Instant submittedAt,
            Long approvedBy,
            Instant approvedAt,
            String decisionReason,
            Long activatedBy,
            Instant activatedAt,
            UUID activationReceiptId,
            Long revokedBy,
            Instant revokedAt,
            String revocationReason,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<String> allowedActions) {

        public Change {
            allowedActions = List.copyOf(allowedActions);
        }
    }

    public record Lineage(
            String level,
            String ownerKey,
            String state,
            String reason,
            UUID receiptId,
            Instant effectiveFrom,
            Instant effectiveTo) {
    }

    public record EffectiveCapability(
            Policy policy,
            String baselineState,
            String effectiveState,
            String effectiveSource,
            String overrideState,
            Change activeOverride,
            List<Lineage> lineage,
            Instant evaluatedAt) {

        public EffectiveCapability {
            lineage = List.copyOf(lineage);
        }
    }

    public record Projection(
            Instant observedAt,
            String coverageState,
            List<String> includedOwners,
            List<String> exclusions,
            List<EffectiveCapability> capabilities,
            List<Change> changes) {

        public Projection {
            includedOwners = List.copyOf(includedOwners);
            exclusions = List.copyOf(exclusions);
            capabilities = List.copyOf(capabilities);
            changes = List.copyOf(changes);
        }
    }
}
