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
        String displayName) {

    public VerifiedIdentity(String userId, String tenantId, List<String> roles) {
        this(userId, tenantId, roles, List.of(), List.of(), List.of(), null, null);
    }

    public VerifiedIdentity(
            String userId,
            String tenantId,
            List<String> roles,
            List<String> permissions) {
        this(userId, tenantId, roles, permissions, List.of(), List.of(), null, null);
    }

    public VerifiedIdentity(
            String userId,
            String tenantId,
            List<String> roles,
            List<String> permissions,
            List<String> groupRefs,
            List<String> resourceRoles) {
        this(userId, tenantId, roles, permissions, groupRefs, resourceRoles, null, null);
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
    }
}
