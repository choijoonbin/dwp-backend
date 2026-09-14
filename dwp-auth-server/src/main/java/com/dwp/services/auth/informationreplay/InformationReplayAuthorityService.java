package com.dwp.services.auth.informationreplay;

import static com.dwp.services.auth.informationreplay.InformationReplayJson.*;
import static com.dwp.services.auth.informationreplay.InformationReplayProofVerifier.earlier;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.time.Instant;

/** Only fresh authority issues a receipt attestation; this service performs no directory or business mutations. */
public final class InformationReplayAuthorityService {
    private final boolean enabled;
    private final InformationReplayProofVerifier verifier;
    private final InformationReplayAuthorityPort authority;
    private final InformationReplayReplayStore replay;
    private final InformationReplayAttestationIssuer issuer;
    private final InformationReplayJson json;
    public InformationReplayAuthorityService(boolean enabled, InformationReplayProofVerifier verifier, InformationReplayAuthorityPort authority,
            InformationReplayReplayStore replay, InformationReplayAttestationIssuer issuer, InformationReplayJson json) {
        this.enabled = enabled; this.verifier = verifier; this.authority = authority; this.replay = replay; this.issuer = issuer; this.json = json;
    }
    public String evaluate(byte[] body, String token) {
        if (!enabled) throw unavailable();
        var proof = verifier.verify(body, token); issuer.requireReady(); replay.requireReady();
        var before = authority.requireCurrent(proof); var after = authority.requireCurrent(proof); unchanged(before, after);
        Instant deadline = earlier(earlier(proof.expiresAt(), before.expiresAt()), after.expiresAt());
        replay.consume(proof, deadline);
        var finalCurrent = authority.requireCurrent(proof); unchanged(after, finalCurrent);
        deadline = earlier(deadline, finalCurrent.expiresAt());
        return issuer.issue(proof, finalCurrent, deadline);
    }
    private void unchanged(InformationReplayAuthorityPort.Current before, InformationReplayAuthorityPort.Current after) {
        if (before == null || after == null || !before.ownerAuthRevision().equals(after.ownerAuthRevision())
                || !before.ownerPolicyRevision().equals(after.ownerPolicyRevision()) || !json.canonical(before.vector()).equals(json.canonical(after.vector()))
                || !json.canonical(before.result()).equals(json.canonical(after.result()))) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT, "Information replay authority changed during evaluation.");
        }
    }
}
