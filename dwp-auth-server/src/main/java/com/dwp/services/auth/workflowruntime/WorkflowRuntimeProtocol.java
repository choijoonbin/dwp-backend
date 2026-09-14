package com.dwp.services.auth.workflowruntime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.util.Set;

public final class WorkflowRuntimeProtocol {
    public static final String PATH = "/internal/approval-workflow/runtime-authority";
    public static final String TOKEN_HEADER = "X-DWP-Approval-Workflow-Runtime-Token";
    public static final int MAX_BODY = 524288;
    public static final int MAX_OWNER_PROOF = 262144;
    public static final int MAX_ATTESTATION = 524270;
    public static final String TRANSPORT_ISSUER = "dwp-approval-server:workflow-runtime-transport:v1";
    public static final String TRANSPORT_AUDIENCE = "dwp-auth-server:workflow-runtime-transport:v1";
    public static final String TRANSPORT_PURPOSE = "APPROVAL_WORKFLOW_RUNTIME_TRANSPORT_V1";
    public static final Set<String> OWNER_CLAIMS = Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti",
            "purpose", "operation", "bindings");
    public static final Set<String> TRANSPORT_CLAIMS = Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti",
            "purpose", "operation", "httpMethod", "httpPath", "bodySha256", "sourceProofSha256");
    public static final Set<String> ATTESTATION_CLAIMS = Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti",
            "purpose", "operation", "sourceProofJti", "transportProofJti", "bodySha256", "bindingsSha256", "authority", "result");
    public static final Set<String> AUTHORITY_FIELDS = Set.of("ownerAuthRevision", "ownerPolicyRevision", "sourceRevision",
            "sourceVectorSha256", "evaluatedAt", "expiresAt");

    private WorkflowRuntimeProtocol() { }
    public enum Operation {
        CANDIDATES("candidates"), VOTER("voter"), INFORMATION_ADMISSION("information-admission");
        private final String slug;
        Operation(String slug) { this.slug = slug; }
        public String ownerIssuer() { return "dwp-approval-server:workflow-runtime:" + slug + ":v1"; }
        public String ownerAudience() { return "dwp-auth-server:workflow-runtime:" + slug + ":v1"; }
        public String ownerPurpose() { return "APPROVAL_WORKFLOW_" + name() + "_V1"; }
        public String attestationIssuer() { return ownerAudience(); }
        public String attestationAudience() { return ownerIssuer(); }
        public String attestationPurpose() { return "APPROVAL_WORKFLOW_" + name() + "_ATTESTATION_V1"; }
    }
    public enum PoolMode { INITIAL, REBUILD, RETAINED, SEALED }
    public enum TargetUse { CAST, SEAT_RECHECK, VOTE_RECHECK, INFORMATION_RECHECK }
    public static BaseException denied() { return new BaseException(ErrorCode.FORBIDDEN, "The dedicated workflow runtime proof is invalid."); }
    public static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Current workflow runtime authority is unavailable."); }
    public static BaseException changed() { return new BaseException(ErrorCode.DECISION_REVISION_CONFLICT, "Current workflow runtime authority changed."); }
}
