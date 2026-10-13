package com.dwp.services.people.hris.performance.workforce;

/** Fail-closed errors raised by the PER-owned workforce projection boundary. */
public final class PerformanceWorkforceProjectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        AUTHORITY_UNAVAILABLE,
        OWNER_CONTRACT_MISMATCH,
        OWNER_REVISION_CHANGED,
        OWNER_CURSOR_REPEATED,
        OWNER_PAGE_LIMIT_EXCEEDED,
        PROJECTION_NOT_READY,
        STALE_WORKFORCE_SNAPSHOT,
        PROJECTION_REVISION_MISMATCH,
        PREVIEW_HASH_MISMATCH,
        EVENT_PAYLOAD_DIGEST_MISMATCH,
        EVENT_ID_PAYLOAD_MISMATCH,
        EVENT_REPLAY_NOT_PENDING,
        PROJECTION_QUARANTINED
    }

    private final Code code;

    public PerformanceWorkforceProjectionException(Code code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code, "code must not be null");
    }

    public PerformanceWorkforceProjectionException(
            Code code,
            String message,
            Throwable cause) {
        super(message, cause);
        this.code = java.util.Objects.requireNonNull(code, "code must not be null");
    }

    public Code code() {
        return code;
    }
}
