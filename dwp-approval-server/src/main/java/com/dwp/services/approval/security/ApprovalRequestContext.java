package com.dwp.services.approval.security;

import java.util.Set;
import java.util.UUID;

public final class ApprovalRequestContext {

    private static final ThreadLocal<Actor> ACTOR = new ThreadLocal<>();

    private ApprovalRequestContext() {
    }

    public static void set(
            Long userId,
            Long tenantId,
            UUID personPublicId,
            Set<String> roles,
            Set<String> permissions) {
        set(userId, tenantId, personPublicId, null, roles, permissions);
    }

    public static void set(
            Long userId,
            Long tenantId,
            UUID personPublicId,
            String displayName,
            Set<String> roles,
            Set<String> permissions) {
        ACTOR.set(new Actor(
                userId,
                tenantId,
                personPublicId,
                displayName,
                Set.copyOf(roles),
                Set.copyOf(permissions)));
    }

    public static Actor require() {
        Actor actor = ACTOR.get();
        if (actor == null) {
            throw new IllegalStateException("Approval request context is not available.");
        }
        return actor;
    }

    public static void clear() {
        ACTOR.remove();
    }

    public record Actor(
            Long userId,
            Long tenantId,
            UUID personPublicId,
            String displayName,
            Set<String> roles,
            Set<String> permissions) {

        public boolean hasPermission(String resource, String... actions) {
            for (String action : actions) {
                if (permissions.contains(resource + ":" + action)) return true;
            }
            return false;
        }

        public boolean hasAnyRole(String... expected) {
            for (String role : expected) {
                if (roles.contains(role)) return true;
            }
            return false;
        }

        public boolean canAdminister() {
            return permissions.stream().anyMatch(permission ->
                    permission.startsWith("ADMIN.APPROVAL_") && permission.endsWith(":VIEW"));
        }
    }
}
