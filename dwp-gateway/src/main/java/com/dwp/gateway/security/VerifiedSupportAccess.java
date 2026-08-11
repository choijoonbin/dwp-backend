package com.dwp.gateway.security;

import java.time.Instant;
import java.util.List;

public record VerifiedSupportAccess(
        String supportSessionId,
        String providerTenantId,
        String authTenantId,
        String tenantKey,
        String tenantName,
        List<String> scopes,
        String accessMode,
        Instant expiresAt,
        long version) {

    public VerifiedSupportAccess {
        if (supportSessionId == null || supportSessionId.isBlank()
                || authTenantId == null || authTenantId.isBlank()) {
            throw new IllegalArgumentException("Support session and target tenant are required.");
        }
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
