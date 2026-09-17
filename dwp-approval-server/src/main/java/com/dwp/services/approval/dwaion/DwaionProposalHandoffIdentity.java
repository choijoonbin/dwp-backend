package com.dwp.services.approval.dwaion;

/** Trusted request-bound identity material that must never be accepted from the JSON body. */
public record DwaionProposalHandoffIdentity(String authSessionId) {
    public DwaionProposalHandoffIdentity {
        if (authSessionId == null || authSessionId.isBlank()
                || !authSessionId.equals(authSessionId.strip())
                || authSessionId.length() > 160
                || authSessionId.indexOf(',') >= 0
                || authSessionId.codePoints().anyMatch(value -> value < 32 || value == 127)) {
            throw new IllegalArgumentException("A verified authentication session is required for DWAI-ON handoff ownership.");
        }
    }
}
