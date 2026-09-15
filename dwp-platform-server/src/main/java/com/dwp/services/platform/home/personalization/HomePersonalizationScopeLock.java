package com.dwp.services.platform.home.personalization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Serializes mutations within one tenant/user/surface/mode scope. */
@Component
public class HomePersonalizationScopeLock {
    private static final long HASH_SEED = 7_193_041_731L;

    private final JdbcTemplate jdbc;

    public HomePersonalizationScopeLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(Long tenantId, Long userId, String surfaceKey) {
        String scope = "home:" + tenantId + ":" + userId + ":" + surfaceKey;
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, ?))",
                result -> null,
                scope,
                HASH_SEED);
    }

    public void lock(Long tenantId, Long userId, String surfaceKey, String modeKey) {
        String canonical = HomeModeKeys.canonical(modeKey);
        lock(tenantId, userId, HomeModeKeys.CLASSIC.equals(canonical)
                ? surfaceKey
                : surfaceKey + ":" + canonical);
    }
}
