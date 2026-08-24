package com.dwp.services.approval.security;

import java.util.Optional;

/** Owner-service scope selected and revalidated by the Gateway/Auth PEP chain. */
public final class ApprovalManagementScopeContext {

    private static final ThreadLocal<Evidence> CURRENT = new ThreadLocal<>();

    private ApprovalManagementScopeContext() {
    }

    static void set(String opaqueScopeKey, String resourceSetKey) {
        if (opaqueScopeKey == null || opaqueScopeKey.isBlank()
                || resourceSetKey == null
                || !resourceSetKey.matches("[A-Z][A-Z0-9_]{2,79}")) {
            throw new IllegalArgumentException("Approval management scope evidence is invalid.");
        }
        CURRENT.set(new Evidence(opaqueScopeKey, resourceSetKey));
    }

    public static Optional<Evidence> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static String requireResourceSetKey() {
        return current().orElseThrow(() -> new IllegalStateException(
                "Approval management scope evidence is unavailable.")).resourceSetKey();
    }

    static void clear() {
        CURRENT.remove();
    }

    public record Evidence(String opaqueScopeKey, String resourceSetKey) {
    }
}
