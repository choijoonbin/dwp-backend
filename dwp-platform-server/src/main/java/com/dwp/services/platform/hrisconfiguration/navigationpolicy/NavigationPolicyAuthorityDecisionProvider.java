package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Trusted Auth boundary for the R6-B exact POLICY route carrier.
 *
 * <p>The request deliberately has no candidate route, node or path fields. Auth owns the closed
 * v46 candidate set and returns all six decisions from one atomic snapshot.
 */
public interface NavigationPolicyAuthorityDecisionProvider {

    AuthoritySnapshot evaluate(AuthorityRequest request);

    enum AccessMode {
        NORMAL,
        ELEVATED
    }

    record AuthorityRequest(
            long tenantId,
            long actorId,
            AccessMode activeAccessMode,
            UUID expectedPersonPublicId,
            String expectedScopeKey,
            long expectedRegistryVersion,
            String expectedRegistryChecksum,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            String expectedDecisionRevision) {
    }

    record AuthoritySnapshot(
            long registryVersion,
            String registryChecksum,
            String snapshotRevision,
            String decisionRevision,
            String authRevision,
            String policyRevision,
            String relationshipRevision,
            OffsetDateTime freshUntil,
            List<RouteDecision> decisions) {

        public AuthoritySnapshot {
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }
    }

    record RouteDecision(
            String nodeId,
            String path,
            String routeContractKey,
            String profileKey,
            String targetBinding,
            ScopeBinding scopeBinding,
            String decision,
            String reasonCode,
            String routeGrantRef,
            String decisionRevision,
            String authRevision,
            String policyRevision,
            String relationshipRevision,
            OffsetDateTime freshUntil) {
    }

    record ScopeBinding(
            String kind,
            String key,
            UUID personPublicId) {
    }
}
