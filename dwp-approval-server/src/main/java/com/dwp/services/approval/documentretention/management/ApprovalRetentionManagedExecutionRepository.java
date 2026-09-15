package com.dwp.services.approval.documentretention.management;

import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Dedicated-role access to the V34 managed execution state machine. */
public final class ApprovalRetentionManagedExecutionRepository {
    public record Lease(ApprovalRetentionExecutionAuthorityPort.Target target, UUID executionClaimId,
            String stage, long executionVersion, long generation, UUID token) {}
    public record ForeignWork(UUID deletionRequestId, long tenantId, String consumerService, String recoveryMode) {}
    public record Status(String stage, String state, long version, long generation, String reason, String outcomeSha256) {}

    private final JdbcTemplate jdbc;

    public ApprovalRetentionManagedExecutionRepository(DataSource executor) {
        this.jdbc = new JdbcTemplate(executor);
    }

    public int expireLostLeases() {
        return jdbc.queryForObject("SELECT apr_retention_internal.expire_managed_retention_leases(100)", Integer.class);
    }

    public Lease claim(UUID intentId, Long expectedIntentVersion, String workerId, Duration lease) {
        long seconds = lease == null ? 0 : lease.toSeconds();
        return jdbc.query("SELECT * FROM apr_retention_internal.claim_managed_retention_execution(CAST(? AS uuid),CAST(? AS bigint),CAST(? AS text),CAST(? AS integer))", result -> {
            if (!result.next()) return null;
            var target = new ApprovalRetentionExecutionAuthorityPort.Target(
                    result.getObject("intent_id", UUID.class), result.getLong("tenant_id"),
                    result.getLong("actor_user_id"), result.getObject("request_id", UUID.class),
                    result.getString("resource_set_key"), result.getLong("request_version"),
                    result.getObject("policy_id", UUID.class), result.getLong("policy_version"),
                    result.getLong("hold_version"), result.getString("inventory_sha256"),
                    result.getString("command_fingerprint"), result.getLong("intent_version"));
            return new Lease(target, result.getObject("execution_claim_id", UUID.class), result.getString("stage"),
                    result.getLong("execution_version"), result.getLong("generation"),
                    result.getObject("lease_token", UUID.class));
        }, intentId, expectedIntentVersion, workerId, seconds);
    }

    public long recover(UUID intentId, long expectedExecutionVersion) {
        return jdbc.queryForObject("SELECT apr_retention_internal.recover_managed_retention_execution(?,?)",
                Long.class, intentId, expectedExecutionVersion);
    }

    public UUID dispatch(Lease lease, String authoritySha256) {
        return jdbc.queryForObject("SELECT apr_retention_internal.dispatch_managed_retention_record(?,?,?,?,?)",
                UUID.class, lease.target().intentId(), lease.target().intentVersion(), lease.generation(),
                lease.token(), authoritySha256);
    }

    public long unknown(Lease lease, String reason) {
        return jdbc.queryForObject("SELECT apr_retention_internal.mark_managed_retention_unknown(?,?,?,?)",
                Long.class, lease.target().intentId(), lease.generation(), lease.token(), reason);
    }

    public long block(Lease lease, String reason) {
        return jdbc.queryForObject("SELECT apr_retention_internal.block_managed_retention_execution(?,?,?,?)",
                Long.class, lease.target().intentId(), lease.generation(), lease.token(), reason);
    }

    public String checkpointObjects(Lease lease) {
        return jdbc.queryForObject("SELECT apr_retention_internal.checkpoint_managed_retention_objects(?,?,?)",
                String.class, lease.target().intentId(), lease.generation(), lease.token());
    }

    public ForeignWork claimForeign(Lease lease) {
        return jdbc.query("SELECT * FROM apr_retention_internal.claim_managed_retention_foreign(?,?,?)", result ->
                result.next() ? new ForeignWork(result.getObject("deletion_request_id", UUID.class),
                        result.getLong("tenant_id"), result.getString("consumer_service"),
                        result.getString("recovery_mode")) : null,
                lease.target().intentId(), lease.generation(), lease.token());
    }

    public long finishForeign(Lease lease, UUID deletionRequestId) {
        return jdbc.queryForObject("SELECT apr_retention_internal.finish_managed_retention_foreign(?,?,?,?)",
                Long.class, lease.target().intentId(), lease.generation(), lease.token(), deletionRequestId);
    }

    public long unknownForeign(Lease lease, UUID deletionRequestId, String reason) {
        return jdbc.queryForObject("SELECT apr_retention_internal.unknown_managed_retention_foreign(?,?,?,?,?)",
                Long.class, lease.target().intentId(), lease.generation(), lease.token(), deletionRequestId, reason);
    }

    public String checkpointForeign(Lease lease) {
        return jdbc.queryForObject("SELECT apr_retention_internal.checkpoint_managed_retention_foreign(?,?,?)",
                String.class, lease.target().intentId(), lease.generation(), lease.token());
    }

    public String purgeLocal(Lease lease) {
        return jdbc.queryForObject("SELECT apr_retention_internal.purge_managed_retention_local(?,?,?)",
                String.class, lease.target().intentId(), lease.generation(), lease.token());
    }

    public String finalizeExecution(Lease lease) {
        return jdbc.queryForObject("SELECT apr_retention_internal.finalize_managed_retention_execution(?,?,?)",
                String.class, lease.target().intentId(), lease.generation(), lease.token());
    }

    public Status status(UUID intentId) {
        var rows = jdbc.query("SELECT * FROM apr_retention_internal.managed_retention_execution_status(?)", (result, row) ->
                new Status(result.getString(1), result.getString(2), result.getLong(3), result.getLong(4),
                        result.getString(5), result.getString(6)), intentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
