package com.dwp.services.platform.home.personalization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Serializes every Classic and Flow mutation for one tenant/user/surface scope. */
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
}
