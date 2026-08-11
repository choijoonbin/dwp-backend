package com.dwp.services.provider.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Optional;
import java.util.Set;

public final class ProviderRequestContext {

    private static final ThreadLocal<Actor> ACTOR = new ThreadLocal<>();

    private ProviderRequestContext() {
    }

    public static void set(Actor actor) {
        ACTOR.set(actor);
    }

    public static void setForTest(Long userId, Long authTenantId) {
        ACTOR.set(new Actor(
                userId,
                userId,
                authTenantId,
                "Test operator",
                Set.of("PROVIDER_ADMIN"),
                Set.of(
                        "ESTATE_READ",
                        "TENANT_WRITE",
                        "ENTITLEMENT_WRITE",
                        "OPERATION_EXECUTE",
                        "SUPPORT_SESSION_WRITE",
                        "HEALTH_READ",
                        "RELIABILITY_READ",
                        "MAINTENANCE_WRITE",
                        "INCIDENT_WRITE",
                        "COMMERCIAL_READ",
                        "CATALOG_READ",
                        "DATA_GOVERNANCE_READ",
                        "CHANGE_APPROVE",
                        "BREAK_GLASS_SUPPORT",
                        "AUDIT_READ")));
    }

    public static Actor require() {
        Actor actor = ACTOR.get();
        if (actor == null) throw new IllegalStateException("Provider request context is missing.");
        return actor;
    }

    public static Optional<Long> currentUserId() {
        return Optional.ofNullable(ACTOR.get()).map(Actor::operatorId);
    }

    public static void clear() {
        ACTOR.remove();
    }

    public static void requirePermission(String permission) {
        if (!require().permissions().contains(permission)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Provider permission is required: " + permission);
        }
    }

    public record Actor(
            Long operatorId,
            Long userId,
            Long authTenantId,
            String displayName,
            Set<String> roles,
            Set<String> permissions) {

        public Actor {
            roles = Set.copyOf(roles);
            permissions = Set.copyOf(permissions);
        }
    }

}
