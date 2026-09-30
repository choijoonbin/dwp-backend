package com.dwp.services.provider.settings;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Set;
import java.util.UUID;

public final class TenantSettingsRequestContext {

    private static final ThreadLocal<Actor> ACTOR = new ThreadLocal<>();

    private TenantSettingsRequestContext() {
    }

    public static void set(Actor actor) {
        ACTOR.set(actor);
    }

    public static Actor require() {
        Actor actor = ACTOR.get();
        if (actor == null) throw new IllegalStateException("Tenant settings context is missing.");
        return actor;
    }

    public static void clear() {
        ACTOR.remove();
    }

    public static void requirePermission(String permission) {
        if (!require().permissions().contains(permission)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Tenant resource permission is required: " + permission);
        }
    }

    public record Actor(
            Long authTenantId,
            Long authUserId,
            UUID authSessionId,
            UUID providerTenantId,
            Set<String> roles,
            Set<String> permissions) {

        public Actor {
            roles = Set.copyOf(roles);
            permissions = Set.copyOf(permissions);
        }

        public boolean editor() {
            return roles.stream().anyMatch(
                    role -> role.equals("ADMIN")
                            || role.equals("TENANT_ADMIN")
                            || role.equals("PLATFORM_ADMIN"));
        }
    }
}
