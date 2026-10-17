package com.dwp.services.people.hris.employeeservice.certificate;

import org.springframework.http.HttpStatus;

/** Closed owner failure; diagnostics never cross the controller boundary. */
final class CertificateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;
    private final boolean retryable;

    private CertificateException(
            HttpStatus status,
            String code,
            String diagnostic,
            boolean retryable) {
        super(diagnostic);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    static CertificateException invalid(String diagnostic) {
        return new CertificateException(
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST", diagnostic, false);
    }

    static CertificateException denied(String diagnostic) {
        return new CertificateException(
                HttpStatus.FORBIDDEN, "CERTIFICATE_ACCESS_DENIED", diagnostic, false);
    }

    static CertificateException conflict(String code, String diagnostic) {
        return new CertificateException(HttpStatus.CONFLICT, code, diagnostic, false);
    }

    static CertificateException unavailable(String diagnostic) {
        return new CertificateException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CERTIFICATE_ARTIFACT_UNAVAILABLE", diagnostic, true);
    }

    HttpStatus status() { return status; }
    String code() { return code; }
    boolean retryable() { return retryable; }
}
