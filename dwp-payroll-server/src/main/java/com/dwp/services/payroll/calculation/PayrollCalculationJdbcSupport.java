package com.dwp.services.payroll.calculation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Lease;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunRevision;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunType;

/** Shared JDBC context and mappings for the payroll store collaborators. */
class PayrollCalculationJdbcSupport {

    final NamedParameterJdbcTemplate jdbc;

    final PayrollCalculationCanonical canonical;

    PayrollCalculationJdbcSupport(
            NamedParameterJdbcTemplate jdbc,
            PayrollCalculationCanonical canonical) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }

    public void bindTenant(long tenantId) {
        if (tenantId < 1) {
            throw new BaseException(ErrorCode.TENANT_MISSING,
                    "A positive Payroll tenant is required.");
        }
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource("tenantId", Long.toString(tenantId)),
                String.class);
    }

    void requireCurrentLease(Lease lease) {
        bindTenant(lease.tenantId());
        Integer count = jdbc.queryForObject("""
                WITH current_owner AS (
                    SELECT worker.*
                      FROM pay_payroll_run_workers worker
                     WHERE worker.tenant_id = :tenantId AND worker.run_id = :runId
                       AND worker.attempt_no = :attemptNo
                       AND worker.record_kind IN ('LEASE', 'RENEWAL')
                     ORDER BY worker.claim_no DESC,
                              worker.run_worker_record_internal_id DESC LIMIT 1
                ), current_attempt AS (
                    SELECT attempt_id
                      FROM pay_payroll_run_attempts
                     WHERE tenant_id = :tenantId AND run_id = :runId
                       AND attempt_no = :attemptNo
                     ORDER BY status_sequence DESC LIMIT 1
                )
                SELECT COUNT(*)
                  FROM current_owner owner_record
                  JOIN current_attempt attempt ON attempt.attempt_id = owner_record.attempt_id
                 WHERE owner_record.attempt_id = :attemptId
                   AND owner_record.claim_no = :claimNo
                   AND owner_record.lease_token = :leaseToken
                   AND owner_record.worker_instance_id = :workerInstanceId
                   AND owner_record.expires_at > clock_timestamp()
                """, params(lease.tenantId(), lease.runId())
                .addValue("attemptNo", lease.attemptNo())
                .addValue("attemptId", lease.attemptId())
                .addValue("claimNo", lease.claimNo())
                .addValue("workerInstanceId", lease.workerInstanceId())
                .addValue("leaseToken", lease.leaseToken()), Integer.class);
        if (count == null || count != 1) {
            throw conflict("The Payroll worker lease is expired or fenced.", null);
        }
    }

    MapSqlParameterSource leaseParameters(Lease lease) {
        return params(lease.tenantId(), lease.runId())
                .addValue("attemptNo", lease.attemptNo())
                .addValue("attemptId", lease.attemptId())
                .addValue("claimNo", lease.claimNo())
                .addValue("leaseToken", lease.leaseToken())
                .addValue("workerInstanceId", lease.workerInstanceId())
                .addValue("claimedAt", timestamp(lease.claimedAt()))
                .addValue("expiresAt", timestamp(lease.expiresAt()));
    }

    MapSqlParameterSource runParameters(RunRevision revision) {
        return params(revision.tenantId(), revision.runId())
                .addValue("runRevision", revision.revision())
                .addValue("runType", revision.runType().name())
                .addValue("payPeriodId", revision.payPeriodId())
                .addValue("sourceSnapshotId", revision.sourceSnapshotId())
                .addValue("sourceSnapshotRevision", revision.sourceSnapshotRevision())
                .addValue("sourcePayloadDigest", revision.sourcePayloadDigest())
                .addValue("legalEntityId", revision.legalEntityId())
                .addValue("payrollGroupId", revision.payrollGroupId())
                .addValue("populationKind", revision.populationKind())
                .addValue("populationScopeDigest", revision.populationScopeDigest())
                .addValue("state", revision.state().name())
                .addValue("makerActorId", revision.makerActorId())
                .addValue("makerSubjectId", revision.makerSubjectPublicId())
                .addValue("latestAttemptNo", revision.latestAttemptNo())
                .addValue("workerCount", revision.workerCount())
                .addValue("grossAmount", revision.gross())
                .addValue("netAmount", revision.net())
                .addValue("currency", revision.currency())
                .addValue("resultDigest", revision.resultDigest())
                .addValue("approvalCaseId", revision.approvalCaseId())
                .addValue("decisionDigest", revision.decisionDigest())
                .addValue("approvedByActorId", revision.approvedByActorId())
                .addValue("approvedAt", revision.approvedAt() == null
                        ? null : timestamp(revision.approvedAt()))
                .addValue("auditBundleDigest", revision.auditBundleDigest())
                .addValue("receiptId", revision.commandReceiptId())
                .addValue("recordedAt", timestamp(revision.recordedAt()));
    }

    RunRevision mapRun(java.sql.ResultSet result) throws java.sql.SQLException {
        OffsetDateTime approved = result.getObject("approved_at", OffsetDateTime.class);
        return new RunRevision(
                result.getLong("tenant_id"), result.getObject("run_id", UUID.class),
                result.getLong("run_revision"),
                RunType.valueOf(result.getString("run_type")),
                result.getObject("pay_period_id", UUID.class),
                result.getObject("source_snapshot_id", UUID.class),
                result.getLong("source_snapshot_revision"),
                result.getString("source_payload_digest"),
                result.getObject("legal_entity_id", UUID.class),
                result.getObject("payroll_group_id", UUID.class),
                result.getString("population_kind"),
                result.getString("population_scope_digest"),
                RunState.valueOf(result.getString("state")),
                result.getLong("maker_actor_id"),
                result.getObject("maker_subject_public_id", UUID.class),
                result.getInt("latest_attempt_no"), result.getInt("worker_count"),
                result.getBigDecimal("gross_amount"), result.getBigDecimal("net_amount"),
                result.getString("currency"), result.getString("result_digest"),
                result.getObject("approval_case_id", UUID.class),
                result.getString("decision_digest"),
                result.getObject("approved_by_actor_id", Long.class),
                approved == null ? null : approved.toInstant(),
                result.getString("audit_bundle_digest"),
                result.getObject("command_receipt_id", UUID.class),
                result.getObject("recorded_at", OffsetDateTime.class).toInstant());
    }

    MapSqlParameterSource params(long tenantId, UUID runId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("runId", runId);
    }

    Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

    BaseException conflict(String message, RuntimeException cause) {
        return cause == null ? new BaseException(ErrorCode.RESOURCE_CONFLICT, message)
                : new BaseException(ErrorCode.RESOURCE_CONFLICT, message, cause);
    }

    record DurableLineHeader(
            int inputOrdinal,
            UUID resultLineId,
            long sourceInputId,
            UUID workerId,
            UUID workerResultId,
            String componentCode,
            String sourcePayloadDigest,
            String semanticBindingDigest,
            UUID elementId,
            long elementVersion,
            UUID formulaId,
            long formulaVersion,
            String formulaAstDigest,
            String category,
            String currency,
            java.math.BigDecimal amount,
            String formulaInputDigest,
            String formulaOutputDigest,
            UUID terminalTraceId,
            String traceDigest,
            String lineDigest) {
    }

    record DurableLine(
            int inputOrdinal,
            UUID resultLineId,
            long sourceInputId,
            UUID workerId,
            UUID workerResultId,
            String componentCode,
            String sourcePayloadDigest,
            String semanticBindingDigest,
            UUID elementId,
            long elementVersion,
            UUID formulaId,
            long formulaVersion,
            String formulaAstDigest,
            String category,
            String currency,
            java.math.BigDecimal amount,
            String formulaInputDigest,
            String formulaOutputDigest,
            UUID terminalTraceId,
            String traceDigest,
            String lineDigest,
            List<DurableTraceNode> traces) {
    }

    record DurableTraceNode(
            UUID traceId,
            int sequence,
            UUID parentTraceId,
            String semanticBindingDigest,
            UUID elementId,
            long elementVersion,
            UUID formulaId,
            Long formulaVersion,
            String formulaAstDigest,
            String category,
            String sourcePayloadDigest,
            String inputDigest,
            String outputDigest,
            java.math.BigDecimal unroundedAmount,
            java.math.BigDecimal roundedAmount,
            String currency,
            String roundingStage,
            int roundingScale,
            String roundingMode,
            java.math.BigDecimal roundingIncrement,
            String roundingPolicyDigest,
            String traceDigest,
            String engineVersion) {
    }

    public void lockRun(long tenantId, UUID runId) {
        bindTenant(tenantId);
        jdbc.queryForObject(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock("
                        + "hashtextextended(:lockKey, 0))) locked",
                new MapSqlParameterSource(
                        "lockKey", tenantId + ":PAY011:" + runId),
                Integer.class);
    }
}
