package com.dwp.services.provider.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
                        "SUPPORT_ACCESS_READ",
                        "SUPPORT_ACCESS_REVIEW",
                        "SUPPORT_POST_REVIEW",
                        "HEALTH_READ",
                        "RELIABILITY_READ",
                        "MAINTENANCE_WRITE",
                        "INCIDENT_WRITE",
                        "COMMERCIAL_READ",
                        "COMMERCIAL_WRITE",
                        "COMMERCIAL_APPROVE",
                        "CATALOG_READ",
                        "DATA_GOVERNANCE_READ",
                        "DATA_GOVERNANCE_WRITE",
                        "DATA_GOVERNANCE_APPROVE",
                        "FEATURE_ROLLOUT_READ",
                        "FEATURE_ROLLOUT_WRITE",
                        "FEATURE_ROLLOUT_APPROVE",
                        "CHANGE_APPROVE",
                        "BREAK_GLASS_SUPPORT",
                        "AUDIT_READ"),
                UUID.fromString("00000000-0000-0000-0000-000000000001")));
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
            Set<String> permissions,
            UUID authSessionId) {

        public Actor(
                Long operatorId,
                Long userId,
                Long authTenantId,
                String displayName,
                Set<String> roles,
                Set<String> permissions) {
            this(operatorId, userId, authTenantId, displayName, roles, permissions, null);
        }

        public Actor {
            roles = Set.copyOf(roles);
            permissions = Set.copyOf(permissions);
        }

        public Actor withAuthSessionId(UUID value) {
            return new Actor(
                    operatorId, userId, authTenantId, displayName, roles, permissions, value);
        }
    }

}
