package com.dwp.services.platform.security;

import java.util.Optional;

public final class RequestActorContext {

    private static final ThreadLocal<Long> ACTOR = new ThreadLocal<>();

    private RequestActorContext() {
    }

    public static void set(Long actorId) {
        ACTOR.set(actorId);
    }

    public static Optional<Long> current() {
        return Optional.ofNullable(ACTOR.get());
    }

    public static void clear() {
        ACTOR.remove();
    }
}
