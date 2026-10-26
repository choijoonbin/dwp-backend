package com.dwp.services.people.hris.separation.hrm013;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

final class Hrm013TenantScope {
    private Hrm013TenantScope() {
    }

    static void setLocal(NamedParameterJdbcTemplate jdbc, long tenantId) {
        if (tenantId < 1) throw new IllegalArgumentException("tenantId must be positive");
        String applied = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenant AS VARCHAR), TRUE)",
                new MapSqlParameterSource("tenant", tenantId), String.class);
        if (!Long.toString(tenantId).equals(applied)) {
            throw new IllegalStateException("HRM-013 tenant database scope was not established");
        }
    }
}
