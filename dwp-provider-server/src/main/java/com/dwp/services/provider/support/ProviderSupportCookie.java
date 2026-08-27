package com.dwp.services.provider.support;

import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class ProviderSupportCookie {

    public static final String NAME = "DWP_SUPPORT_SESSION";
    private static final List<String> PATHS = List.of(
            "/api/provider/v1/admin/",
            "/api/auth/",
            "/api/platform/v1/admin/");

    private ProviderSupportCookie() {
    }

    public static List<ResponseCookie> issue(String token, Instant expiresAt, boolean secure) {
        long maxAge = Math.max(1, Duration.between(Instant.now(), expiresAt).getSeconds());
        return PATHS.stream()
                .map(path -> base(token, path, secure).maxAge(Duration.ofSeconds(maxAge)).build())
                .toList();
    }

    public static List<ResponseCookie> clear(boolean secure) {
        return PATHS.stream()
                .map(path -> base("", path, secure).maxAge(Duration.ZERO).build())
                .toList();
    }

    private static ResponseCookie.ResponseCookieBuilder base(
            String value,
            String path,
            boolean secure) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(path);
    }
}
