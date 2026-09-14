package com.dwp.services.auth.approvalpolicyimpact;

import java.time.Instant;
import java.util.List;

public interface PolicyImpactAuthorityPort {
    /** Registry-only readiness check, before replay admission or identity/source reads. */
    void requireRegistered();
    Current requireCurrent(PolicyImpactProofVerifier.Verified proof);
    record Grant(String capabilityContractKey, String resolvedCapabilityCode, String resourceSetKey,
            String contextScopeKey, Instant expiresAt) { }
    record Current(String ownerAuthRevision, String ownerPolicyRevision, String sourceRevision,
            String sourceVectorSha256, Instant evaluatedAt, Instant expiresAt, List<Grant> grants) {
        public Current { grants = List.copyOf(grants); }
        public boolean sameVector(Current other) {
            return other != null && ownerAuthRevision.equals(other.ownerAuthRevision)
                    && ownerPolicyRevision.equals(other.ownerPolicyRevision) && sourceRevision.equals(other.sourceRevision)
                    && sourceVectorSha256.equals(other.sourceVectorSha256) && expiresAt.equals(other.expiresAt()) && grants.equals(other.grants());
        }
    }
}
