package com.dwp.services.messaging.security;

import java.util.Set;
import java.util.UUID;

public final class MessagingRequestContext {

    private static final ThreadLocal<Subject> CURRENT = new ThreadLocal<>();

    private MessagingRequestContext() {
    }

    public static void set(Subject subject) {
        CURRENT.set(subject);
    }

    public static Subject get() {
        Subject subject = CURRENT.get();
        if (subject == null) {
            throw new IllegalStateException("Messaging request context is not available.");
        }
        return subject;
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Subject(
            long userId,
            long tenantId,
            UUID personPublicId,
            String displayName,
            Set<String> roles,
            Set<String> permissions,
            Set<String> groupRefs) {

        public boolean has(String resourceKey, String... permissionCodes) {
            for (String code : permissionCodes) {
                if (permissions.contains(resourceKey + ":" + code)) return true;
            }
            return false;
        }
    }
}
