package com.dwp.services.time.clockingestion;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owner-side, bounded projection of one Gateway scope to current TIM population facts. */
interface ClockOwnerPolicyProjectionResolver {

    Optional<PopulationProjection> resolve(
            long tenantId, long actorId, String gatewayScopeKey, Instant checkedAt);

    record Member(
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            long membershipRevision,
            String membershipDigest) {
        public Member {
            Objects.requireNonNull(workerPublicId, "workerPublicId must not be null");
            Objects.requireNonNull(
                    peopleAssignmentPublicId, "peopleAssignmentPublicId must not be null");
            if (peopleAssignmentRevision < 0 || membershipRevision <= 0) {
                throw new IllegalArgumentException("member revisions are invalid");
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
            if (tenantId <= 0 || actorId <= 0
                    || gatewayScopeKey == null || gatewayScopeKey.isBlank()
                    || populationRevision <= 0 || grantRevision <= 0) {
                throw new IllegalArgumentException("population projection is invalid");
            }
            Objects.requireNonNull(populationPublicId, "populationPublicId must not be null");
            requireDigest(populationDigest, "populationDigest");
            requireDigest(grantDigest, "grantDigest");
            Objects.requireNonNull(validUntil, "validUntil must not be null");
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
