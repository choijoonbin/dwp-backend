package com.dwp.core.security;

import java.util.Collection;
import java.util.Locale;

/**
 * Defines the identity boundary between provider control-plane operators and
 * customer-tenant identities. A provider role is never additive to a tenant or
 * workspace role; tenant access is represented by a separately verified,
 * time-bound support context instead.
 */
public final class RolePlaneBoundary {

    public static final String CONFLICT_REASON = "PROVIDER_TENANT_PLANE_SEPARATION";

    private RolePlaneBoundary() {
    }

    public static boolean hasConflict(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) return false;
        boolean provider = false;
        boolean tenant = false;
        for (String roleCode : roleCodes) {
            String normalized = normalize(roleCode);
            if (normalized == null) continue;
            if (normalized.startsWith("PROVIDER_")) {
                provider = true;
            } else {
                tenant = true;
            }
            if (provider && tenant) return true;
        }
        return false;
    }

    public static boolean isProviderIdentity(Collection<String> roleCodes) {
        return roleCodes != null && roleCodes.stream()
                .filter(RolePlaneBoundary::isProviderRole)
                .findAny()
                .isPresent();
    }

    public static boolean isProviderRole(String roleCode) {
        String normalized = normalize(roleCode);
        return normalized != null && normalized.startsWith("PROVIDER_");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
