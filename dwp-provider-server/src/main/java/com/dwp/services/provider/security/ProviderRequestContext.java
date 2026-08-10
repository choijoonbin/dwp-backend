package com.dwp.services.provider.security;

import java.util.Optional;

public final class ProviderRequestContext {

    private static final ThreadLocal<Actor> ACTOR = new ThreadLocal<>();

    private ProviderRequestContext() {
    }

    public static void set(Long userId, Long authTenantId) {
        ACTOR.set(new Actor(userId, authTenantId));
    }

    public static Actor require() {
        Actor actor = ACTOR.get();
        if (actor == null) throw new IllegalStateException("Provider request context is missing.");
        return actor;
    }

    public static Optional<Long> currentUserId() {
        return Optional.ofNullable(ACTOR.get()).map(Actor::userId);
    }

    public static void clear() {
        ACTOR.remove();
    }

    public record Actor(Long userId, Long authTenantId) {
    }
}
