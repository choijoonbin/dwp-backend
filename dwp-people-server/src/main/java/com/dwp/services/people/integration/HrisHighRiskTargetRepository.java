package com.dwp.services.people.integration;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Locks the tenant-bound object revision used by HRIS step-up commands. */
@Repository
public class HrisHighRiskTargetRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public HrisHighRiskTargetRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> lockConnector(Long tenantId, UUID connectorId) {
        return jdbc.query("""
                SELECT version FROM int_connector_instances
                 WHERE tenant_id = :tenantId AND connector_instance_id = :connectorId
                 FOR UPDATE
                """, parameters(tenantId).addValue("connectorId", connectorId),
                (result, ignored) -> result.getLong("version")).stream().findFirst();
    }

    public Optional<SyncRunTarget> lockRun(Long tenantId, UUID syncRunId) {
        return jdbc.query("""
                SELECT connector_instance_id, sync_mode, lifecycle_state, version
                  FROM int_sync_runs
                 WHERE tenant_id = :tenantId AND sync_run_id = :syncRunId
                 FOR UPDATE
                """, parameters(tenantId).addValue("syncRunId", syncRunId),
                (result, ignored) -> new SyncRunTarget(
                        result.getObject("connector_instance_id", UUID.class),
                        result.getString("sync_mode"), result.getString("lifecycle_state"),
                        result.getLong("version"))).stream().findFirst();
    }

    public boolean claimRunRetry(Long tenantId, UUID syncRunId, long expectedVersion) {
        return jdbc.update("""
                UPDATE int_sync_runs
                   SET version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND sync_run_id = :syncRunId
                   AND version = :expectedVersion AND lifecycle_state = 'FAILED'
                """, parameters(tenantId).addValue("syncRunId", syncRunId)
                .addValue("expectedVersion", expectedVersion)) == 1;
    }

    private MapSqlParameterSource parameters(Long tenantId) {
        return new MapSqlParameterSource("tenantId", tenantId);
    }

    public record SyncRunTarget(
            UUID connectorId, String syncMode, String lifecycleState, long version) {
    }
}
