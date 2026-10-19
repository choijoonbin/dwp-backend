package com.dwp.services.auth.hrisperformancefeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Additive canonical feature evidence; the DATA key selects policy, never query authority. */
public final class PerformanceFeatureContract {
    public static final String PATH = "/internal/auth/v1/hris-performance-feature-decisions:query";
    public static final String FEATURE = "HRIS_PERFORMANCE_CONFIGURATION";
    public static final String DECISION_ROUTE = "route.hcm.configuration.settings-performance.data";
    public static final String BINDING = "HRIS_PERFORMANCE_FEATURE_DECISION_V1";
    public static final String PURPOSE = "FEATURE_POLICY_LOOKUP_ONLY";
    public static final Set<String> ACTIONS = Set.of(
            "route.hcm.configuration.configuration-version-create.action",
            "route.hcm.configuration.configuration-version-validate.action",
            "route.hcm.configuration.configuration-version-simulate.action",
            "route.hcm.configuration.configuration-version-submit.action",
            "route.hcm.configuration.configuration-version-approve.action",
            "route.hcm.configuration.configuration-version-publish.action");

    private PerformanceFeatureContract() { }

    public record Request(long tenantId, String subjectPrincipalPublicId,
            String exactSharedActionRouteContractKey, String configurationId,
            String resolvedDomain, String scopeDigest, long commandAuthorizationRevision,
            String correlationId) {
        public Request {
            if (tenantId <= 0 || !uuid(subjectPrincipalPublicId) || !uuid(configurationId)
                    || !uuid(correlationId) || !ACTIONS.contains(exactSharedActionRouteContractKey)
                    || !"PER".equals(resolvedDomain) || scopeDigest == null
                    || !scopeDigest.matches("[0-9a-f]{64}") || commandAuthorizationRevision <= 0) {
                throw new IllegalArgumentException("Exact PER command binding required.");
            }
        }
    }

    public record Response(long tenantId, String subjectPrincipalPublicId, String featureKey,
            String decisionRoute, String exactSharedActionRouteContractKey,
            String configurationId, String resolvedDomain, String scopeDigest,
            long commandAuthorizationRevision, String correlationId,
            String featureState, String decision, String featureDecisionRevision,
            Instant evaluatedAt, Instant revalidateAt) { }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Same numeric projection used by Gateway for the trusted command authorization revision. */
    public static long authorizationRevision(String authRevision) {
        if (authRevision == null || authRevision.isBlank()) return 0;
        byte[] bytes = HexFormat.of().parseHex(sha256(
                ("authorization:" + authRevision).getBytes(StandardCharsets.UTF_8)));
        long value = java.nio.ByteBuffer.wrap(bytes).getLong() & Long.MAX_VALUE;
        return value == 0 ? 1 : value;
    }

    static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
