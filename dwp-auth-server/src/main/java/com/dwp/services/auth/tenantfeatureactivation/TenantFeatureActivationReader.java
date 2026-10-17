package com.dwp.services.auth.tenantfeatureactivation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Fail-closed read boundary for the positive product-feature gate.
 *
 * <p>The gate never grants product authority. It may only preserve an already allowed
 * Product Surface decision or narrow it because adoption, activation, or enrollment is absent.
 */
@FunctionalInterface
public interface TenantFeatureActivationReader {

    GateResult evaluate(GateRequest request);

    enum State {
        UNGATED,
        FEATURE_NOT_ACTIVATED,
        TENANT_NOT_ENROLLED,
        ACTIVE,
        AUTHORITY_UNAVAILABLE
    }

    record GateRequest(
            Long tenantId,
            String productKey,
            String surfaceKey,
            String routeContractKey,
            long bundleVersion,
            Instant evaluatedAt) {
    }

    record GateResult(
            State state,
            String reasonCode,
            String featureKey,
            String revision) {

        public static GateResult ungoverned(String routeContractKey) {
            return new GateResult(
                    State.UNGATED,
                    "UNGATED",
                    null,
                    TenantFeatureActivationReader.revision(
                            "v259|UNGATED|" + canonical(routeContractKey)));
        }

        public static GateResult authorizationNotEligible(String routeContractKey) {
            return new GateResult(
                    State.UNGATED,
                    "AUTHORIZATION_NOT_ELIGIBLE",
                    null,
                    TenantFeatureActivationReader.revision(
                            "v259|AUTHORIZATION_NOT_ELIGIBLE|" + canonical(routeContractKey)));
        }

        public static GateResult unavailable() {
            return new GateResult(
                    State.AUTHORITY_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE",
                    null,
                    TenantFeatureActivationReader.revision(
                            "v259|AUTHORITY_UNAVAILABLE"));
        }

        public boolean preservesAuthority() {
            return state == State.UNGATED || state == State.ACTIVE;
        }
    }

    static TenantFeatureActivationReader unavailable() {
        return ignored -> GateResult.unavailable();
    }

    /** Explicit unit-test boundary only; production injection uses {@link #unavailable()}. */
    static TenantFeatureActivationReader ungoverned() {
        return request -> GateResult.ungoverned(request.routeContractKey());
    }

    static String revision(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "tenant-feature-v259-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String canonical(String value) {
        return value == null || value.isBlank() ? "NO_ROUTE" : value;
    }
}
