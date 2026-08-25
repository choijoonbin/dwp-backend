package com.dwp.services.approval.security;

import java.util.List;
import java.util.Optional;

/** Server-resolved registry route authority for the current Approval request. */
public final class ApprovalPilotAuthorizationContext {

    private static final ThreadLocal<List<ApprovalPilotPepRegistry.RouteAuthority>> AUTHORITIES =
            new ThreadLocal<>();

    private ApprovalPilotAuthorizationContext() {
    }

    static void set(List<ApprovalPilotPepRegistry.RouteAuthority> authorities) {
        if (authorities == null || authorities.isEmpty()) {
            throw new IllegalArgumentException("Approval route authority cannot be empty.");
        }
        AUTHORITIES.set(List.copyOf(authorities));
    }

    public static Optional<List<ApprovalPilotPepRegistry.RouteAuthority>> current() {
        return Optional.ofNullable(AUTHORITIES.get());
    }

    public static boolean requiresPredicate(String predicatePolicyKey) {
        return current().orElse(List.of()).stream()
                .anyMatch(authority -> authority.predicatePolicyKeys().contains(predicatePolicyKey));
    }

    public static Optional<ApprovalPilotPepRegistry.RouteAuthority> highRisk() {
        return current().orElse(List.of()).stream()
                .filter(ApprovalPilotPepRegistry.RouteAuthority::highRisk)
                .findFirst();
    }

    static void clear() {
        AUTHORITIES.remove();
    }
}
