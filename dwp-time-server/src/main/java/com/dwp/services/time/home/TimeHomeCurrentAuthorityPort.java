package com.dwp.services.time.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1.ProofClaims;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.BrowserAudience;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.OwnerPopulationScope;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owner-side port that rebinds a signed delegated subject to current Time authority.
 *
 * <p>The adapter is deliberately outside this slice: it must be backed by the current tenant
 * capability, permission, population and field-policy projections. Implementations must return an
 * empty result whenever any current evidence is missing or ambiguous.
 */
@FunctionalInterface
public interface TimeHomeCurrentAuthorityPort {

    Set<String> FIELD_KEYS = Set.of(
            "clockState", "leaveBalanceMinutes", "openTimecardCount",
            "pendingApprovalCount");

    Optional<CurrentAuthority> reevaluate(ProofClaims signedContext, Instant checkedAt);

    record CurrentAuthority(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String capability,
            OwnerPopulationScope ownerPopulationScope,
            long scopeRevision,
            String populationScopeDigest,
            long effectivePermissionRevision,
            long fieldPolicyRevision,
            String widgetKey,
            BrowserAudience browserAudience,
            List<UUID> workerPublicIds,
            Map<String, Decision> fieldDecisions) {

        private static final Set<String> CAPABILITIES = Set.of(
                "hcm.personal.time.view",
                "hcm.team.time.approve",
                "hcm.operations.time.update");

        public CurrentAuthority {
            if (tenantId < 1 || subjectPrincipalPublicId == null
                    || !CAPABILITIES.contains(capability)
                    || ownerPopulationScope == null || scopeRevision < 1
                    || populationScopeDigest == null
                    || !populationScopeDigest.matches("^[0-9a-f]{64}$")
                    || effectivePermissionRevision < 1 || fieldPolicyRevision < 1
                    || widgetKey == null
                    || !widgetKey.matches("^(time|leave)-[a-z0-9-]{1,31}$")
                    || browserAudience == null
                    || !ownerPopulationScope.matches(browserAudience,
                            com.dwp.platform.contracts.hris.home
                                    .HrisHomeContributionMaterializationV1.Module.TIM)) {
                throw new IllegalArgumentException("Current Time Home authority is invalid");
            }
            requireCapabilityScope(capability, ownerPopulationScope);
            workerPublicIds = List.copyOf(Objects.requireNonNull(
                    workerPublicIds, "workerPublicIds"));
            if (workerPublicIds.isEmpty() || workerPublicIds.size() > 1_000
                    || workerPublicIds.stream().anyMatch(Objects::isNull)
                    || new HashSet<>(workerPublicIds).size() != workerPublicIds.size()
                    || (ownerPopulationScope == OwnerPopulationScope.SELF
                            && workerPublicIds.size() != 1)) {
                throw new IllegalArgumentException(
                        "Current Time Home worker population is invalid");
            }
            fieldDecisions = Map.copyOf(Objects.requireNonNull(
                    fieldDecisions, "fieldDecisions"));
            if (!fieldDecisions.keySet().equals(FIELD_KEYS)
                    || fieldDecisions.values().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "Current Time Home field decisions are not closed");
            }
        }

        boolean exactlyMatches(ProofClaims claims) {
            return claims != null
                    && tenantId == claims.tenantId()
                    && subjectPrincipalPublicId.equals(claims.subjectPrincipalPublicId())
                    && capability.equals(claims.capability())
                    && ownerPopulationScope == claims.ownerPopulationScope()
                    && scopeRevision == claims.scopeRevision()
                    && populationScopeDigest.equals(claims.populationScopeDigest())
                    && effectivePermissionRevision == claims.effectivePermissionRevision()
                    && fieldPolicyRevision == claims.fieldPolicyRevision()
                    && widgetKey.equals(claims.widgetKey())
                    && browserAudience == claims.browserAudience();
        }

        Decision decision(String fieldKey) {
            return Objects.requireNonNull(fieldDecisions.get(fieldKey), fieldKey);
        }

        private static void requireCapabilityScope(
                String capability, OwnerPopulationScope scope) {
            boolean valid = switch (scope) {
                case SELF -> "hcm.personal.time.view".equals(capability);
                case TEAM -> "hcm.team.time.approve".equals(capability);
                case TIME_GROUP -> "hcm.operations.time.update".equals(capability);
                default -> false;
            };
            if (!valid) {
                throw new IllegalArgumentException(
                        "Time Home capability does not match its current population scope");
            }
        }
    }
}
