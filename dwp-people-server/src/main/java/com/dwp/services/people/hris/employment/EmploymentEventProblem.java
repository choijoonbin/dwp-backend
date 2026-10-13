package com.dwp.services.people.hris.employment;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/** Stable employment error carrying only safe recovery metadata. */
final class EmploymentEventProblem extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;
    private final Long currentVersion;
    private final UUID receiptId;
    private final boolean retryable;

    EmploymentEventProblem(
            HttpStatus status,
            String code,
            String safeMessage,
            Long currentVersion,
            UUID receiptId,
            boolean retryable) {
        super(safeMessage);
        this.status = status;
        this.code = code;
        this.currentVersion = currentVersion;
        this.receiptId = receiptId;
        this.retryable = retryable;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    Long currentVersion() {
        return currentVersion;
    }

    UUID receiptId() {
        return receiptId;
    }

    boolean retryable() {
        return retryable;
    }
}
