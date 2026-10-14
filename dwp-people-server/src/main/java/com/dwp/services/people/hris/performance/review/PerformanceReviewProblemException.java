package com.dwp.services.people.hris.performance.review;

import org.springframework.http.HttpStatus;

import java.util.UUID;

final class PerformanceReviewProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;
    private final Long currentVersion;
    private final UUID receiptId;
    private final boolean retryable;

    PerformanceReviewProblemException(
            HttpStatus status,
            String code,
            String message,
            Long currentVersion,
            UUID receiptId,
            boolean retryable) {
        super(message);
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
