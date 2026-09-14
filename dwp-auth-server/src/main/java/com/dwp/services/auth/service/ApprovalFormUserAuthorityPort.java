package com.dwp.services.auth.service;

import com.dwp.services.auth.service.ApprovalFormUserSourceProofVerifier.VerifiedSourceProof;

/** Auth must independently re-evaluate the exact current source and owner-reference authority. */
@FunctionalInterface
public interface ApprovalFormUserAuthorityPort {
    CurrentAuthority requireCurrent(VerifiedSourceProof proof);

    record CurrentAuthority(String authRevision, String policyRevision) { }
}
