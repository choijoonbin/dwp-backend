package com.dwp.services.platform.home.runtime;

public final class WidgetProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        FORBIDDEN,
        TIMEOUT,
        UNAVAILABLE,
        MALFORMED
    }

    private final Kind kind;
    private final String reasonCode;
    private final String securityViolationReason;

    public WidgetProviderException(Kind kind, String reasonCode, String message) {
        this(kind, reasonCode, message, null, null);
    }

    public WidgetProviderException(Kind kind, String reasonCode, String message, Throwable cause) {
        this(kind, reasonCode, message, cause, null);
    }

    WidgetProviderException(
            Kind kind,
            String reasonCode,
            String message,
            Throwable cause,
            String securityViolationReason) {
        super(message, cause);
        this.kind = kind;
        this.reasonCode = reasonCode;
        this.securityViolationReason = securityViolationReason;
    }

    public Kind kind() {
        return kind;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String securityViolationReason() {
        return securityViolationReason;
    }
}
