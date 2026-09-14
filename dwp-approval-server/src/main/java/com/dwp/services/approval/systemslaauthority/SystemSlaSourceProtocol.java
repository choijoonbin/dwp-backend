package com.dwp.services.approval.systemslaauthority;

import java.util.Set;

public final class SystemSlaSourceProtocol {
    private SystemSlaSourceProtocol() { }
    public static final String PATH = "/internal/auth/v1/approval-system-sla-authority/evaluate";
    public static final String HEADER = "X-DWP-Approval-System-Sla-Token";
    public static final String OWNER_ISSUER = "dwp-approval-system-sla-owner";
    public static final String OWNER_AUDIENCE = "dwp-auth-system-sla-owner";
    public static final String OWNER_PURPOSE = "APPROVAL_SYSTEM_SLA_SOURCE_V1";
    public static final String TRANSPORT_ISSUER = "dwp-approval-system-sla-transport";
    public static final String TRANSPORT_AUDIENCE = "dwp-auth-system-sla-transport";
    public static final String TRANSPORT_PURPOSE = "APPROVAL_SYSTEM_SLA_TRANSPORT_V1";
    public static final String ATTESTATION_ISSUER = "dwp-auth-system-sla-attestation";
    public static final String ATTESTATION_AUDIENCE = "dwp-approval-system-sla-attestation";
    public static final String ATTESTATION_PURPOSE = "APPROVAL_SYSTEM_SLA_ATTESTATION_V1";
    public static final int BODY_LIMIT = 524288;
    public static final Set<String> ATTESTATION_FIELDS = Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti", "purpose",
            "sourceProofJti", "transportProofJti", "bodySha256", "bindingsSha256", "sourceDigest", "authority", "recipients");
    public static final Set<String> REASONS = Set.of("ELIGIBLE", "RESOURCE_SET_INACTIVE", "SUBJECT_MISSING", "TENANT_INACTIVE",
            "SUBJECT_INACTIVE", "PERSON_CHANGED", "REQUESTER_SOD", "ROLE_MISSING", "APP_NOT_ENTITLED", "TASK_PERMISSION_DENIED", "SOURCE_EXPIRED");
}
