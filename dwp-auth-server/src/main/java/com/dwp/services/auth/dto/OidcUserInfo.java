package com.dwp.services.auth.dto;

import java.time.Instant;
import java.util.List;

public record OidcUserInfo(
        String issuer,
        String subject,
        String email,
        boolean emailVerified,
        String name,
        Instant authenticatedAt,
        String acr,
        List<String> amr) {

    public OidcUserInfo {
        amr = amr == null ? List.of() : amr.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    public OidcUserInfo(
            String issuer,
            String subject,
            String email,
            boolean emailVerified,
            String name) {
        this(issuer, subject, email, emailVerified, name, null, null, List.of());
    }
}
