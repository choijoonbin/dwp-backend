package com.dwp.services.time.timecard;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded owner-side projection of one verified Gateway scope to tenant workers. */
interface TimecardPopulationProjectionResolver {

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
            TimecardModels.requireDigest(membershipDigest, "membershipDigest");
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
            TimecardModels.requireDigest(populationDigest, "populationDigest");
            TimecardModels.requireDigest(grantDigest, "grantDigest");
            Objects.requireNonNull(validUntil, "validUntil");
            members = members == null ? List.of() : List.copyOf(members);
            if (members.isEmpty() || members.size() > 1_000) {
                throw new IllegalArgumentException("population membership must be bounded");
            }
        }
    }
}
