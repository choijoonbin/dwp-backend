package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-side projection boundary for the centrally governed TIME_TARGET_POPULATION scope.
 *
 * <p>The Gateway scope key is deliberately actor-bound and is only an entitlement lookup key. It
 * must never be persisted as the business scope of a work plan. This resolver maps that opaque key
 * to one stable population identity and independently proves that the selected People assignment
 * is a current member of that population.</p>
 */
public interface WorkRegimeTargetPopulationResolver {

    Optional<PopulationAccess> resolveActorAccess(
            long tenantId, long actorId, String gatewayScopeKey, Instant checkedAt);

    Optional<TargetMembershipEvidence> resolveTargetMembership(
            long tenantId,
            UUID populationPublicId,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            EffectivePeriod requiredPeriod,
            Instant checkedAt);

    record PopulationAccess(
            long tenantId,
            long actorId,
            String gatewayScopeKey,
            UUID populationPublicId,
            String scopePublicRef,
            long populationRevision,
            long grantRevision,
            String populationDigest,
            String grantDigest,
            Instant validUntil) {
        public PopulationAccess {
            requireTenant(tenantId);
            if (actorId <= 0) throw new IllegalArgumentException("actorId must be positive");
            gatewayScopeKey = WorkRegimeModels.requireBoundedText(
                    gatewayScopeKey, "gatewayScopeKey", 38);
            if (!gatewayScopeKey.matches("scope-[0-9a-f]{32}")) {
                throw new IllegalArgumentException("gatewayScopeKey is not canonical");
            }
            Objects.requireNonNull(populationPublicId, "populationPublicId must not be null");
            scopePublicRef = WorkRegimeModels.requireBoundedText(
                    scopePublicRef, "scopePublicRef", 128);
            if (!scopePublicRef.equals(stableScopeRef(populationPublicId))) {
                throw new IllegalArgumentException("scopePublicRef is not the stable population ref");
            }
            requirePositive(populationRevision, "populationRevision");
            requirePositive(grantRevision, "grantRevision");
            populationDigest = WorkRegimeModels.requireDigest(
                    populationDigest, "populationDigest");
            grantDigest = WorkRegimeModels.requireDigest(grantDigest, "grantDigest");
            Objects.requireNonNull(validUntil, "validUntil must not be null");
        }
    }

    record TargetMembershipEvidence(
            long tenantId,
            UUID populationPublicId,
            long populationRevision,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            long membershipRevision,
            EffectivePeriod effectivePeriod,
            String populationDigest,
            String membershipDigest) {
        public TargetMembershipEvidence {
            requireTenant(tenantId);
            Objects.requireNonNull(populationPublicId, "populationPublicId must not be null");
            requirePositive(populationRevision, "populationRevision");
            Objects.requireNonNull(workerPublicId, "workerPublicId must not be null");
            Objects.requireNonNull(
                    peopleAssignmentPublicId, "peopleAssignmentPublicId must not be null");
            if (peopleAssignmentRevision < 0) {
                throw new IllegalArgumentException("peopleAssignmentRevision must not be negative");
            }
            requirePositive(membershipRevision, "membershipRevision");
            Objects.requireNonNull(effectivePeriod, "effectivePeriod must not be null");
            populationDigest = WorkRegimeModels.requireDigest(
                    populationDigest, "populationDigest");
            membershipDigest = WorkRegimeModels.requireDigest(
                    membershipDigest, "membershipDigest");
        }
    }

    static String stableScopeRef(UUID populationPublicId) {
        return "population:" + Objects.requireNonNull(
                populationPublicId, "populationPublicId must not be null");
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
    }

    private static void requirePositive(long value, String label) {
        if (value <= 0) throw new IllegalArgumentException(label + " must be positive");
    }
}
