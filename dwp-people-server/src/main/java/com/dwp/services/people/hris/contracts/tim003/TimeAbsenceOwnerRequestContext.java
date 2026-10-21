package com.dwp.services.people.hris.contracts.tim003;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;

/** Request attribute established only after the dedicated workload proof verifier succeeds. */
public final class TimeAbsenceOwnerRequestContext {

    private static final String ATTRIBUTE =
            TimeAbsenceOwnerRequestContext.class.getName() + ".verified";

    private TimeAbsenceOwnerRequestContext() {
    }

    static void set(
            HttpServletRequest request,
            TimeAbsenceOwnerContract.VerifiedRequest verified) {
        request.setAttribute(ATTRIBUTE, Objects.requireNonNull(verified));
    }

    static void clear(HttpServletRequest request) {
        request.removeAttribute(ATTRIBUTE);
    }

    static TimeAbsenceOwnerContract.VerifiedRequest require(
            HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof TimeAbsenceOwnerContract.VerifiedRequest verified
                && TimeAbsenceOwnerIngressFilter.matchesVerifiedRequest(request, verified)) {
            return verified;
        }
        throw new IllegalStateException(
                "Verified SVC-PEP-HRM-007 workload context is required.");
    }

    /** The generic People filter delegates only after exact proof and wire revalidation. */
    public static boolean isVerifiedRequest(HttpServletRequest request) {
        try {
            require(request);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
