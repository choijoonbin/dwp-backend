package com.dwp.services.provider.settings;

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

    public record Actor(
            Long authTenantId,
            Long authUserId,
            UUID authSessionId,
            UUID providerTenantId,
            Set<String> roles) {

        public Actor {
            roles = Set.copyOf(roles);
        }

        public boolean editor() {
            return roles.stream().anyMatch(
                    role -> role.equals("ADMIN")
                            || role.equals("TENANT_ADMIN")
                            || role.equals("PLATFORM_ADMIN"));
        }
    }
}
