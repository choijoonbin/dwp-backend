package com.dwp.services.auth.approvalsignatures;

import static com.dwp.services.auth.approvalsignatures.SignatureAuthorityJson.*;
import java.util.function.Supplier;

public final class SignatureAuthorityService {
    private final boolean enabled;
    private final Supplier<SignatureAuthorityProofVerifier> verifier;
    private final SignatureCurrentAuthority authority;
    private final Supplier<SignatureAuthorityIssuer> issuer;
    public SignatureAuthorityService(boolean enabled, Supplier<SignatureAuthorityProofVerifier> verifier,
            SignatureCurrentAuthority authority, Supplier<SignatureAuthorityIssuer> issuer) {
        this.enabled = enabled; this.verifier = verifier; this.authority = authority; this.issuer = issuer;
    }
    public SignatureAuthorityProofVerifier.Verified preverify(byte[] body, String token) {
        if (!enabled) throw unavailable(); return verifier.get().verify(body, token);
    }
    public String evaluate(SignatureAuthorityProofVerifier.Verified proof) {
        if (!enabled || proof == null) throw unavailable();
        authority.requireRegistered(proof.binding());
        var before = authority.requireCurrent(proof);
        var after = authority.requireCurrent(proof);
        if (!before.equals(after)) throw changed();
        String token = issuer.get().issue(proof, after);
        if (!after.equals(authority.requireCurrent(proof))) throw changed();
        return token;
    }
}
