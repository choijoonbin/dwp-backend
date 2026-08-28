package com.dwp.services.notification.common;

public class NotificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final NotificationErrorCode errorCode;

    public NotificationException(NotificationErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public NotificationException(NotificationErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public NotificationException(
            NotificationErrorCode errorCode,
            String message,
            Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public NotificationErrorCode errorCode() {
        return errorCode;
    }
}
