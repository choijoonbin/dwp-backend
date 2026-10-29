package com.dwp.services.people.hris.contracts.hrm011;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;
import java.util.UUID;

/** Typed request identity established only by the dedicated HRM-011 ingress filter. */
public final class Hrm011WorkloadRequestContext {

    static final String REQUEST_ATTRIBUTE =
            Hrm011WorkloadRequestContext.class.getName() + ".verified";

    private Hrm011WorkloadRequestContext() {
    }

    static void set(HttpServletRequest request, VerifiedRequest verified) {
        request.setAttribute(REQUEST_ATTRIBUTE, Objects.requireNonNull(verified));
    }

    static void clear(HttpServletRequest request) {
        request.removeAttribute(REQUEST_ATTRIBUTE);
    }

    static VerifiedRequest require(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        if (value instanceof VerifiedRequest verified
                && Hrm011WorkloadRouteContract.matchesVerifiedRequest(request, verified)) {
            return verified;
        }
        throw new IllegalStateException("Verified HRM-011 workload context is required.");
    }

    /** Used by the generic People filter without introducing a path/header allowlist. */
    public static boolean isVerifiedRequest(HttpServletRequest request) {
        try {
            require(request);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    record VerifiedRequest(
            String method,
            String path,
            String queryFingerprint,
            String bindingId,
            UUID attemptId,
            UUID correlationId,
            long tenantGrantRevision,
            Hrm011WorkloadContract.TrustedWorkload trusted,
            Hrm011WorkloadContract.SnapshotRequest snapshotRequest)
            implements Hrm011WorkloadContract.VerifiedRequestView {
        VerifiedRequest {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(queryFingerprint, "queryFingerprint");
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(trusted, "trusted");
            Objects.requireNonNull(snapshotRequest, "snapshotRequest");
        }
    }
}
