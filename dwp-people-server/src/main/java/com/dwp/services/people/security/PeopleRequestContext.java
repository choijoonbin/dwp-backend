package com.dwp.services.people.security;

import java.util.Set;

public final class PeopleRequestContext {

    private static final ThreadLocal<Actor> ACTOR = new ThreadLocal<>();

    private PeopleRequestContext() {
    }

    public static void set(Long userId, Long tenantId, Set<String> roles) {
        ACTOR.set(new Actor(userId, tenantId, Set.copyOf(roles)));
    }

    public static Actor require() {
        Actor actor = ACTOR.get();
        if (actor == null) {
            throw new IllegalStateException("People request context is not available.");
        }
        return actor;
    }

    public static void clear() {
        ACTOR.remove();
    }

    public record Actor(Long userId, Long tenantId, Set<String> roles) {

        public boolean hasAnyRole(String... expected) {
            for (String role : expected) {
                if (roles.contains(role)) return true;
            }
            return false;
        }
    }
}
