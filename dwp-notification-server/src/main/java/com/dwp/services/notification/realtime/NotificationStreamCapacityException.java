package com.dwp.services.notification.realtime;

public final class NotificationStreamCapacityException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    public static final int RETRY_AFTER_SECONDS = 5;

    public NotificationStreamCapacityException() {
        super("Notification stream connection capacity is temporarily unavailable.");
    }
}
