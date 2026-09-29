package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationAction;

/** Fail-closed action/resource/scope and purpose checks for payroll foundation requests. */
final class PayrollFoundationAccess {

    private static final AccessPolicy COMPATIBILITY_POLICY = new AccessPolicy(
            "PAYROLL-ACCESS-COMPATIBILITY",
            1,
            "PAYROLL_FOUNDATION",
            Set.of("APP.HRIS:VIEW"),
            Set.of("PAYROLL_CONFIGURATION"),
            Set.of("PAYROLL_AUDIT"),
            Map.of(),
            Map.of());

    private PayrollFoundationAccess() {
    }

    static Actor actor(
            Long tenantId,
            Long actorId,
            String roles,
            String permissions,
            String purpose,
            String legalEntityScope) {
        return actor(
                tenantId, actorId, roles, permissions, purpose, legalEntityScope,
                COMPATIBILITY_POLICY);
    }

    static Actor actor(
            Long tenantId,
            Long actorId,
            String roles,
            String permissions,
            String purpose,
            String legalEntityScope,
            AccessPolicy policy) {
        return actor(
                tenantId, actorId, roles, permissions, purpose, legalEntityScope,
                "test-" + policy.policyId() + "-v" + policy.version(),
                "test-authorization-revision", policy);
    }

    static Actor actor(
            Long tenantId,
            Long actorId,
            String roles,
            String permissions,
            String purpose,
            String legalEntityScope,
            String policyRevision,
            String authorizationRevision,
            AccessPolicy policy) {
        if (tenantId == null || tenantId <= 0 || actorId == null || actorId <= 0) {
            throw new BaseException(ErrorCode.UNAUTHORIZED, "A positive tenant and actor are required.");
        }
        Set<String> permissionTokens = tokens(permissions);
        if (permissionTokens.stream().noneMatch(policy.acceptedEntitlements()::contains)) {
            throw forbidden();
        }
        Scope scope = Scope.parse(legalEntityScope);
        return new Actor(
                tenantId,
                actorId,
                parseRoles(roles),
                parseActions(permissionTokens, policy),
                requirePurpose(purpose, policy),
                scope,
                scope.digest(),
                requireRevision(policyRevision),
                requireRevision(authorizationRevision),
                policy);
    }

    static AccessPolicy compatibilityPolicy() {
        return COMPATIBILITY_POLICY;
    }

    record Actor(
            long tenantId,
            long actorId,
            Set<String> roles,
            Set<FoundationAction> actions,
            String purpose,
            Scope scope,
            String legalEntityScopeDigest,
            String policyRevision,
            String authorizationRevision,
            AccessPolicy policy) {

        Actor {
            roles = Set.copyOf(roles);
            actions = Set.copyOf(actions);
        }

        boolean allows(FoundationAction action, UUID legalEntityId) {
            if (!roleAndPurposeAllow(action) || !scope.allows(legalEntityId)) {
                return false;
            }
            return true;
        }

        boolean allowsUnscoped(FoundationAction action) {
            return scope.allLegalEntities() && roleAndPurposeAllow(action);
        }

        boolean allowsAny(FoundationAction action) {
            return roleAndPurposeAllow(action);
        }

        private boolean roleAndPurposeAllow(FoundationAction action) {
            if (!actions.contains(action)) {
                return false;
            }
            return policy.allows(roles, purpose, action);
        }

        void require(FoundationAction action, UUID legalEntityId) {
            if (!allows(action, legalEntityId)) {
                throw new BaseException(
                        ErrorCode.FORBIDDEN,
                        "Payroll foundation action is outside the actor's role, purpose, or scope.");
            }
        }
    }

    record AccessPolicy(
            String policyId,
            long version,
            String resourceKey,
            Set<String> acceptedEntitlements,
            Set<String> configurationPurposes,
            Set<String> auditPurposes,
            Map<String, Set<FoundationAction>> roleActions,
            Map<String, Set<FoundationAction>> roleDenials) {

        AccessPolicy {
            if (policyId == null || policyId.isBlank() || version <= 0
                    || resourceKey == null || resourceKey.isBlank()) {
                throw new IllegalArgumentException("A versioned access policy identity is required");
            }
            resourceKey = resourceKey.strip().toUpperCase(Locale.ROOT);
            acceptedEntitlements = normalizedStrings(acceptedEntitlements);
            configurationPurposes = normalizedStrings(configurationPurposes);
            auditPurposes = normalizedStrings(auditPurposes);
            roleActions = normalizedRoleActions(roleActions);
            roleDenials = normalizedRoleActions(roleDenials);
        }

        boolean allows(Set<String> roles, String purpose, FoundationAction action) {
            boolean purposeAllowed = configurationPurposes.contains(purpose)
                    || (auditPurposes.contains(purpose)
                    && (action == FoundationAction.VIEW
                    || action == FoundationAction.RECONCILE));
            if (!purposeAllowed) {
                return false;
            }
            if (roles.stream().anyMatch(role ->
                    roleDenials.getOrDefault(role, Set.of()).contains(action))) {
                return false;
            }
            return roleActions.isEmpty() || roles.stream().anyMatch(role ->
                    roleActions.getOrDefault(role, Set.of()).contains(action));
        }

        private static Map<String, Set<FoundationAction>> normalizedRoleActions(
                Map<String, Set<FoundationAction>> mappings) {
            if (mappings == null) {
                throw new IllegalArgumentException("Role action constraints cannot be null");
            }
            Map<String, Set<FoundationAction>> normalized = new LinkedHashMap<>();
            mappings.forEach((role, actions) -> normalized.put(
                    role.strip().toUpperCase(Locale.ROOT), Set.copyOf(actions)));
            return Collections.unmodifiableMap(normalized);
        }

        private static Set<String> normalizedStrings(Set<String> values) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("Access policy values cannot be empty");
            }
            Set<String> normalized = new LinkedHashSet<>();
            values.forEach(value -> normalized.add(value.strip().toUpperCase(Locale.ROOT)));
            return Set.copyOf(normalized);
        }
    }

    record Scope(boolean allLegalEntities, Set<UUID> legalEntityIds) {

        Scope {
            legalEntityIds = Set.copyOf(legalEntityIds);
            if (!allLegalEntities && legalEntityIds.isEmpty()) {
                throw forbidden();
            }
        }

        static Scope parse(String value) {
            if (value == null || value.isBlank()) {
                throw forbidden();
            }
            if ("*".equals(value.strip())) {
                return new Scope(true, Set.of());
            }
            try {
                Set<UUID> ids = new LinkedHashSet<>();
                for (String item : value.split(",")) {
                    if (!ids.add(UUID.fromString(item.strip()))) {
                        throw forbidden();
                    }
                }
                return new Scope(false, ids);
            } catch (IllegalArgumentException exception) {
                throw forbidden();
            }
        }

        boolean allows(UUID legalEntityId) {
            return allLegalEntities || legalEntityIds.contains(legalEntityId);
        }

        String digest() {
            String canonical = allLegalEntities ? "*" : legalEntityIds.stream()
                    .sorted()
                    .map(UUID::toString)
                    .collect(java.util.stream.Collectors.joining(","));
            return PayrollFoundationCanonical.textDigest(canonical);
        }
    }

    private static Set<String> parseRoles(String header) {
        Set<String> roles = tokens(header);
        if (roles.isEmpty()) {
            throw forbidden();
        }
        return roles;
    }

    private static Set<FoundationAction> parseActions(
            Set<String> permissionTokens, AccessPolicy policy) {
        Set<FoundationAction> actions = new LinkedHashSet<>();
        for (String token : permissionTokens) {
            String prefix = policy.resourceKey() + ":";
            if (!token.startsWith(prefix)) {
                continue;
            }
            try {
                actions.add(FoundationAction.valueOf(token.substring(prefix.length())));
            } catch (IllegalArgumentException ignored) {
                // Unknown permissions do not grant access.
            }
        }
        if (actions.isEmpty()) {
            throw forbidden();
        }
        return actions;
    }

    private static Set<String> tokens(String header) {
        if (header == null || header.isBlank()) {
            throw forbidden();
        }
        Set<String> values = new LinkedHashSet<>();
        Arrays.stream(header.split("[,\\s]+"))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .forEach(values::add);
        return values;
    }

    private static String requirePurpose(String purpose, AccessPolicy policy) {
        if (purpose == null) {
            throw forbidden();
        }
        String normalized = purpose.strip().toUpperCase(Locale.ROOT);
        if (!policy.configurationPurposes().contains(normalized)
                && !policy.auditPurposes().contains(normalized)) {
            throw forbidden();
        }
        return normalized;
    }

    private static String requireRevision(String revision) {
        if (revision == null) {
            throw forbidden();
        }
        String normalized = revision.strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,239}")) {
            throw forbidden();
        }
        return normalized;
    }

    private static BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN, "Payroll foundation access is fail-closed.");
    }
}
