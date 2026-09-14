package com.dwp.services.approval.informationreplay;

import java.util.Set;

final class InformationReplayProtocol {
    static final String PATH="/internal/approval-workflow/information-command-replay";
    static final String HEADER="X-DWP-Approval-Information-Replay-Token";
    static final String OPERATION="RECEIPT_REPLAY";
    static final String OWNER_ISSUER="dwp-approval-server:information-command-replay:v1";
    static final String OWNER_AUDIENCE="dwp-auth-server:information-command-replay:v1";
    static final String OWNER_PURPOSE="APPROVAL_INFORMATION_COMMAND_REPLAY_V1";
    static final String TRANSPORT_ISSUER="dwp-approval-server:information-replay-transport:v1";
    static final String TRANSPORT_AUDIENCE="dwp-auth-server:information-replay-transport:v1";
    static final String TRANSPORT_PURPOSE="APPROVAL_INFORMATION_REPLAY_TRANSPORT_V1";
    static final String ATTESTATION_PURPOSE="APPROVAL_INFORMATION_COMMAND_REPLAY_ATTESTATION_V1";
    static final int BODY_MAX=524288,OWNER_MAX=262144,TOKEN_MAX=2048,ATTESTATION_MAX=524270;
    static final Set<String> ATTESTATION_FIELDS=Set.of("iss","aud","sub","iat","nbf","exp","jti","purpose","operation",
            "sourceProofJti","transportProofJti","bodySha256","bindingsSha256","authority","result");
    static final Set<String> AUTHORITY_FIELDS=Set.of("ownerAuthRevision","ownerPolicyRevision","sourceRevision","sourceVectorSha256","evaluatedAt","expiresAt");
    private InformationReplayProtocol() { }
}
