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

    public WidgetProviderException(Kind kind, String reasonCode, String message) {
        super(message);
        this.kind = kind;
        this.reasonCode = reasonCode;
    }

    public WidgetProviderException(Kind kind, String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.reasonCode = reasonCode;
    }

    public Kind kind() {
        return kind;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
