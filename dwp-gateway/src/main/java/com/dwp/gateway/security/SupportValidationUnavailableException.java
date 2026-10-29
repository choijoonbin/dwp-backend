package com.dwp.gateway.security;

/** Provider support validation could not produce authoritative evidence. */
public class SupportValidationUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SupportValidationUnavailableException() {
    }

    public SupportValidationUnavailableException(Throwable cause) {
        super(cause);
    }
}
