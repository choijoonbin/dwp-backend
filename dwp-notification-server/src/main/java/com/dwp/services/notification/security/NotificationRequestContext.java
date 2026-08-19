package com.dwp.services.notification.security;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;

import java.util.Set;

public final class NotificationRequestContext {

    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    private NotificationRequestContext() {
    }

    public static void set(Actor actor) {
        CURRENT.set(actor);
    }

    public static Actor requireActor() {
        Actor actor = CURRENT.get();
        if (actor == null || actor.userId() == null) {
            throw new NotificationException(NotificationErrorCode.UNAUTHORIZED);
        }
        return actor;
    }

    public static Actor requireInternalActor() {
        Actor actor = CURRENT.get();
        if (actor == null || !actor.internal()) {
            throw new NotificationException(NotificationErrorCode.UNAUTHORIZED);
        }
        return actor;
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Actor(
            long tenantId,
            Long userId,
            Set<String> roles,
            Set<String> permissions,
            boolean internal,
            String sourceService) {
    }
}
