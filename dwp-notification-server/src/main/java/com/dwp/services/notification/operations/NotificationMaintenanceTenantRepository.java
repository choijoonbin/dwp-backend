package com.dwp.services.notification.operations;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class NotificationMaintenanceTenantRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationMaintenanceTenantRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Long> activeTenantIdsAfter(long afterTenantId, int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Notification maintenance tenant page size is invalid.");
        }
        jdbc.getJdbcTemplate().execute("SET LOCAL ROLE dwp_notification_worker");
        return jdbc.queryForList("""
                SELECT tenant_id
                  FROM ntf_runtime_tenants
                 WHERE tenant_id > :afterTenantId
                 ORDER BY tenant_id
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("afterTenantId", afterTenantId)
                .addValue("limit", limit), Long.class);
    }
}
