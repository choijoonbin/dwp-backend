package com.dwp.services.people.hris.workbench.hrm007;

import org.springframework.http.HttpStatus;

/** Internal closed failure; messages are deliberately not serialized. */
final class Hrm007Exception extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;
    private final boolean retryable;

    private Hrm007Exception(
            HttpStatus status,
            String code,
            String diagnostic,
            boolean retryable) {
        super(diagnostic);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    static Hrm007Exception invalid(String diagnostic) {
        return new Hrm007Exception(
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST", diagnostic, false);
    }

    static Hrm007Exception denied(String diagnostic) {
        return new Hrm007Exception(
                HttpStatus.FORBIDDEN, "HRIS_WORKBENCH_ACCESS_DENIED", diagnostic, false);
    }

    static Hrm007Exception unavailable(String diagnostic) {
        return new Hrm007Exception(
                HttpStatus.SERVICE_UNAVAILABLE,
                "HRIS_WORKBENCH_PROJECTION_UNAVAILABLE", diagnostic, true);
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    boolean retryable() {
        return retryable;
    }
}
