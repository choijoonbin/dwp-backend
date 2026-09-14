package com.dwp.services.auth.approvalsignatures;

import java.time.Instant;

public interface SignatureCurrentAuthority {
    /** Must run before principal/directory reads; uninstalled routes cannot obtain an authority window. */
    void requireRegistered(SignatureAuthorityBindings binding);
    Observation requireCurrent(SignatureAuthorityProofVerifier.Verified proof);
    record Observation(String authRevision, String policyRevision, String registrySha256, String vectorSha256,
            Instant expiresAt, boolean highRiskVerified) { }
}
