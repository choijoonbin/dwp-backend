package com.dwp.services.time.interpretation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded owner-side projection of one verified Gateway scope to TIM workers. */
interface InterpretationPopulationProjectionResolver {

    Optional<PopulationProjection> resolve(
            long tenantId, long actorId, String gatewayScopeKey, Instant checkedAt);

    record Member(
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            long membershipRevision,
            String membershipDigest) {
        public Member {
            Objects.requireNonNull(workerPublicId, "workerPublicId");
            Objects.requireNonNull(peopleAssignmentPublicId, "peopleAssignmentPublicId");
            if (peopleAssignmentRevision < 0 || membershipRevision <= 0) {
                throw new IllegalArgumentException("population member revisions are invalid");
            }
            requireDigest(membershipDigest, "membershipDigest");
        }
    }

    record PopulationProjection(
            long tenantId,
            long actorId,
            String gatewayScopeKey,
            UUID populationPublicId,
            long populationRevision,
            long grantRevision,
            String populationDigest,
            String grantDigest,
            Instant validUntil,
            List<Member> members) {
        public PopulationProjection {
            if (tenantId <= 0 || actorId <= 0 || gatewayScopeKey == null
                    || gatewayScopeKey.isBlank() || populationRevision <= 0
                    || grantRevision <= 0) {
                throw new IllegalArgumentException("population projection is invalid");
            }
            Objects.requireNonNull(populationPublicId, "populationPublicId");
            requireDigest(populationDigest, "populationDigest");
            requireDigest(grantDigest, "grantDigest");
            Objects.requireNonNull(validUntil, "validUntil");
            members = members == null ? List.of() : List.copyOf(members);
            if (members.isEmpty() || members.size() > 1_000) {
                throw new IllegalArgumentException("population membership must be bounded");
            }
        }
    }

    private static void requireDigest(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }
}
