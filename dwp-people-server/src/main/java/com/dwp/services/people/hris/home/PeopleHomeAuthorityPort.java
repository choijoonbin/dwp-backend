package com.dwp.services.people.hris.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** People-owned current authority used after workload authentication. */
interface PeopleHomeAuthorityPort {

    Optional<CurrentAuthority> current(
            HomeMaterializationWorkloadProofV1.ProofClaims signedContext);

    List<CurrentAuthority> currentGrants();

    record CurrentAuthority(
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
            Set<UUID> populationPersonPublicIds,
            Map<String, HrisHomeContributionMaterializationV1.Decision> fieldDecisions) {

        private static final Set<String> FIELDS = Set.of(
                "employmentStatus", "organizationLabel", "pendingActionCount");

        public CurrentAuthority {
            if (tenantId < 1 || subjectPrincipalPublicId == null
                    || capability == null || browserAudience == null
                    || ownerPopulationScope == null || scopeRevision < 1
                    || populationScopeDigest == null
                    || !populationScopeDigest.matches("[0-9a-f]{64}")
                    || effectivePermissionRevision < 1 || fieldPolicyRevision < 1
                    || widgetKey == null
                    || !widgetKey.matches("people-[a-z0-9-]{1,33}")) {
                throw new IllegalArgumentException("Current People Home authority is invalid.");
            }
            populationPersonPublicIds = Set.copyOf(populationPersonPublicIds);
            fieldDecisions = Map.copyOf(fieldDecisions);
            if (populationPersonPublicIds.isEmpty()
                    || populationPersonPublicIds.size() > 1_000
                    || !fieldDecisions.keySet().equals(FIELDS)
                    || !ownerPopulationScope.matches(
                    browserAudience, HrisHomeContributionMaterializationV1.Module.HRM)) {
                throw new IllegalArgumentException("Current People Home scope is invalid.");
            }
            if (ownerPopulationScope
                    == HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF
                    && (!populationPersonPublicIds.equals(Set.of(subjectPrincipalPublicId))
                    || !"hcm.personal.profile.view".equals(capability))) {
                throw new IllegalArgumentException("SELF People Home authority is invalid.");
            }
            if (ownerPopulationScope
                    != HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF
                    && !"hcm.operations.people.view".equals(capability)) {
                throw new IllegalArgumentException("Operations People Home authority is invalid.");
            }
        }

        boolean exactlyMatches(HomeMaterializationWorkloadProofV1.ProofClaims claims) {
            return claims != null
                    && tenantId == claims.tenantId()
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
