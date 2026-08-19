package com.dwp.services.notification.integration;

/**
 * A producer contract violation that must be routed to the dead-letter topic without retry.
 */
public final class ApprovalNotificationEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Classification classification;

    public ApprovalNotificationEventException(Classification classification, String message) {
        super(message);
        this.classification = classification;
    }

    public ApprovalNotificationEventException(
            Classification classification,
            String message,
            Throwable cause) {
        super(message, cause);
        this.classification = classification;
    }

    public Classification classification() {
        return classification;
    }

    public enum Classification {
        MALFORMED,
        TENANT_MISMATCH,
        EVENT_TYPE_MISMATCH,
        EVENT_TYPE_NOT_ALLOWED,
        PAYLOAD_CONTRACT_VIOLATION
    }
}
