package com.dwp.services.auth.informationreplay;

import java.util.Set;

public final class InformationReplayProtocol {
    private InformationReplayProtocol() { }
    public static final String PATH = "/internal/approval-workflow/information-command-replay";
    public static final String HEADER = "X-DWP-Approval-Information-Replay-Token";
    public static final String ROUTE = "route.approvals.work.information-command-receipt.data";
    public static final String CAPABILITY = "approvals.work.information-command-receipt.read";
    public static final String PREDICATE = "predicate.approval.original-information-command-receipt.v1";
    public static final String SCOPE_RESOLVER = "ORIGINAL_COMPLETED_INFORMATION_COMMAND_ACTOR_WITH_CURRENT_ORIGINAL_AUTHORITY";
    public static final String OPERATION = "RECEIPT_REPLAY";
    public static final String OWNER_ISSUER = "dwp-approval-server:information-command-replay:v1";
    public static final String OWNER_AUDIENCE = "dwp-auth-server:information-command-replay:v1";
    public static final String OWNER_PURPOSE = "APPROVAL_INFORMATION_COMMAND_REPLAY_V1";
    public static final String TRANSPORT_ISSUER = "dwp-approval-server:information-replay-transport:v1";
    public static final String TRANSPORT_AUDIENCE = "dwp-auth-server:information-replay-transport:v1";
    public static final String TRANSPORT_PURPOSE = "APPROVAL_INFORMATION_REPLAY_TRANSPORT_V1";
    public static final String ATTESTATION_PURPOSE = "APPROVAL_INFORMATION_COMMAND_REPLAY_ATTESTATION_V1";
    public static final int BODY_LIMIT = 524288;
    public static final int OWNER_LIMIT = 262144;
    public static final int TRANSPORT_LIMIT = 2048;
    public static final int ATTESTATION_LIMIT = 524270;
    public static final long SAFE_INTEGER = 9007199254740991L;
    public static final Set<String> OWNER_CLAIMS = Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti", "purpose", "version", "sealed");
    public static final Set<String> TRANSPORT_CLAIMS = Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti", "purpose",
            "sourceProofJti", "sourceProofSha256", "bodySha256", "contextKey", "routeContractKey");
    public static final Set<String> ATTESTATION_CLAIMS = Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti", "purpose", "operation",
            "sourceProofJti", "transportProofJti", "bodySha256", "bindingsSha256", "authority", "result");
    public static final Set<String> AUTHORITY_FIELDS = Set.of("ownerAuthRevision", "ownerPolicyRevision", "sourceRevision",
            "sourceVectorSha256", "evaluatedAt", "expiresAt");
    public static final Set<String> RESULT_FIELDS = Set.of("receiptSha256", "commandSha256", "admissionSha256", "role", "originalActor", "principal");
}
