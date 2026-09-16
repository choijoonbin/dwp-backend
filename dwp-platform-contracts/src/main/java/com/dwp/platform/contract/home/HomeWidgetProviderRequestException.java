package com.dwp.platform.contract.home;

/** A transport-safe owner-provider request failure. */
public final class HomeWidgetProviderRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        BAD_REQUEST,
        UNAUTHORIZED,
        FORBIDDEN,
        SERVICE_UNAVAILABLE
    }

    private final Kind kind;
    private final String reasonCode;

    public HomeWidgetProviderRequestException(Kind kind, String reasonCode, String message) {
        super(message);
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
