package com.dwp.services.space.security;

import java.util.Set;
import java.util.UUID;

public final class SpaceRequestContext {

    private static final ThreadLocal<Subject> CURRENT = new ThreadLocal<>();

    private SpaceRequestContext() {
    }

    public static void set(Subject subject) {
        CURRENT.set(subject);
    }

    public static Subject get() {
        Subject subject = CURRENT.get();
        if (subject == null) {
            throw new IllegalStateException("Space request context is not available.");
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

        public boolean tenantAdministrator() {
            return roles.contains("TENANT_ADMIN")
                    || roles.contains("ADMIN")
                    || roles.contains("PLATFORM_ADMIN");
        }
    }
}
