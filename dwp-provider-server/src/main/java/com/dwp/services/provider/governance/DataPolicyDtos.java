package com.dwp.services.provider.governance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DataPolicyDtos {

    private DataPolicyDtos() {
    }

    public record Policy(
            UUID policyId,
            String policyKey,
            String displayName,
            String description,
            String policyType,
            String scopeType,
            String scopeRef,
            String ownerService,
            String lifecycleState,
            long version,
            List<Revision> revisions) {
    }

    public record Revision(
            UUID revisionId,
            int revisionNumber,
            String lifecycleState,
            JsonNode policyRule,
            Instant effectiveFrom,
            Instant effectiveTo,
            String justification,
            UUID previousRevisionId,
            UUID rollbackOfRevisionId,
            ImpactPreview impact,
            Long requestedBy,
            Long approvedBy,
            Instant submittedAt,
            Instant approvedAt,
            Instant publishedAt,
            long version,
            Approval approval) {
    }

    public record ImpactPreview(
            Instant catalogGeneratedAt,
            int affectedAssetCount,
            List<String> affectedAssetKeys,
            List<String> blockers,
            List<String> warnings,
            List<String> controls,
            String impactHash,
            Instant previewedAt,
            boolean publishable) {
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

    public record CreatePolicyRequest(
            @NotBlank @Size(max = 160)
            @Pattern(regexp = "^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*){1,7}$")
            String policyKey,
            @NotBlank @Size(max = 240) String displayName,
            @NotBlank @Size(max = 1200) String description,
            @NotBlank @Pattern(regexp = "CLASSIFICATION|MINIMIZATION|RESIDENCY|RETENTION|DELETION|LEGAL_HOLD|RESTRICTED_FIELD|TENANT_RLS")
            String policyType,
            @NotBlank @Pattern(regexp = "GLOBAL|DATABASE|ASSET") String scopeType,
            @Size(max = 320) String scopeRef,
            @NotBlank @Size(max = 120) String ownerService,
            @NotNull JsonNode policyRule,
            @NotBlank @Size(max = 1200) String justification,
            Instant effectiveFrom,
            Instant effectiveTo) {
    }

    public record CreateRevisionRequest(
            @NotNull JsonNode policyRule,
            @NotBlank @Size(max = 1200) String justification,
            Instant effectiveFrom,
            Instant effectiveTo) {
    }

    public record VersionedReasonRequest(
            @Min(0) long version,
            @NotBlank @Size(max = 1200) String reason) {
    }

    public record ApprovalDecisionRequest(
            @Min(0) long version,
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @NotBlank @Size(max = 1200) String reason) {
    }
}
