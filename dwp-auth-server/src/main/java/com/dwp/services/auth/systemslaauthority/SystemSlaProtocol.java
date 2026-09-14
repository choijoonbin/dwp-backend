package com.dwp.services.auth.systemslaauthority;

import java.util.Set;

public final class SystemSlaProtocol {
    public static final String PATH = "/internal/auth/v1/approval-system-sla-authority/evaluate";
    public static final String HEADER = "X-DWP-Approval-System-Sla-Token";
    public static final String OWNER_PURPOSE = "APPROVAL_SYSTEM_SLA_SOURCE_V1";
    public static final String TRANSPORT_PURPOSE = "APPROVAL_SYSTEM_SLA_TRANSPORT_V1";
    public static final String ATTESTATION_PURPOSE = "APPROVAL_SYSTEM_SLA_ATTESTATION_V1";
    public static final String OWNER_ISSUER = "dwp-approval-system-sla-owner";
    public static final String OWNER_AUDIENCE = "dwp-auth-system-sla-owner";
    public static final String TRANSPORT_ISSUER = "dwp-approval-system-sla-transport";
    public static final String TRANSPORT_AUDIENCE = "dwp-auth-system-sla-transport";
    public static final String ATTESTATION_ISSUER = "dwp-auth-system-sla-attestation";
    public static final String ATTESTATION_AUDIENCE = "dwp-approval-system-sla-attestation";
    public static final int BODY_LIMIT = 524288, OWNER_LIMIT = 16384, TRANSPORT_LIMIT = 2048, AUDIENCE_LIMIT = 1000;
    public static final long MAX_SAFE_INTEGER = 9007199254740991L;
    public static final Set<String> STANDARD = Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti");
    private SystemSlaProtocol() { }
}
