package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;

/** Verified request state established only by the dedicated HRM-013 ingress filter. */
public final class Hrm013CallbackRequestContext {
    static final String ATTRIBUTE = Hrm013CallbackRequestContext.class.getName() + ".verified";

    private final Route route;
    private final Hrm013WorkloadProofVerifier.VerifiedCallback verified;
    private final Hrm013SeparationBridgeV1.PayloadBody body;

    Hrm013CallbackRequestContext(
            Route route, Hrm013WorkloadProofVerifier.VerifiedCallback verified,
            Hrm013SeparationBridgeV1.PayloadBody body) {
        this.route = Objects.requireNonNull(route, "route");
        this.verified = Objects.requireNonNull(verified, "verified");
        this.body = Objects.requireNonNull(body, "body");
    }

    public Route route() { return route; }
    public Hrm013WorkloadProofVerifier.VerifiedCallback verified() { return verified; }
    public Hrm013SeparationBridgeV1.PayloadBody body() { return body; }

    static void set(HttpServletRequest request, Hrm013CallbackRequestContext context) {
        request.setAttribute(ATTRIBUTE, Objects.requireNonNull(context, "context"));
    }

    static void clear(HttpServletRequest request) { request.removeAttribute(ATTRIBUTE); }

    public static Hrm013CallbackRequestContext require(HttpServletRequest request) {
        Object raw = request.getAttribute(ATTRIBUTE);
        if (raw instanceof Hrm013CallbackRequestContext context
                && context.route.method().equals(request.getMethod())
                && context.route.path().equals(request.getRequestURI())
                && request.getQueryString() == null
                && context.route.correlationId().toString().equals(
                request.getHeader("X-Correlation-ID"))) {
            return context;
        }
        throw new IllegalStateException("Verified HRM-013 callback context is required");
    }

    public static boolean isVerifiedRequest(HttpServletRequest request) {
        try {
            require(request);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    public enum Kind { TASK_RECEIPT, RECONCILIATION_RESULT, WAIVER_DECISION }

    public record Route(
            Kind kind, String method, String path, UUID casePublicId,
            UUID taskPublicId, UUID correlationId,
            Hrm013SeparationBridgeV1.BindingProfile bindingProfile,
            Hrm013WorkloadProofV1.Profile proofProfile) {
        public Route {
            Objects.requireNonNull(kind, "kind");
            if (!"POST".equals(method) || path == null || path.indexOf('?') >= 0) {
                throw new IllegalArgumentException("exact POST route is required");
            }
            Objects.requireNonNull(casePublicId, "casePublicId");
            if (kind == Kind.RECONCILIATION_RESULT ? taskPublicId != null
                    : taskPublicId == null) {
                throw new IllegalArgumentException("route task identity mismatch");
            }
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(bindingProfile, "bindingProfile");
            Objects.requireNonNull(proofProfile, "proofProfile");
            if (!path.equals(proofProfile.exactPath())
                    || !bindingProfile.matchesPath(path)) {
                throw new IllegalArgumentException("route profile mismatch");
            }
        }
    }
}
