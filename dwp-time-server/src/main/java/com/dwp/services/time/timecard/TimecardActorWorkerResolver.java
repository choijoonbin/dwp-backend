package com.dwp.services.time.timecard;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Resolves the current actor identity into the distinct People worker identity plane. */
interface TimecardActorWorkerResolver {

    Optional<ActorWorkerEvidence> resolve(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            Instant checkedAt);

    record ActorWorkerEvidence(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            UUID workerPublicId,
            Instant verifiedAt,
            Instant revalidateAt) {

        public ActorWorkerEvidence {
            if (tenantId <= 0 || actorId <= 0) {
                throw new IllegalArgumentException("actor worker evidence is invalid");
            }
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            Objects.requireNonNull(workerPublicId, "workerPublicId");
            Objects.requireNonNull(verifiedAt, "verifiedAt");
            Objects.requireNonNull(revalidateAt, "revalidateAt");
            if (!revalidateAt.isAfter(verifiedAt)) {
                throw new IllegalArgumentException("actor worker evidence is stale");
            }
        }
    }
}
