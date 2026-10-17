package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.EnforcementState;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.RolloutMode;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.ScopeKind;

/** Closed aggregate-v2 contract used by every HRIS navigation consumer. */
public final class NavigationPolicyAggregateV2Dtos {

    public static final String SCHEMA_VERSION = "2";

    private NavigationPolicyAggregateV2Dtos() {
    }

    /**
     * Canonical order is not tenant configurable. A policy can only restrict canonical members.
     * This keeps tenant and legal-entity overlays incapable of inventing a route or reordering IA.
     */
    public record WorkbenchPolicy(
            String workbenchId,
            boolean enabled,
            List<String> enabledGroupIds,
            List<String> enabledNodeIds) {

        public WorkbenchPolicy {
            enabledGroupIds = enabledGroupIds == null ? List.of() : List.copyOf(enabledGroupIds);
            enabledNodeIds = enabledNodeIds == null ? List.of() : List.copyOf(enabledNodeIds);
        }
    }

    public record AggregatePolicyDocument(
            String schemaVersion,
            ScopeKind scopeKind,
            UUID legalEntityPublicId,
            RolloutMode rolloutMode,
            List<WorkbenchPolicy> workbenches) {

        public AggregatePolicyDocument {
            workbenches = workbenches == null ? List.of() : List.copyOf(workbenches);
        }
    }

    /** Authoring command for the complete, immutable seven-workbench aggregate. */
    public record AggregateDraftCommand(
            @NotNull ScopeKind scopeKind,
            UUID scopePublicId,
            UUID legalEntityPublicId,
            @NotNull RolloutMode rolloutMode,
            @NotNull @Size(min = 7, max = 7)
            List<@Valid WorkbenchPolicy> workbenches,
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Pattern(regexp = "^(sha256:)?[a-fA-F0-9]{64}$")
            String requestDigest) {

        public AggregateDraftCommand {
            workbenches = workbenches == null ? List.of() : List.copyOf(workbenches);
        }

        public AggregatePolicyDocument policy() {
            UUID legalEntity = scopeKind == ScopeKind.LEGAL_ENTITY
                    ? (legalEntityPublicId == null ? scopePublicId : legalEntityPublicId)
                    : null;
            return new AggregatePolicyDocument(
                    SCHEMA_VERSION, scopeKind, legalEntity, rolloutMode, workbenches);
        }
    }

    public record AggregateRevisionView(
            UUID policyId,
            UUID revisionId,
            long revisionNumber,
            NavigationPolicyDtos.LifecycleState lifecycleState,
            RolloutMode rolloutMode,
            AggregatePolicyDocument policy,
            String payloadDigest,
            long objectVersion,
            long authoredBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long approvedBy,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long restoredFromRevisionNumber,
            @JsonInclude(JsonInclude.Include.NON_NULL) String restoredFromDigest,
            String topologyRevision,
            String topologySha256,
            String iaRegisterSha256,
            String shellRegisterSha256,
            String taskGroupRegisterSha256,
            String generatedIaSha256,
            @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime publishedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    public record AggregateWorkspaceResponse(
            String schemaVersion,
            ScopeKind scopeKind,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID scopePublicId,
            @JsonInclude(JsonInclude.Include.NON_NULL) AggregateRevisionView draft,
            @JsonInclude(JsonInclude.Include.NON_NULL) AggregateRevisionView published,
            List<AggregateRevisionView> restorableRevisions) {

        public AggregateWorkspaceResponse {
            restorableRevisions = List.copyOf(restorableRevisions);
        }
    }

    public record PublishedAggregate(
            UUID policyId,
            UUID revisionId,
            long revisionNumber,
            AggregatePolicyDocument policy,
            String payloadDigest,
            String topologyRevision,
            String topologySha256,
            String iaRegisterSha256,
            String shellRegisterSha256,
            String taskGroupRegisterSha256,
            String generatedIaSha256,
            OffsetDateTime publishedAt) {
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

    public record EffectiveWorkbench(
            String workbenchId,
            int displayOrder,
            String landingNodeId,
            List<EffectiveGroup> groups) {

        public EffectiveWorkbench {
            groups = List.copyOf(groups);
        }
    }

    public record EffectiveNavigation(
            String schemaVersion,
            String topologyRevision,
            String topologySha256,
            String iaRegisterSha256,
            String shellRegisterSha256,
            String taskGroupRegisterSha256,
            String generatedIaSha256,
            EnforcementState enforcementState,
            boolean policyEnforced,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID tenantPolicyId,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID tenantRevisionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long tenantRevisionNumber,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID legalEntityRevisionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long legalEntityRevisionNumber,
            @JsonInclude(JsonInclude.Include.NON_NULL) String revisionPin,
            List<EffectiveWorkbench> workbenches,
            @JsonInclude(JsonInclude.Include.NON_NULL) String denialReason,
            OffsetDateTime evaluatedAt) {

        public EffectiveNavigation {
            workbenches = List.copyOf(workbenches);
        }
    }

    public record DeepLinkDecision(
            boolean allowed,
            String nodeId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String canonicalRoute,
            @JsonInclude(JsonInclude.Include.NON_NULL) String revisionPin,
            @JsonInclude(JsonInclude.Include.NON_NULL) String denialReason) {
    }
}
