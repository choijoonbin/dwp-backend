package com.dwp.services.payroll.calculation;

import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Attempt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Lease;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunRevision;

/** JDBC operations for payroll attempts. */
final class PayrollCalculationAttemptJdbc extends PayrollCalculationJdbcSupport {

    PayrollCalculationAttemptJdbc(
            NamedParameterJdbcTemplate jdbc,
            PayrollCalculationCanonical canonical) {
        super(jdbc, canonical);
    }

    public Optional<RunRevision> currentRun(long tenantId, UUID runId) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT tenant_id, run_id, run_revision, run_type, pay_period_id,
                       source_snapshot_id, source_snapshot_revision,
                       source_payload_digest,
                       legal_entity_id, payroll_group_id, population_kind,
                       population_scope_digest, state, maker_actor_id,
                       maker_subject_public_id, latest_attempt_no, worker_count,
                       gross_amount, net_amount, currency, result_digest,
                       approval_case_id, decision_digest, approved_by_actor_id,
                       approved_at, audit_bundle_digest, command_receipt_id, recorded_at
                  FROM pay_payroll_runs
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND (state <> 'READY' OR latest_attempt_no <> 0 OR EXISTS (
                       SELECT 1
                         FROM pay_run_preparations prepared
                         JOIN pay_outbox_events event
                           ON event.tenant_id = prepared.tenant_id
                          AND event.event_id = prepared.event_id
                          AND event.run_id = prepared.run_id
                          AND event.run_revision = prepared.run_revision
                          AND event.event_type = 'PayrollRunPrepared'
                        WHERE prepared.tenant_id = pay_payroll_runs.tenant_id
                          AND prepared.run_id = pay_payroll_runs.run_id
                          AND prepared.run_revision =
                              pay_payroll_runs.run_revision
                          AND prepared.state_sequence = 2
                          AND prepared.preparation_state = 'READY'))
                 ORDER BY run_revision DESC LIMIT 1
                """, params(tenantId, runId), (result, row) -> mapRun(result))
                .stream().findFirst();
    }

    public Optional<AttemptStatus> currentAttemptStatus(
            long tenantId, UUID runId, int attemptNo) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT tenant_id, run_id, attempt_no, status_sequence,
                       attempt_state, failure_code, occurred_at
                  FROM pay_payroll_run_attempts
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                 ORDER BY status_sequence DESC LIMIT 1
                """, params(tenantId, runId).addValue("attemptNo", attemptNo),
                (result, row) -> new AttemptStatus(
                        result.getLong("tenant_id"),
                        result.getObject("run_id", UUID.class),
                        result.getInt("attempt_no"), result.getInt("status_sequence"),
                        AttemptState.valueOf(result.getString("attempt_state")),
                        result.getString("failure_code"),
                        result.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .stream().findFirst();
    }

    public void appendAttempt(Attempt attempt) {
        bindTenant(attempt.tenantId());
        jdbc.update("""
                INSERT INTO pay_payroll_run_attempts (
                    tenant_id, run_id, attempt_no, attempt_id,
                    predecessor_attempt_no, status_sequence, attempt_state,
                    engine_version, command_receipt_id, failure_code, occurred_at)
                VALUES (:tenantId, :runId, :attemptNo, :attemptId,
                    :predecessorAttemptNo, 1, 'READY', :engineVersion,
                    :receiptId, NULL, :occurredAt)
                """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("runId", attempt.runId())
                .addValue("attemptNo", attempt.attemptNo())
                .addValue("attemptId", attempt.attemptId())
                .addValue("predecessorAttemptNo", attempt.predecessorAttemptNo())
                .addValue("engineVersion", attempt.engineVersion())
                .addValue("receiptId", attempt.commandReceiptId())
                .addValue("occurredAt", timestamp(attempt.createdAt())));
    }

    public void appendAttemptStatus(AttemptStatus status) {
        bindTenant(status.tenantId());
        if (status.statusSequence() == 1 && status.state() == AttemptState.READY) {
            AttemptStatus persisted = currentAttemptStatus(
                    status.tenantId(), status.runId(), status.attemptNo())
                    .orElseThrow(() -> notFound("The Payroll attempt is unavailable."));
            if (persisted.statusSequence() != 1 || persisted.state() != AttemptState.READY) {
                throw conflict("The Payroll attempt READY fact differs.", null);
            }
            return;
        }
        int inserted = jdbc.update("""
                INSERT INTO pay_payroll_run_attempts (
                    tenant_id, run_id, attempt_no, attempt_id,
                    predecessor_attempt_no, status_sequence, attempt_state,
                    engine_version, command_receipt_id, failure_code, occurred_at)
                SELECT tenant_id, run_id, attempt_no, attempt_id,
                       predecessor_attempt_no, :sequence, :state,
                       engine_version, command_receipt_id, :failureCode, :occurredAt
                  FROM pay_payroll_run_attempts
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo AND status_sequence = 1
                """, params(status.tenantId(), status.runId())
                .addValue("attemptNo", status.attemptNo())
                .addValue("sequence", status.statusSequence())
                .addValue("state", status.state().name())
                .addValue("failureCode", status.failureCode())
                .addValue("occurredAt", timestamp(status.occurredAt())));
        if (inserted != 1) throw notFound("The Payroll attempt is unavailable.");
    }

    public void appendRunRevision(RunRevision revision) {
        bindTenant(revision.tenantId());
        jdbc.update("""
                INSERT INTO pay_payroll_runs (
                    tenant_id, run_id, run_revision, run_type, pay_period_id,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest,
                    legal_entity_id, payroll_group_id, population_kind,
                    population_scope_digest, state, maker_actor_id,
                    maker_subject_public_id, latest_attempt_no, worker_count,
                    gross_amount, net_amount, currency, result_digest,
                    approval_case_id, decision_digest, approved_by_actor_id,
                    approved_at, audit_bundle_digest, command_receipt_id, recorded_at)
                VALUES (:tenantId, :runId, :runRevision, :runType, :payPeriodId,
                    :sourceSnapshotId, :sourceSnapshotRevision, :sourcePayloadDigest,
                    :legalEntityId, :payrollGroupId, :populationKind,
                    :populationScopeDigest, :state, :makerActorId,
                    :makerSubjectId, :latestAttemptNo, :workerCount,
                    :grossAmount, :netAmount, :currency, :resultDigest,
                    :approvalCaseId, :decisionDigest, :approvedByActorId,
                    :approvedAt, :auditBundleDigest, :receiptId, :recordedAt)
                """, runParameters(revision));
    }

    public Optional<Lease> claim(
            long tenantId,
            UUID runId,
            int attemptNo,
            String workerInstanceId,
            Instant now,
            Duration leaseDuration) {
        lockRun(tenantId, runId);
        UUID attemptId = jdbc.queryForObject("""
                SELECT attempt_id
                  FROM pay_payroll_run_attempts
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                 ORDER BY status_sequence DESC LIMIT 1
                """, params(tenantId, runId).addValue("attemptNo", attemptNo), UUID.class);
        if (attemptId == null) throw notFound("The Payroll attempt is unavailable.");
        Optional<Lease> previous = jdbc.query("""
                SELECT tenant_id, run_id, attempt_no, attempt_id, claim_no, lease_token,
                       worker_instance_id, claimed_at, expires_at
                  FROM pay_payroll_run_workers
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                   AND record_kind IN ('LEASE', 'RENEWAL')
                 ORDER BY claim_no DESC, run_worker_record_internal_id DESC LIMIT 1
                """, params(tenantId, runId).addValue("attemptNo", attemptNo),
                (result, row) -> new Lease(
                        result.getLong("tenant_id"),
                        result.getObject("run_id", UUID.class),
                        result.getInt("attempt_no"),
                        result.getObject("attempt_id", UUID.class),
                        result.getInt("claim_no"),
                        result.getObject("lease_token", UUID.class),
                        result.getString("worker_instance_id"),
                        result.getObject("claimed_at", OffsetDateTime.class).toInstant(),
                        result.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .stream().findFirst();
        if (previous.isPresent() && previous.get().expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        int claimNo = previous.map(value -> value.claimNo() + 1).orElse(1);
        Lease lease = new Lease(
                tenantId, runId, attemptNo, attemptId, claimNo, UUID.randomUUID(),
                workerInstanceId, now, now.plus(leaseDuration));
        jdbc.update("""
                INSERT INTO pay_payroll_run_workers (
                    tenant_id, run_id, attempt_no, attempt_id, record_kind, claim_no,
                    lease_token, worker_instance_id, claimed_at, expires_at,
                    checkpoint_no, next_input_ordinal, completed_worker_count,
                    checkpoint_digest, recorded_at)
                VALUES (:tenantId, :runId, :attemptNo, :attemptId, 'LEASE', :claimNo,
                    :leaseToken, :workerInstanceId, :claimedAt, :expiresAt,
                    NULL, NULL, NULL, NULL, :recordedAt)
                """, leaseParameters(lease).addValue("recordedAt", timestamp(now)));
        return Optional.of(lease);
    }

    public Optional<Lease> renew(Lease lease, Instant now, Duration leaseDuration) {
        lockRun(lease.tenantId(), lease.runId());
        try {
            requireCurrentLease(lease);
        } catch (BaseException exception) {
            return Optional.empty();
        }
        Lease renewed = new Lease(
                lease.tenantId(), lease.runId(), lease.attemptNo(), lease.attemptId(),
                lease.claimNo(), lease.leaseToken(), lease.workerInstanceId(),
                lease.claimedAt(), now.plus(leaseDuration));
        int inserted = jdbc.update("""
                INSERT INTO pay_payroll_run_workers (
                    tenant_id, run_id, attempt_no, attempt_id, record_kind, claim_no,
                    lease_token, worker_instance_id, claimed_at, expires_at,
                    checkpoint_no, next_input_ordinal, completed_worker_count,
                    checkpoint_digest, recorded_at)
                VALUES (:tenantId, :runId, :attemptNo, :attemptId, 'RENEWAL', :claimNo,
                    :leaseToken, :workerInstanceId, :claimedAt, :expiresAt,
                    NULL, NULL, NULL, NULL, :recordedAt)
                """, leaseParameters(renewed).addValue("recordedAt", timestamp(now)));
        return inserted == 1 ? Optional.of(renewed) : Optional.empty();
    }

    public void verifyLease(Lease lease) {
        requireCurrentLease(lease);
    }

    public long outboxCount(long tenantId, UUID runId, String eventType) {
        bindTenant(tenantId);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_outbox_events
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND event_type = :eventType
                """, params(tenantId, runId).addValue("eventType", eventType), Long.class);
        return count == null ? 0 : count;
    }

    @Transactional
    public List<PayrollCalculationStore.ClaimableAttempt> claimable(
            long tenantId,
            int limit,
            String workerInstanceId,
            Instant now,
            Duration leaseDuration) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Payroll claim poll bound is invalid");
        }
        if (workerInstanceId == null || workerInstanceId.isBlank()
                || workerInstanceId.length() > 160
                || now == null
                || leaseDuration == null
                || leaseDuration.compareTo(Duration.ofMillis(100)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Payroll claim lease bounds are invalid");
        }
        return jdbc.query("""
                SELECT tenant_id, run_id, attempt_no, attempt_id, claim_no,
                       lease_token, worker_instance_id, claimed_at, expires_at
                  FROM pay_v50_claim_attempts(
                       :tenantId, :workerInstanceId, :limit, :leaseMillis)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("workerInstanceId", workerInstanceId)
                .addValue("limit", limit)
                .addValue("leaseMillis", leaseDuration.toMillis()),
                (result, row) -> new PayrollCalculationStore.ClaimableAttempt(
                        new Lease(
                                result.getLong("tenant_id"),
                                result.getObject("run_id", UUID.class),
                                result.getInt("attempt_no"),
                                result.getObject("attempt_id", UUID.class),
                                result.getInt("claim_no"),
                                result.getObject("lease_token", UUID.class),
                                result.getString("worker_instance_id"),
                                result.getObject("claimed_at", OffsetDateTime.class).toInstant(),
                                result.getObject("expires_at", OffsetDateTime.class).toInstant())));
    }

    @Transactional(readOnly = true)
    public List<PayrollCalculationStore.RecoveryCandidate> recoveryCandidates(
            long tenantId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Payroll recovery poll bound is invalid");
        }
        return jdbc.query("""
                SELECT tenant_id, run_id
                  FROM pay_v50_recovery_candidates(:tenantId, :limit)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit),
                (result, row) -> new PayrollCalculationStore.RecoveryCandidate(
                        result.getLong("tenant_id"),
                        result.getObject("run_id", UUID.class)));
    }
}
