package com.dwp.services.approval.hrisbridge.separation;

import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Binds the fail-closed V59 tenant RLS setting to the active transaction. */
public final class HrisSeparationRlsScope {
    private final NamedParameterJdbcTemplate jdbc;

    public HrisSeparationRlsScope(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void bind(long tenantId) {
        if (tenantId < 1 || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("HRM-013 tenant scope requires an active transaction.");
        }
        jdbc.queryForObject(
                "SELECT set_config('dwp.approval.hrm013.tenant',:tenant,true)",
                Map.of("tenant", Long.toString(tenantId)), String.class);
    }
}
