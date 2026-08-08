package com.dwp.gateway.security;

import java.util.List;

public record VerifiedIdentity(String userId, String tenantId, List<String> roles) {

    public VerifiedIdentity {
        if (userId == null || userId.isBlank() || tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("userId and tenantId are required");
        }
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
