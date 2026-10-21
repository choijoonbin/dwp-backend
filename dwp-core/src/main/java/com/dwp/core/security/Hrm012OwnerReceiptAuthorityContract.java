package com.dwp.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Closed wire contract for the HRM-012 receipt-first authorization hand-off. */
public final class Hrm012OwnerReceiptAuthorityContract {

    public static final String HEADER = "X-DWP-HRM012-Owner-Receipt-Authority";
    public static final String TYPE = "dwp-hrm012-owner-receipt-auth+jwt";
    public static final String ALGORITHM = "RS256";
    public static final String ISSUER = "dwp-auth-server/hrm012-owner-receipt/v1";
    public static final String AUDIENCE = "dwp-people-server";
    public static final String STATE = "OWNER_RECEIPT_AUTH_REQUIRED";
    public static final String ROUTE = "route.hcm.operations.workforce-ingestion.action";
    public static final String METHOD = "POST";
    public static final String SERVICE_PATH = "/v1/hris/integrations/workforce-ingestions";
    public static final String PUBLIC_PATH = "/api/people/v1/hris/integrations/workforce-ingestions";
    public static final String FEATURE = "HRIS_HRM_DATA_QUALITY_WORKBENCH";
    public static final String CHALLENGE_HEADER = "X-DWP-Step-Up-Challenge";

    public static final Set<String> HEADER_FIELDS = Set.of("typ", "alg", "kid");
    public static final Set<String> CLAIM_FIELDS = Set.of(
            "iss", "aud", "iat", "nbf", "exp", "jti", "state", "tenant_id",
            "on_behalf_of_subject", "route_contract_key", "command_method",
            "command_path", "accepted_app_entitlement", "feature_key",
            "feature_decision_revision", "correlation_id", "idempotency_key_sha256",
            "opaque_step_up_challenge_ref");
    public static final Set<String> APP_ENTITLEMENTS =
            Set.of("APP.HCM:VIEW", "APP.HRIS:VIEW");

    private Hrm012OwnerReceiptAuthorityContract() {
    }

    public static String sha256(String value) {
        if (value == null) throw new IllegalArgumentException("Value is required.");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
