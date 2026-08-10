package com.dwp.services.auth.dto;

public record OidcUserInfo(
        String issuer,
        String subject,
        String email,
        boolean emailVerified,
        String name) {
}
