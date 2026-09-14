package com.dwp.services.approval.workflowauthority;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.util.Set;

public final class WorkflowRuntimeProtocol {
    private WorkflowRuntimeProtocol() { }
    public static final String PATH = "/internal/approval-workflow/runtime-authority";
    public static final String HEADER = "X-DWP-Approval-Workflow-Runtime-Token";
    public static final int BODY_MAX = 524288, OWNER_MAX = 262144, TRANSPORT_MAX = 2048, ATTESTATION_MAX = 524270;
    public enum Operation {
        CANDIDATES("candidates"), VOTER("voter"), INFORMATION_ADMISSION("information-admission");
        private final String slug;
        Operation(String slug) { this.slug = slug; }
        public String ownerIssuer() { return "dwp-approval-server:workflow-runtime:" + slug + ":v1"; }
        public String ownerAudience() { return "dwp-auth-server:workflow-runtime:" + slug + ":v1"; }
        public String ownerPurpose() { return "APPROVAL_WORKFLOW_" + name() + "_V1"; }
        public String attestationIssuer() { return "dwp-auth-server:workflow-runtime:" + slug + ":v1"; }
        public String attestationAudience() { return "dwp-approval-server:workflow-runtime:" + slug + ":v1"; }
        public String attestationPurpose() { return "APPROVAL_WORKFLOW_" + name() + "_ATTESTATION_V1"; }
    }
    static final String TRANSPORT_ISSUER = "dwp-approval-server:workflow-runtime-transport:v1";
    static final String TRANSPORT_AUDIENCE = "dwp-auth-server:workflow-runtime-transport:v1";
    static final String TRANSPORT_PURPOSE = "APPROVAL_WORKFLOW_RUNTIME_TRANSPORT_V1";
    static final Set<String> ATTESTATION_CLAIMS = Set.of("iss","aud","sub","iat","nbf","exp","jti","purpose","operation",
            "sourceProofJti","transportProofJti","bodySha256","bindingsSha256","authority","result");
    static final Set<String> AUTHORITY_FIELDS = Set.of("ownerAuthRevision","ownerPolicyRevision","sourceRevision","sourceVectorSha256","evaluatedAt","expiresAt");
    static final Set<String> COMMAND_FIELDS = Set.of("tenantId","actorId","personPublicId","commandPurpose","targetId","routeContractKey","method","path",
            "idempotencyKey","rawBodySha256","contextKey","contextScopeKey","decisionRevision","accessMode","rolloutState","authorityValidUntil","expectedVersion","sourceGeneration");
    static final Set<String> INFO_RESULT_FIELDS = Set.of("tenantId","personPublicId","commandPurpose","targetId","idempotencyKey","rawBodySha256",
            "expectedVersion","sourceGeneration","contextKey","contextScopeKey","decisionRevision","routeContractKey","accessMode","rolloutState","authorityValidUntil");
    public static BaseException denied() { return new BaseException(ErrorCode.FORBIDDEN, "Workflow runtime authority proof is invalid."); }
    public static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "The dedicated workflow runtime source is unavailable."); }
}
