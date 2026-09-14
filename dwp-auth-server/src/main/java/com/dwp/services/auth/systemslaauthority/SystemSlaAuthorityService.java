package com.dwp.services.auth.systemslaauthority;

import java.util.function.Supplier;

public final class SystemSlaAuthorityService {
    private final Supplier<SystemSlaProofVerifier> verifier;
    private final SystemSlaAuthorityPort authority;
    private final SystemSlaReplayStore replay;
    private final SystemSlaAttestationIssuer issuer;
    private final boolean enabled;
    public SystemSlaAuthorityService(Supplier<SystemSlaProofVerifier> verifier, SystemSlaAuthorityPort authority,
            SystemSlaReplayStore replay, SystemSlaAttestationIssuer issuer, boolean enabled) {
        this.verifier = verifier; this.authority = authority; this.replay = replay; this.issuer = issuer; this.enabled = enabled;
    }
    public SystemSlaProofVerifier.Verified preverify(byte[] body, String transport) {
        if (!enabled) throw SystemSlaJson.unavailable();
        try { return verifier.get().verify(body, transport); }
        catch (com.dwp.core.exception.BaseException error) { throw error; }
        catch (RuntimeException error) { throw SystemSlaJson.unavailable(); }
    }
    public String evaluate(SystemSlaProofVerifier.Verified proof) {
        if (!enabled || proof == null) throw SystemSlaJson.unavailable(); replay.ready();
        var before = authority.current(proof); var after = authority.current(proof);
        if (!before.sameVector(after)) throw SystemSlaJson.changed();
        replay.consume(proof, after.expiresAt()); var last = authority.current(proof);
        if (!after.sameVector(last)) throw SystemSlaJson.changed();
        return issuer.issue(proof, last);
    }
}
