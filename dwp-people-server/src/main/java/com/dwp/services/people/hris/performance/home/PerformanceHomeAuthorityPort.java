package com.dwp.services.people.hris.performance.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Re-evaluates owner-side Performance authority after workload-proof verification. */
public interface PerformanceHomeAuthorityPort {

    String CAPABILITY = "hcm.operations.performance.view";
    Set<String> FIELD_KEYS = Set.of("goalProgress", "reviewStatus", "pendingActionCount");

    AuthorityDecision revalidate(HomeMaterializationWorkloadProofV1.ProofClaims signedContext);

    record AuthorityDecision(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String capability,
            HrisHomeContributionMaterializationV1.BrowserAudience browserAudience,
            HrisHomeContributionMaterializationV1.OwnerPopulationScope ownerPopulationScope,
            long scopeRevision,
            String populationScopeDigest,
            long effectivePermissionRevision,
            long fieldPolicyRevision,
            String widgetKey,
            Map<String, HrisHomeContributionMaterializationV1.Decision> fieldDecisions,
            Set<UUID> authorizedParticipantIds) {

        public AuthorityDecision {
            if (tenantId < 1 || subjectPrincipalPublicId == null
                    || !CAPABILITY.equals(capability) || browserAudience == null
                    || ownerPopulationScope == null || scopeRevision < 1
                    || populationScopeDigest == null
                    || !populationScopeDigest.matches("^[0-9a-f]{64}$")
                    || effectivePermissionRevision < 1 || fieldPolicyRevision < 1
                    || widgetKey == null
                    || !widgetKey.matches("^performance-[a-z0-9-]{1,28}$")) {
                throw new IllegalArgumentException("Current Performance Home authority is invalid.");
            }
            fieldDecisions = fieldDecisions == null ? Map.of() : Map.copyOf(fieldDecisions);
            if (!fieldDecisions.keySet().equals(FIELD_KEYS)
                    || fieldDecisions.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "Performance Home field policy must decide exactly the closed fields.");
            }
            authorizedParticipantIds = authorizedParticipantIds == null
                    ? Set.of() : Set.copyOf(authorizedParticipantIds);
            if (authorizedParticipantIds.isEmpty() || authorizedParticipantIds.size() > 500
                    || authorizedParticipantIds.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "Current Performance Home population must be non-empty and bounded.");
            }
            if (browserAudience == HrisHomeContributionMaterializationV1.BrowserAudience.SELF
                    && (ownerPopulationScope
                    != HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF
                    || !authorizedParticipantIds.equals(Set.of(subjectPrincipalPublicId)))) {
                throw new IllegalArgumentException(
                        "SELF authority must bind only the delegated subject.");
            }
            if (browserAudience == HrisHomeContributionMaterializationV1.BrowserAudience.TEAM
                    && ownerPopulationScope
                    != HrisHomeContributionMaterializationV1.OwnerPopulationScope.TEAM) {
                throw new IllegalArgumentException("TEAM authority requires TEAM scope.");
            }
            if ((browserAudience
                    == HrisHomeContributionMaterializationV1.BrowserAudience.OPERATIONS
                    || browserAudience
                    == HrisHomeContributionMaterializationV1.BrowserAudience.EXECUTIVE)
                    && ownerPopulationScope
                    != HrisHomeContributionMaterializationV1.OwnerPopulationScope
                    .NAMED_POPULATION) {
                throw new IllegalArgumentException(
                        "Performance operations authority requires NAMED_POPULATION scope.");
            }
        }

        boolean exactlyMatches(HomeMaterializationWorkloadProofV1.ProofClaims claims) {
            return claims != null
                    && claims.tenantId() == tenantId
                    && subjectPrincipalPublicId.equals(claims.subjectPrincipalPublicId())
                    && capability.equals(claims.capability())
                    && browserAudience == claims.browserAudience()
                    && ownerPopulationScope == claims.ownerPopulationScope()
                    && scopeRevision == claims.scopeRevision()
                    && populationScopeDigest.equals(claims.populationScopeDigest())
                    && effectivePermissionRevision == claims.effectivePermissionRevision()
                    && fieldPolicyRevision == claims.fieldPolicyRevision()
                    && widgetKey.equals(claims.widgetKey());
        }
    }
}
