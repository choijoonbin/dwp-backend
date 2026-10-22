package com.dwp.services.people.hris.contracts.hrm012;

/** Opaque fail-closed problem raised by the HRM-012 owner boundary. */
public final class Hrm012Problem extends RuntimeException {
    private final String code;
    private final int status;
    private final boolean retryable;

    private Hrm012Problem(String code, int status, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
    }

    public static Hrm012Problem invalid(String message) {
        return new Hrm012Problem("HRM012_INVALID_REQUEST", 400, message, false);
    }

    public static Hrm012Problem denied(String message) {
        return new Hrm012Problem("HRM012_FORBIDDEN", 403, message, false);
    }

    public static Hrm012Problem conflict(String code, String message) {
        return new Hrm012Problem(code, 409, message, false);
    }

    public static Hrm012Problem unavailable(String message) {
        return new Hrm012Problem(
                "HRM012_AUTHORITY_OR_PROVIDER_UNAVAILABLE", 503, message, true);
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
