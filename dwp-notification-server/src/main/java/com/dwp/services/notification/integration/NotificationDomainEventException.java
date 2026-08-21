package com.dwp.services.notification.integration;

public class NotificationDomainEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotificationDomainEventException(String message) {
        super(message);
    }

    public NotificationDomainEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
