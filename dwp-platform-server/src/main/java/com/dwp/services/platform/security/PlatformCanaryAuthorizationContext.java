package com.dwp.services.platform.security;

import java.util.List;
import java.util.Optional;

/** Server-resolved Canary route contracts for the current request thread. */
public final class PlatformCanaryAuthorizationContext {

    private static final ThreadLocal<List<String>> ROUTES = new ThreadLocal<>();

    private PlatformCanaryAuthorizationContext() {
    }

    static void set(List<String> routeContractKeys) {
        if (routeContractKeys == null || routeContractKeys.isEmpty()) {
            throw new IllegalArgumentException("Canary route contract context cannot be empty.");
        }
        ROUTES.set(List.copyOf(routeContractKeys));
    }

    static Optional<List<String>> current() {
        return Optional.ofNullable(ROUTES.get());
    }

    static void clear() {
        ROUTES.remove();
    }
}
