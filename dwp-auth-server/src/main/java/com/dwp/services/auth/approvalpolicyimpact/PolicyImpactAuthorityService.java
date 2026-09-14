package com.dwp.services.auth.approvalpolicyimpact;

import java.util.function.Supplier;

public final class PolicyImpactAuthorityService {
    private final Supplier<PolicyImpactProofVerifier> verifier;
    private final PolicyImpactAuthorityPort authority;
    private final PolicyImpactReplayStore replay;
    private final PolicyImpactAuthorityIssuer issuer;
    private final boolean enabled;
    public PolicyImpactAuthorityService(Supplier<PolicyImpactProofVerifier> verifier, PolicyImpactAuthorityPort authority,
            PolicyImpactReplayStore replay, PolicyImpactAuthorityIssuer issuer, boolean enabled) {
        this.verifier = verifier; this.authority = authority; this.replay = replay; this.issuer = issuer; this.enabled = enabled;
    }
    public PolicyImpactProofVerifier.Verified preverify(byte[] body, String transport) {
        if (!enabled) throw PolicyImpactJson.unavailable();
        try { return verifier.get().verify(body, transport); }
        catch (com.dwp.core.exception.BaseException error) { throw error; }
        catch (RuntimeException error) { throw PolicyImpactJson.unavailable(); }
    }
    public String evaluate(PolicyImpactProofVerifier.Verified proof) {
        if (!enabled || proof == null) throw PolicyImpactJson.unavailable();
        authority.requireRegistered();
        replay.requireReady();
        var before = authority.requireCurrent(proof); var after = authority.requireCurrent(proof);
        if (!before.sameVector(after)) throw new com.dwp.core.exception.BaseException(
                com.dwp.core.common.ErrorCode.DECISION_REVISION_CONFLICT, "Policy impact authority changed during evaluation.");
        replay.consume(proof, after.expiresAt());
        var finalCurrent = authority.requireCurrent(proof);
        if (!after.sameVector(finalCurrent)) throw new com.dwp.core.exception.BaseException(
                com.dwp.core.common.ErrorCode.DECISION_REVISION_CONFLICT, "Policy impact authority changed before attestation.");
        return issuer.issue(proof, finalCurrent);
    }
}
