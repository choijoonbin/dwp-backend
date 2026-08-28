package com.dwp.services.notification.common;

import org.springframework.http.HttpStatus;

public enum NotificationErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "E1001", "The input is invalid."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "E2000", "Authentication is required."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "E2001", "The operation is not permitted."),
    SERVICE_NOT_CONFIGURED(HttpStatus.BAD_GATEWAY, "E5000", "Notification service identity is not configured."),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "The notification was not found."),
    NOTIFICATION_TARGET_UNAVAILABLE(HttpStatus.GONE, "NOTIFICATION_TARGET_UNAVAILABLE", "The notification target is no longer available."),
    NOTIFICATION_STALE_VERSION(HttpStatus.CONFLICT, "NOTIFICATION_STALE_VERSION", "The notification was changed by another request."),
    NOTIFICATION_INVALID_CURSOR(HttpStatus.BAD_REQUEST, "NOTIFICATION_INVALID_CURSOR", "The cursor is invalid or expired."),
    NOTIFICATION_SYNC_RESET_REQUIRED(HttpStatus.CONFLICT, "NOTIFICATION_SYNC_RESET_REQUIRED", "A full notification refresh is required."),
    NOTIFICATION_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "NOTIFICATION_IDEMPOTENCY_CONFLICT", "The idempotency key was reused with a different request."),
    DECISION_REVISION_CONFLICT(HttpStatus.CONFLICT, "DECISION_REVISION_CONFLICT", "The authorization decision revision changed."),
    NOTIFICATION_CONTRACT_QUARANTINED(HttpStatus.UNPROCESSABLE_ENTITY, "NOTIFICATION_CONTRACT_QUARANTINED", "The notification contract is not active or compatible."),
    NOTIFICATION_IDENTITY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "NOTIFICATION_IDENTITY_UNAVAILABLE", "Recipient entitlement validation is unavailable."),
    AUTHORITY_RESOLUTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AUTHORITY_RESOLUTION_UNAVAILABLE", "Current authorization evidence is unavailable."),
    NOTIFICATION_CAPABILITY_DISABLED(HttpStatus.NOT_IMPLEMENTED, "NOTIFICATION_CAPABILITY_DISABLED", "The requested notification capability is disabled."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "E1000", "An internal server error occurred.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    NotificationErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
