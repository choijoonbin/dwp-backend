package com.dwp.services.notification.domain;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Serializes tenant governance publication with user attention-rule writers. */
@Component
public class NotificationAttentionGovernanceLock {

    static final String LOCK_NAMESPACE = "notification-attention-governance:";

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationAttentionGovernanceLock(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockTenant(long tenantId) {
        if (tenantId < 1) {
            throw new IllegalArgumentException("A positive tenant identifier is required.");
        }
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                new MapSqlParameterSource("lockKey", LOCK_NAMESPACE + tenantId),
                resultSet -> null);
    }
}
