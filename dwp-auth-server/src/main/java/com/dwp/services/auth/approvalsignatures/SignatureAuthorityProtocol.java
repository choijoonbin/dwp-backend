package com.dwp.services.auth.approvalsignatures;

import java.util.Set;
import java.util.UUID;

/** Closed independent Auth copy of the native self-attestation source protocol. */
public final class SignatureAuthorityProtocol {
    private SignatureAuthorityProtocol() { }
    public static final String PATH = "/internal/auth/v1/approval-signature-authority/evaluate";
    public static final String HEADER = "X-DWP-Approval-Signature-Source-Token";
    public static final String OWNER = "DWP_APPROVAL_SIGNATURE_OWNER_SOURCE_V1";
    public static final String TRANSPORT = "DWP_APPROVAL_SIGNATURE_TRANSPORT_V1";
    public static final String ATTESTATION = "DWP_APPROVAL_SIGNATURE_AUTHORITY_V1";
    public static final int BODY_LIMIT = 65536;
    public static final int TOKEN_LIMIT = 32768;
    public static final long MAX_INTEGER = 9007199254740991L;
    public static final Set<String> STANDARD = Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti");
    public static final Set<String> SOURCE_FIELDS = Set.of("requestId", "requestVersion", "payloadRevision", "payloadSha256",
            "formVersionId", "formSchemaSha256", "workflowVersionId", "workflowSha256", "resourceSetKey", "manifestSha256",
            "documentPolicyId", "documentPolicyVersion", "documentPolicyRevision", "documentPolicySha256",
            "attachmentPolicyId", "attachmentPolicyVersion", "attachmentPolicyRevision", "attachmentPolicySha256",
            "providerId", "providerVersion", "providerSha256", "ownerUserId", "rendererVersion", "artifactSha256", "signingKeySha256");
    public enum Operation {
        CONTEXT("GET", "requests", "/signature-context", "signature-context.data"),
        CREATE("POST", "requests", "/signature-requests", "signature-request-create.action"),
        GET("GET", "signature-requests", "", "signature-request.data"),
        CONSENT("POST", "signature-requests", "/consents", "signature-consent.action"),
        SIGN("POST", "signature-requests", "/sign", "signature-sign.action"),
        CANCEL("POST", "signature-requests", "/cancel", "signature-cancel.action"),
        AUDIT("GET", "signature-requests", "/audit", "signature-audit.data"),
        COMMAND_RECEIPT("GET", "signature-command-receipts", "", "signature-command-receipt.data");
        private final String method, root, suffix, leaf;
        Operation(String method, String root, String suffix, String leaf) {
            this.method = method; this.root = root; this.suffix = suffix; this.leaf = leaf;
        }
        public String method() { return method; }
        public String path(UUID id) { return "/v1/" + root + '/' + id + suffix; }
        public String route() { return "route.approvals.work." + leaf; }
        public boolean mutation() { return "POST".equals(method); }
        public String capability() { return this == SIGN ? "approvals.work.signature.sign"
                : mutation() ? "approvals.work.signature.update" : "approvals.work.signature.read"; }
        public String permission() { return this == SIGN ? "ACTION.APPROVAL_SIGNATURE:SIGN"
                : mutation() ? "ACTION.APPROVAL_SIGNATURE:UPDATE" : "ACTION.APPROVAL_REQUEST:VIEW"; }
    }
}
