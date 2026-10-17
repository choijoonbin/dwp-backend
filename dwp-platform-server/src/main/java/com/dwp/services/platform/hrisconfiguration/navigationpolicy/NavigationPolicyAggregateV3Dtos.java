package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.EffectiveWorkbench;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.EnforcementState;

/** Tenant-SELF aggregate-v3 wire contract for the R6-C v47 authority carrier. */
public final class NavigationPolicyAggregateV3Dtos {

    public static final String SCHEMA_VERSION = "3";

    private NavigationPolicyAggregateV3Dtos() {
    }

    public record AuthorityScopeBinding(
            String kind,
            String key,
            UUID personPublicId) {
    }

    public record AdmittedPolicyRoute(
            String nodeId,
            String path,
            String routeContractKey,
            String profileKey,
            String targetBinding,
            AuthorityScopeBinding scopeBinding,
            String decision,
            String routeGrantRef,
            String decisionRevision,
            String authRevision,
            String policyRevision,
            String relationshipRevision,
            OffsetDateTime freshUntil) {
    }

    public record RuntimeOwnerFirstReadBinding(
            String method,
            String gatewayPath,
            String serviceKey,
            String servicePath) {
    }

    public record RuntimeOwnerOverlayRecord(
            String nodeId,
            String historicalOwner,
            String effectiveRuntimeOwner,
            String canonicalPath,
            @JsonInclude(JsonInclude.Include.NON_NULL) String compatibilityPath,
            String routeContractKey,
            RuntimeOwnerFirstReadBinding firstReadBinding,
            long registryVersion,
            String registryChecksum,
            String evidenceDigest) {
    }

    public record EffectiveNavigationV3(
            String schemaVersion,
            String topologyRevision,
            String topologySha256,
            String iaRegisterSha256,
            String shellRegisterSha256,
            String taskGroupRegisterSha256,
            String generatedIaSha256,
            long authorizationRegistryVersion,
            String authorizationRegistryChecksum,
            String runtimeOwnerOverlayRevision,
            String runtimeOwnerOverlayChecksum,
            List<RuntimeOwnerOverlayRecord> runtimeOwnerOverlayRecords,
            EnforcementState enforcementState,
            boolean policyEnforced,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID tenantPolicyId,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID tenantRevisionId,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long tenantRevisionNumber,
            @JsonInclude(JsonInclude.Include.NON_NULL) String revisionPin,
            String navigationContextScopeKey,
            @JsonInclude(JsonInclude.Include.NON_NULL) String authoritySelfScopeKey,
            @JsonInclude(JsonInclude.Include.NON_NULL) String authoritySnapshotRevision,
            @JsonInclude(JsonInclude.Include.NON_NULL) String authorityDecisionRevision,
            @JsonInclude(JsonInclude.Include.NON_NULL) String authorityAuthRevision,
            @JsonInclude(JsonInclude.Include.NON_NULL) String authorityPolicyRevision,
            @JsonInclude(JsonInclude.Include.NON_NULL) String authorityRelationshipRevision,
            @JsonInclude(JsonInclude.Include.NON_NULL) OffsetDateTime authorityFreshUntil,
            int authorityDecisionCount,
            List<AdmittedPolicyRoute> admittedPolicyRoutes,
            List<EffectiveWorkbench> workbenches,
            @JsonInclude(JsonInclude.Include.NON_NULL) String denialReason,
            OffsetDateTime evaluatedAt) {

        public EffectiveNavigationV3 {
            runtimeOwnerOverlayRecords = List.copyOf(runtimeOwnerOverlayRecords);
            admittedPolicyRoutes = List.copyOf(admittedPolicyRoutes);
            workbenches = List.copyOf(workbenches);
        }
    }

    public record DeepLinkDecisionV3(
            boolean allowed,
            String nodeId,
            String requestedPath,
            @JsonInclude(JsonInclude.Include.NON_NULL) String admittedTarget,
            @JsonInclude(JsonInclude.Include.NON_NULL) String iaCanonicalRoute,
            @JsonInclude(JsonInclude.Include.NON_NULL) String revisionPin,
            @JsonInclude(JsonInclude.Include.NON_NULL) String denialReason) {
    }
}
