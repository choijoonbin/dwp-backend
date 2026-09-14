package com.dwp.services.approval.policyimpactsource;

import java.util.Map;
import java.util.Set;

/** Independent service-side copy of the closed wire protocol, not an Auth module dependency. */
public final class PolicyImpactSourceProtocol {
    private PolicyImpactSourceProtocol() { }
    public static final String PATH = "/internal/auth/v1/approval-policy-impact-authority/evaluate";
    public static final String HEADER = "X-DWP-Approval-Policy-Impact-Token";
    public static final String ROUTE = "route.approvals.admin.policy-impact.data";
    public static final String OWNER_PURPOSE = "APPROVAL_POLICY_IMPACT_SOURCE_V1";
    public static final String TRANSPORT_PURPOSE = "APPROVAL_POLICY_IMPACT_AUTHORITY_TRANSPORT_V1";
    public static final String ATTESTATION_PURPOSE = "APPROVAL_POLICY_IMPACT_AUTHORITY_ATTESTATION_V1";
    public static final String OWNER_ISSUER = "approval-policy-impact-owner";
    public static final String OWNER_AUDIENCE = "auth-approval-policy-impact-source";
    public static final String TRANSPORT_ISSUER = "approval-policy-impact-transport";
    public static final String TRANSPORT_AUDIENCE = "auth-approval-policy-impact-authority";
    public static final String ATTESTATION_ISSUER = "auth-approval-policy-impact-attestation";
    public static final String ATTESTATION_AUDIENCE = "approval-policy-impact-authority";
    public static final Set<String> STANDARD = Set.of("iss", "aud", "sub", "jti", "iat", "nbf", "exp");
    public static final Set<String> BINDINGS = Set.of("tenantId", "actorId", "personPublicId", "policyId",
            "expectedVersion", "sourceDigest", "method", "path", "rawQuerySha256", "routeContractKey",
            "contextKey", "contextScopeKey", "resourceSetKey", "decisionRevision", "rolloutState", "accessMode", "authorityValidUntil");
    public static final Map<String, String> REQUIRED = Map.of(
            "approvals.policy.read", "ADMIN.APPROVAL_POLICY:VIEW",
            "approvals.design.read", "ADMIN.APPROVAL_DESIGN:VIEW",
            "approvals.operations.read", "ADMIN.APPROVAL_OPERATIONS:VIEW");
    public static final int BODY_LIMIT = 524288;
    public static final int OWNER_LIMIT = 16384;
    public static final int TRANSPORT_LIMIT = 2048;
    public static final long MAX_SAFE_INTEGER = 9007199254740991L;
}
