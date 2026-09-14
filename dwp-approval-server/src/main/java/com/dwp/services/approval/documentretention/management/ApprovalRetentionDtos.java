package com.dwp.services.approval.documentretention.management;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ApprovalRetentionDtos {
    private ApprovalRetentionDtos() {}

    @Schema(name="ApprovalRetentionPublicRules", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record PublicRules(@NotNull Boolean allowPurge,
            @NotNull @Size(min=1,max=3) List<@Pattern(regexp="INTERNAL|CONFIDENTIAL|RESTRICTED") String> allowedClassifications,
            @NotNull @Min(1) @Max(3650) Integer recordRetentionDays,
            @NotNull @Min(1) @Max(3650) Integer deletedDraftRecoveryDays,
            @NotNull @Min(1) @Max(3650) Integer receiptRetentionDays,
            @NotNull @Min(1) @Max(3650) Integer holdEvidenceRetentionDays,
            @NotNull @Min(1) @Max(3650) Integer auditEvidenceRetentionDays,
            @NotNull @Min(1) @Max(50000) Integer maxInventoryRows,
            @NotNull @Min(1) @Max(1000) Integer maxObjectsPerRecord) {
        public PublicRules {
            if (allowedClassifications != null) allowedClassifications=List.copyOf(allowedClassifications);
        }
        @JsonAnySetter public void unknown(String key, JsonNode value) { rejectUnknown(key); }
    }

    @Schema(name="ApprovalRetentionInitializePolicy", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record InitializePolicy(@NotNull @AssertTrue Boolean expectedAbsent,
            @NotBlank @Size(max=128) @Pattern(regexp="[A-Za-z0-9._:-]+") String idempotencyKey) {
        @JsonAnySetter public void unknown(String key, JsonNode value) { rejectUnknown(key); }
    }
    @Schema(name="ApprovalRetentionSavePolicy", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record SavePolicy(@NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(max=128) @Pattern(regexp="[A-Za-z0-9._:-]+") String idempotencyKey,
            @NotNull @Valid PublicRules rules) {
        @JsonAnySetter public void unknown(String key, JsonNode value) { rejectUnknown(key); }
    }
    @Schema(name="ApprovalRetentionPublishPolicy", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record PublishPolicy(@NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(max=128) @Pattern(regexp="[A-Za-z0-9._:-]+") String idempotencyKey,
            @NotBlank @Size(min=10,max=1000) String reviewComment) {
        @JsonAnySetter public void unknown(String key, JsonNode value) { rejectUnknown(key); }
    }
    @Schema(name="ApprovalRetentionCreateClaim", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record CreateClaim(@NotNull @Min(0) Long expectedVersion,
            @NotNull UUID policyId, @NotNull @Min(0) Long expectedPolicyVersion,
            @NotNull @Min(0) Long expectedHoldVersion,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String inventorySha256,
            @NotBlank @Size(max=128) @Pattern(regexp="[A-Za-z0-9._:-]+") String idempotencyKey) {
        @JsonAnySetter public void unknown(String key, JsonNode value) { rejectUnknown(key); }
    }

    @Schema(name="ApprovalRetentionPolicy", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Policy(UUID policyId, String resourceSetKey, long version, int publishedRevision,
            Integer pendingRevision, Long pendingMakerUserId, String publishedRulesSha256,
            String pendingRulesSha256, PublicRules published, PublicRules pending,
            boolean publishEligible, String publishReason, String runtimeReadiness) {}
    @Schema(name="ApprovalRetentionRecord", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Record(UUID requestId, String resourceSetKey, long version, UUID policyId,
            long policyVersion, long holdVersion, String state, boolean claimEligible,
            String claimReason, String inventorySha256, int inventoryRows, int inventoryTables,
            int objectCount, OffsetDateTime eligibleAfter, UUID claimId, String runtimeReadiness) {}
    @Schema(name="ApprovalRetentionClaim", additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Claim(UUID claimId, UUID requestId, String resourceSetKey, long version,
            String state, String reason, String inventorySha256, UUID executionClaimId,
            int foreignRequests, int verifiedAcknowledgements, String foreignCopyState,
            String runtimeReadiness) {}

    private static void rejectUnknown(String key) {
        throw new IllegalArgumentException("Unknown retention contract field: "+key);
    }
}
