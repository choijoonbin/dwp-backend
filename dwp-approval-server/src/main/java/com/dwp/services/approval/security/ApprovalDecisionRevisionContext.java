package com.dwp.services.approval.security;

import java.time.OffsetDateTime;
import java.util.Optional;

/** Trusted current authority revision injected by the Gateway after direct evaluation. */
public final class ApprovalDecisionRevisionContext {

    private static final ThreadLocal<Evidence> CURRENT = new ThreadLocal<>();

    private ApprovalDecisionRevisionContext() {
    }

    static void set(
            String revision,
            OffsetDateTime validUntil,
            String contextKey,
            String contextScopeKey,
            String routeContractKey,
            String rolloutState) {
        CURRENT.set(new Evidence(
                revision, validUntil, contextKey, contextScopeKey,
                routeContractKey, rolloutState));
    }

    public static Optional<Evidence> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static void clear() {
        CURRENT.remove();
    }

    public record Evidence(
            String revision,
            OffsetDateTime validUntil,
            String contextKey,
            String contextScopeKey,
            String routeContractKey,
            String rolloutState) {
    }
}
