package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class NavigationPolicyDtos {

    private NavigationPolicyDtos() {
    }

    public enum ScopeKind { TENANT, LEGAL_ENTITY }

    public enum LifecycleState { DRAFT, VALIDATED, PENDING_APPROVAL, APPROVED, PUBLISHED }

    public enum RolloutMode { OFF, SHADOW, ENFORCED }

    public enum EnforcementState {
        NOT_ENROLLED, OFF, SHADOW, ENFORCED, DENIED_MISSING_POLICY, DENIED_STALE_CONTEXT
    }

    public record NodePolicy(
            @NotBlank @Size(max = 160) String nodeId,
            @NotNull @Min(0) Integer displayOrder,
            boolean enabled) {
    }

    public record GroupPolicy(
            @NotBlank @Pattern(regexp = "^HROPS-0[1-7]$") String groupId,
            @NotNull @Min(0) Integer displayOrder,
            boolean enabled,
            @NotBlank @Size(max = 160) String defaultNodeId,
            @NotEmpty @Size(max = 17) List<@Valid NodePolicy> nodes) {

        public GroupPolicy {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }
    }

    public record PolicyDocument(
            @NotNull ScopeKind scopeKind,
            UUID legalEntityPublicId,
            @NotNull RolloutMode rolloutMode,
            @NotBlank @Pattern(regexp = "^HRIS-WB-HR-OPERATIONS$") String workbenchId,
            @NotEmpty @Size(max = 7) List<@Valid GroupPolicy> groups) {

        public PolicyDocument {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }

    public record DraftCommand(
            @NotNull ScopeKind scopeKind,
            UUID scopePublicId,
            UUID legalEntityPublicId,
            @NotNull RolloutMode rolloutMode,
            @NotBlank @Pattern(regexp = "^HRIS-WB-HR-OPERATIONS$") String workbenchId,
            @NotEmpty @Size(max = 7) List<@Valid GroupPolicy> groups,
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Pattern(regexp = "^(sha256:)?[a-fA-F0-9]{64}$") String requestDigest) {

        public DraftCommand {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }

        public PolicyDocument policy() {
            UUID legalEntity = scopeKind == ScopeKind.LEGAL_ENTITY
                    ? (legalEntityPublicId == null ? scopePublicId : legalEntityPublicId)
                    : null;
            return new PolicyDocument(scopeKind, legalEntity, rolloutMode, workbenchId, groups);
        }
    }

    public record LifecycleCommand(
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Pattern(regexp = "^(sha256:)?[a-fA-F0-9]{64}$") String requestDigest,
            @Size(max = 500) String reason) {

        public LifecycleCommand(Long expectedVersion, String requestDigest) {
            this(expectedVersion, requestDigest, null);
        }
    }

    public record RestoreCommand(
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Pattern(regexp = "^(sha256:)?[a-fA-F0-9]{64}$") String requestDigest,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record RevisionView(
            UUID policyId,
            UUID revisionId,
            long revisionNumber,
            LifecycleState lifecycleState,
            RolloutMode rolloutMode,
            PolicyDocument policy,
            String payloadDigest,
            long objectVersion,
            long authoredBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long approvedBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long restoredFromRevisionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String restoredFromDigest,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record CommandReceipt(
            UUID receiptId,
            String commandType,
            UUID policyId,
            UUID revisionId,
            long revisionNumber,
            LifecycleState lifecycleState,
            long objectVersion,
            String requestDigest,
            UUID correlationId,
            OffsetDateTime createdAt) {
    }

    public record WorkspaceResponse(
            String workbenchId,
            ScopeKind scopeKind,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID scopePublicId,
            @JsonInclude(JsonInclude.Include.NON_NULL) RevisionView draft,
            @JsonInclude(JsonInclude.Include.NON_NULL) RevisionView published,
            List<RevisionView> restorableRevisions) {

        public WorkspaceResponse {
            restorableRevisions = List.copyOf(restorableRevisions);
        }
    }

    public record EffectiveNode(String nodeId, int displayOrder) {
    }

    public record EffectiveGroup(
            String groupId,
            int displayOrder,
            String defaultNodeId,
            List<EffectiveNode> nodes) {

        public EffectiveGroup {
            nodes = List.copyOf(nodes);
        }
    }

    public record EffectiveProjection(
            String workbenchId,
            EnforcementState enforcementState,
            boolean policyEnforced,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID tenantPolicyId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long tenantRevisionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long legalEntityRevisionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String revisionPin,
            List<EffectiveGroup> groups,
            @JsonInclude(JsonInclude.Include.NON_NULL) String denialReason,
            OffsetDateTime evaluatedAt) {

        public EffectiveProjection {
            groups = List.copyOf(groups);
        }
    }
}
