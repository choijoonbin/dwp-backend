package com.dwp.services.people.security;

import java.time.OffsetDateTime;

/** Trusted Gateway decision evidence available only during one People request. */
public final class HcmPepContext {

    private static final ThreadLocal<Evidence> CURRENT = new ThreadLocal<>();

    private HcmPepContext() {
    }

    static void set(Evidence evidence) {
        CURRENT.set(evidence);
    }

    public static Evidence current() {
        return CURRENT.get();
    }

    public static Evidence require() {
        Evidence evidence = CURRENT.get();
        if (evidence == null) {
            throw new IllegalStateException("HCM product-surface PEP evidence is unavailable.");
        }
        return evidence;
    }

    static void clear() {
        CURRENT.remove();
    }

    public record Evidence(
            HcmV3PepRegistry.RouteAuthority authority,
            String decisionRevision,
            OffsetDateTime revalidateAt,
            String contextKey,
            String scopeKey,
            String rolloutState) {
    }
}
