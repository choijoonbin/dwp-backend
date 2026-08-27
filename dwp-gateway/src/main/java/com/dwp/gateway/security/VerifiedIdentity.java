package com.dwp.gateway.security;

import java.util.List;

public record VerifiedIdentity(
        String userId,
        String tenantId,
        List<String> roles,
        List<String> permissions,
        List<String> groupRefs,
        List<String> resourceRoles,
        String personPublicId,
        String displayName,
        boolean legacyRoleFallbackAllowed,
        String sessionFamilyId,
        String identityPlane) {

    public VerifiedIdentity(
            String userId,
            String tenantId,
            List<String> roles,
            String identityPlane) {
        this(userId, tenantId, roles, List.of(), List.of(), List.of(), null, null,
                false, null, identityPlane);
    }

    public VerifiedIdentity {
        if (userId == null || userId.isBlank() || tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("userId and tenantId are required");
        }
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        groupRefs = groupRefs == null ? List.of() : List.copyOf(groupRefs);
        resourceRoles = resourceRoles == null ? List.of() : List.copyOf(resourceRoles);
        personPublicId = personPublicId == null || personPublicId.isBlank()
                ? null
                : personPublicId.trim();
        displayName = displayName == null || displayName.isBlank()
                ? null
                : displayName.trim();
        sessionFamilyId = sessionFamilyId == null || sessionFamilyId.isBlank()
                ? null
                : sessionFamilyId.trim();
        identityPlane = identityPlane == null ? null : identityPlane.trim();
        if (!"PROVIDER".equals(identityPlane) && !"TENANT".equals(identityPlane)) {
            throw new IllegalArgumentException(
                    "identityPlane is required and must be PROVIDER or TENANT");
        }
        requireNoProviderResourceRoles(identityPlane, resourceRoles);

        boolean hasProviderRole = false;
        boolean hasTenantRole = false;
        for (String role : roles) {
            if (role == null || role.isBlank()) continue;
            if (role.trim().toUpperCase(java.util.Locale.ROOT).startsWith("PROVIDER_")) {
                hasProviderRole = true;
            } else {
                hasTenantRole = true;
            }
        }
        if (hasProviderRole && hasTenantRole) {
            throw new IllegalArgumentException(
                    "provider and tenant roles cannot coexist on one identity");
        }
        if (("PROVIDER".equals(identityPlane) && hasTenantRole)
                || ("TENANT".equals(identityPlane) && hasProviderRole)) {
            throw new IllegalArgumentException(
                    "roles must belong to the durable identityPlane");
        }
    }

    static void requireNoProviderResourceRoles(
            String identityPlane,
            List<?> resourceRoles) {
        String plane = identityPlane == null ? null : identityPlane.trim();
        if ("PROVIDER".equals(plane)
                && resourceRoles != null
                && !resourceRoles.isEmpty()) {
            throw new IllegalArgumentException(
                    "provider identities cannot carry tenant resource-role evidence");
        }
    }
}
