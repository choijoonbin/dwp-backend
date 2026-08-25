package com.dwp.services.platform.home.personalization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class HomeTemplateScopeLock {
    private static final long LOCK_SEED = 0x44575054454d504cL;
    private final JdbcTemplate jdbc;

    public HomeTemplateScopeLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(Long tenantId) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, ?))",
                result -> null,
                "home-template:" + tenantId,
                LOCK_SEED);
    }
}
