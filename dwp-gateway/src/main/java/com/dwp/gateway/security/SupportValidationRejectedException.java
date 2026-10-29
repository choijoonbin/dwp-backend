package com.dwp.gateway.security;

import org.springframework.http.HttpStatusCode;

/** Provider support validation rejected a structurally valid validation request. */
public class SupportValidationRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final HttpStatusCode statusCode;

    public SupportValidationRejectedException(HttpStatusCode statusCode) {
        this.statusCode = statusCode;
    }

    public HttpStatusCode statusCode() {
        return statusCode;
    }
}
