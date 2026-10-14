package com.dwp.services.people.hr.performance.compensationplanning;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;
import java.util.UUID;

/**
 * Request-local identity established only by the dedicated SVC-PEP-PER-001
 * ingress filter. Header values are never read again after this context is
 * created.
 */
public final class ApprovedCompensationSnapshotRequestContext {

    static final String REQUEST_ATTRIBUTE =
            ApprovedCompensationSnapshotRequestContext.class.getName() + ".verified";

    private ApprovedCompensationSnapshotRequestContext() {
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
                && ApprovedCompensationSnapshotIngressFilter.isExactGet(
                        request.getMethod(), request.getRequestURI())
                && verified.path().equals(request.getRequestURI())) {
            return verified;
        }
        throw new IllegalStateException(
                "Verified SVC-PEP-PER-001 request context is required.");
    }

    /** Used by the generic People filter without widening its path allowlist. */
    public static boolean isVerifiedRequest(HttpServletRequest request) {
        try {
            require(request);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    record VerifiedRequest(
            String path,
            UUID snapshotId,
            long snapshotRevision,
            String payloadDigest,
            long tenantId,
            long tenantGrantRevision,
            long policyRevision,
            UUID correlationId,
            String callerWorkloadId,
            String audience,
            String purposeCode,
            String projectionId) {
        VerifiedRequest {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(payloadDigest, "payloadDigest");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(callerWorkloadId, "callerWorkloadId");
            Objects.requireNonNull(audience, "audience");
            Objects.requireNonNull(purposeCode, "purposeCode");
            Objects.requireNonNull(projectionId, "projectionId");
        }
    }
}
