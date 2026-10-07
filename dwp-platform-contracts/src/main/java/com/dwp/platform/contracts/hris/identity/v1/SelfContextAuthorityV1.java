package com.dwp.platform.contracts.hris.identity.v1;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Untrusted adapter result. Neither this record nor its version fields constitute a signed proof. */
public record SelfContextAuthorityV1(long tenantId, long userId, UUID principalPublicId,
                                     UUID personPublicId, long expectedUserRowVersion,
                                     long expectedAccessRevision, SelfContextPurposeV1.Audience audience,
                                     SelfContextPurposeV1 purpose, boolean appEntitled,
                                     boolean separationOfDutiesAllowed, long permissionDecisionRevision,
                                     Instant issuedAt, Instant expiresAt, EffectivePolicy effectivePolicy) {
    /** Purpose policy is supplied by the current owner/PEP, not inferred from enum or worker name. */
    public record EffectivePolicy(String reference, long version, Set<NativeSelfContextSetV1.PersonState> allowedPersonStates,
                                  Set<String> allowedWorkerStatuses,
                                  Set<String> allowedAssignmentStatuses) {
        public EffectivePolicy {
            allowedPersonStates = allowedPersonStates == null ? null : Set.copyOf(allowedPersonStates);
            allowedWorkerStatuses = allowedWorkerStatuses == null ? null : Set.copyOf(allowedWorkerStatuses);
            allowedAssignmentStatuses = allowedAssignmentStatuses == null ? null : Set.copyOf(allowedAssignmentStatuses);
        }
    }
}
