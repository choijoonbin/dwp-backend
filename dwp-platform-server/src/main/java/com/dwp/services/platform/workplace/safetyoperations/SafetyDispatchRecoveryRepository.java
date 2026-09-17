package com.dwp.services.platform.workplace.safetyoperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

@Repository
class SafetyDispatchRecoveryRepository extends SafetyRepositorySupport {
    private final SafetyIncidentRepository incidents;

    SafetyDispatchRecoveryRepository(
            JdbcTemplate jdbc, ObjectMapper mapper, SafetyIncidentRepository incidents) {
        super(jdbc, mapper);
        this.incidents = incidents;
    }

    List<RecoveryWorkRow> pending(int limit, OffsetDateTime now) {
        return jdbc.query("""
                SELECT a.dispatch_attempt_id,a.tenant_id,a.channel,
                       a.provider_operation_reference,b.incident_id,a.provider_code,
                       a.provider_configuration_version,a.provider_credential_reference
                  FROM wp_safety_dispatch_attempts a
                  JOIN wp_safety_dispatch_batches b
                    ON b.tenant_id=a.tenant_id AND b.dispatch_batch_id=a.dispatch_batch_id
                 WHERE a.attempt_state='RESULT_UNKNOWN'
                   AND a.reconcile_attempt_count<5
                   AND (a.next_reconcile_at IS NULL OR a.next_reconcile_at<=?)
                 ORDER BY a.updated_at,a.dispatch_attempt_id LIMIT ?
                """, (rs, row) -> new RecoveryWorkRow(
                rs.getObject("dispatch_attempt_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("incident_id", UUID.class),
                DeliveryChannel.valueOf(rs.getString("channel")),
                rs.getString("provider_operation_reference"),
                providerContext(rs)), now, limit);
    }

    /**
     * Converts an abandoned provider call into lookup-only recovery. A DISPATCHING row may have
     * reached the provider before this process stopped, so it must never return to the mutation
     * queue. The stable attempt id remains the authoritative provider lookup key.
     */
    int recoverStaleDispatches(int limit, OffsetDateTime staleBefore, OffsetDateTime now) {
        return jdbc.update("""
                WITH stale AS (
                    SELECT tenant_id,dispatch_attempt_id
                      FROM wp_safety_dispatch_attempts
                     WHERE attempt_state='DISPATCHING'
                       AND COALESCE(dispatch_started_at,updated_at)<=?
                     ORDER BY COALESCE(dispatch_started_at,updated_at),dispatch_attempt_id
                     LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE wp_safety_dispatch_attempts attempt
                   SET attempt_state='RESULT_UNKNOWN',
                       result_code='PROVIDER_PROCESS_RESTARTED',
                       next_reconcile_at=?,dispatch_started_at=NULL,updated_at=?
                  FROM stale
                 WHERE attempt.tenant_id=stale.tenant_id
                   AND attempt.dispatch_attempt_id=stale.dispatch_attempt_id
                   AND attempt.attempt_state='DISPATCHING'
                """, staleBefore, limit, now, now);
    }

    boolean claim(RecoveryWorkRow work, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_safety_dispatch_attempts
                   SET reconcile_attempt_count=reconcile_attempt_count+1,
                       next_reconcile_at=? + INTERVAL '30 seconds',updated_at=?
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                   AND attempt_state='RESULT_UNKNOWN'
                   AND reconcile_attempt_count<5
                   AND (next_reconcile_at IS NULL OR next_reconcile_at<=?)
                """, now, now, work.tenantId(), work.attemptId(), now) == 1;
    }

    boolean deferUnavailable(RecoveryWorkRow work, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_safety_dispatch_attempts
                   SET result_code='STATUS_LOOKUP_UNAVAILABLE',
                       next_reconcile_at=? + INTERVAL '5 minutes',updated_at=?
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                   AND attempt_state='RESULT_UNKNOWN'
                """, now, now, work.tenantId(), work.attemptId()) == 1;
    }

    boolean record(RecoveryWorkRow work, SafetyDispatchProvider.DispatchResult result,
                   OffsetDateTime now) {
        if (result.state() != AttemptState.DELIVERED
                && result.state() != AttemptState.DELIVERY_FAILED
                && result.state() != AttemptState.RESULT_UNKNOWN) return false;
        AttemptLink link = link(work.tenantId(), work.attemptId());
        int changed = jdbc.update("""
                UPDATE wp_safety_dispatch_attempts
                   SET attempt_state=?,result_code=?,
                       provider_operation_reference=COALESCE(?,provider_operation_reference),
                       next_reconcile_at=CASE WHEN ?='RESULT_UNKNOWN'
                         THEN ? + INTERVAL '30 seconds' ELSE NULL END,updated_at=?
                 WHERE tenant_id=? AND dispatch_attempt_id=? AND attempt_state='RESULT_UNKNOWN'
                """, result.state().name(), result.resultCode(),
                result.providerOperationReference(), result.state().name(), now, now,
                work.tenantId(), work.attemptId());
        if (changed == 0) return false;
        jdbc.update("""
                INSERT INTO wp_safety_dispatch_receipts(
                    dispatch_receipt_id,tenant_id,dispatch_attempt_id,receipt_state,
                    evidence_reference,source_at,received_at)
                VALUES(?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id,dispatch_attempt_id) DO UPDATE SET
                    receipt_state=EXCLUDED.receipt_state,
                    evidence_reference=EXCLUDED.evidence_reference,
                    source_at=EXCLUDED.source_at,received_at=EXCLUDED.received_at
                """, UUID.randomUUID(), work.tenantId(), work.attemptId(),
                result.state().name(), result.evidenceReference(), now, now);
        if (result.state() != AttemptState.RESULT_UNKNOWN) {
            incidents.refreshBatch(work.tenantId(), link.batchId(), now);
        }
        return true;
    }

    private AttemptLink link(long tenantId, UUID attemptId) {
        return jdbc.queryForObject("""
                SELECT dispatch_batch_id FROM wp_safety_dispatch_attempts
                 WHERE tenant_id=? AND dispatch_attempt_id=?
                """, (rs, row) -> new AttemptLink(rs.getObject(1, UUID.class)),
                tenantId, attemptId);
    }

    record RecoveryWorkRow(UUID attemptId, long tenantId, UUID incidentId,
                           DeliveryChannel channel, String providerOperationReference,
                           SafetyDispatchProvider.ProviderContext providerContext) {
        RecoveryWorkRow(UUID attemptId, long tenantId, UUID incidentId,
                        DeliveryChannel channel, String providerOperationReference) {
            this(attemptId, tenantId, incidentId, channel, providerOperationReference, null);
        }
    }
    private record AttemptLink(UUID batchId) { }

    private static SafetyDispatchProvider.ProviderContext providerContext(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        String provider = rs.getString("provider_code");
        if (provider == null) return null;
        return new SafetyDispatchProvider.ProviderContext(
                DeliveryChannel.valueOf(rs.getString("channel")), provider,
                rs.getLong("provider_configuration_version"),
                rs.getString("provider_credential_reference"));
    }
}
