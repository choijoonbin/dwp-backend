package com.dwp.services.payroll.calculation;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CalculatedLine;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CalculationOutcome;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Checkpoint;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.EventFact;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Lease;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PersistedReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ReceiptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunRevision;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.WorkerResult;

/** JDBC operations for payroll progress. */
final class PayrollCalculationProgressJdbc extends PayrollCalculationJdbcSupport {

    private final PayrollCalculationResultJdbc results;
    private final PayrollCalculationAttemptJdbc attempts;
    private final PayrollCalculationReceiptJdbc receipts;

    PayrollCalculationProgressJdbc(
            NamedParameterJdbcTemplate jdbc,
            PayrollCalculationCanonical canonical,
            PayrollCalculationResultJdbc results,
            PayrollCalculationAttemptJdbc attempts,
            PayrollCalculationReceiptJdbc receipts) {
        super(jdbc, canonical);
        this.results = results;
        this.attempts = attempts;
        this.receipts = receipts;
    }

    public Optional<Checkpoint> latestCheckpoint(long tenantId, UUID runId, int attemptNo) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT checkpoint_no, next_input_ordinal,
                       completed_worker_count, checkpoint_digest
                  FROM pay_payroll_run_workers
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo AND record_kind = 'CHECKPOINT'
                 ORDER BY checkpoint_no DESC LIMIT 1
                """, params(tenantId, runId).addValue("attemptNo", attemptNo),
                (result, row) -> new Checkpoint(
                        result.getInt("checkpoint_no"),
                        result.getInt("next_input_ordinal"),
                        result.getInt("completed_worker_count"),
                        result.getString("checkpoint_digest")))
                .stream().findFirst();
    }

    public void verifyCheckpoint(
            Lease lease, Checkpoint checkpoint, CalculationOutcome outcome) {
        lockRun(lease.tenantId(), lease.runId());
        requireCurrentLease(lease);
        int prefixSize = checkpoint.nextInputOrdinal() - 1;
        if (prefixSize < 1 || prefixSize > outcome.lines().size()) {
            throw conflict("The Payroll checkpoint prefix is out of bounds.", null);
        }
        List<DurableLine> durable = results.durableLines(lease);
        if (durable.size() != prefixSize) {
            throw conflict("The Payroll checkpoint durable prefix size changed.", null);
        }
        results.verifyLines(durable, outcome.lines().subList(0, prefixSize));
        int completedWorkers = (int) durable.stream().map(DurableLine::workerId)
                .distinct().count();
        String digest = canonical.checkpointDigest(
                lease.runId(), lease.attemptNo(), checkpoint.checkpointNo(),
                checkpoint.nextInputOrdinal(), durable.stream()
                .map(DurableLine::lineDigest).toList());
        if (completedWorkers != checkpoint.completedWorkerCount()
                || !digest.equals(checkpoint.checkpointDigest())) {
            throw conflict("The Payroll checkpoint digest does not match durable facts.", null);
        }
    }

    public void appendProgress(
            Lease lease,
            CalculationOutcome outcome,
            int fromInputOrdinal,
            int toInputOrdinal,
            Checkpoint checkpoint,
            Instant recordedAt) {
        lockRun(lease.tenantId(), lease.runId());
        requireCurrentLease(lease);
        if (fromInputOrdinal < 1 || toInputOrdinal < fromInputOrdinal
                || toInputOrdinal > outcome.lines().size()
                || checkpoint.nextInputOrdinal() != toInputOrdinal + 1) {
            throw invalid("Payroll worker checkpoint bounds are invalid.");
        }
        for (int index = fromInputOrdinal - 1; index < toInputOrdinal; index++) {
            CalculatedLine line = outcome.lines().get(index);
            boolean insertedEveryTrace = true;
            for (PayrollCalculationModels.CalculatedTraceNode trace : line.traceNodes()) {
                int traceInserted = jdbc.update("""
                        INSERT INTO pay_calculation_trace_nodes (
                            tenant_id, run_id, attempt_no,
                            input_snapshot_internal_id, trace_id, trace_sequence,
                            parent_trace_id, semantic_binding_digest,
                            worker_id, compensation_plan_input_id,
                            element_id, element_version, formula_id, formula_version,
                            formula_ast_digest, formula_input_digest,
                            formula_output_digest, line_category,
                            source_payload_digest, unrounded_output_amount,
                            rounded_output_amount, currency, rounding_stage,
                            rounding_scale, rounding_mode, rounding_increment,
                            rounding_policy_digest, trace_digest, engine_version,
                            recorded_at)
                        SELECT :tenantId, :runId, :attemptNo,
                               snapshot.input_snapshot_internal_id,
                               :traceId, :traceSequence, :parentTraceId,
                               :semanticBindingDigest,
                               :workerId, :sourceInputId, :elementId,
                               :elementVersion, :formulaId, :formulaVersion,
                               :formulaAstDigest, :formulaInputDigest,
                               :formulaOutputDigest, :lineCategory,
                               :sourcePayloadDigest, :unroundedAmount,
                               :roundedAmount, :currency, :roundingStage,
                               :roundingScale, :roundingMode, :roundingIncrement,
                               :roundingPolicyDigest, :nodeTraceDigest,
                               :engineVersion, :recordedAt
                          FROM pay_input_snapshots snapshot
                          JOIN pay_input_snapshot_compensation_refs ref
                            ON ref.tenant_id = snapshot.tenant_id
                           AND ref.input_snapshot_internal_id =
                               snapshot.input_snapshot_internal_id
                           AND ref.compensation_plan_input_id = :sourceInputId
                           AND ref.semantic_binding_digest = :semanticBindingDigest
                         WHERE snapshot.tenant_id = :tenantId
                           AND snapshot.run_id = :runId
                           AND snapshot.attempt_no = :attemptNo
                        ON CONFLICT (tenant_id, run_id, attempt_no,
                            compensation_plan_input_id, trace_sequence) DO NOTHING
                        """, results.traceParameters(lease, line, trace, recordedAt));
                insertedEveryTrace &= traceInserted == 1;
            }
            int lineInserted = jdbc.update("""
                    INSERT INTO pay_result_lines (
                        tenant_id, run_id, attempt_no,
                        input_snapshot_internal_id, result_line_id,
                        compensation_plan_input_id, worker_id, component_code,
                        worker_result_id,
                        source_payload_digest, semantic_binding_digest,
                        element_id, element_version,
                        formula_id, formula_version,
                        formula_ast_digest, line_category,
                        formula_input_digest, formula_output_digest, currency, amount,
                        terminal_trace_id, trace_digest, result_line_digest, recorded_at)
                    SELECT :tenantId, :runId, :attemptNo,
                           snapshot.input_snapshot_internal_id, :resultLineId,
                           :sourceInputId, :workerId, :componentCode,
                           :workerResultId,
                           :sourcePayloadDigest, :semanticBindingDigest,
                           :elementId, :elementVersion,
                           :formulaId, :formulaVersion,
                           :formulaAstDigest, :lineCategory,
                           :formulaInputDigest, :formulaOutputDigest, :currency, :amount,
                           :terminalTraceId, :traceDigest, :lineDigest, :recordedAt
                      FROM pay_input_snapshots snapshot
                      JOIN pay_input_snapshot_compensation_refs ref
                        ON ref.tenant_id = snapshot.tenant_id
                       AND ref.input_snapshot_internal_id =
                           snapshot.input_snapshot_internal_id
                       AND ref.compensation_plan_input_id = :sourceInputId
                       AND ref.semantic_binding_digest = :semanticBindingDigest
                     WHERE snapshot.tenant_id = :tenantId
                       AND snapshot.run_id = :runId
                       AND snapshot.attempt_no = :attemptNo
                    ON CONFLICT (tenant_id, run_id, attempt_no,
                        compensation_plan_input_id) DO NOTHING
                    """, results.lineParameters(lease, line, recordedAt));
            if (lineInserted == 0 || !insertedEveryTrace) {
                results.verifyExistingLine(lease, line);
            }
        }
        jdbc.update("""
                INSERT INTO pay_payroll_run_workers (
                    tenant_id, run_id, attempt_no, attempt_id, record_kind, claim_no,
                    lease_token, worker_instance_id, claimed_at, expires_at,
                    checkpoint_no, next_input_ordinal, completed_worker_count,
                    checkpoint_digest, recorded_at)
                VALUES (:tenantId, :runId, :attemptNo, :attemptId, 'CHECKPOINT', :claimNo,
                    :leaseToken, :workerInstanceId, :claimedAt, :expiresAt,
                    :checkpointNo, :nextOrdinal, :workerCount, :digest, :recordedAt)
                """, leaseParameters(lease)
                .addValue("checkpointNo", checkpoint.checkpointNo())
                .addValue("nextOrdinal", checkpoint.nextInputOrdinal())
                .addValue("workerCount", checkpoint.completedWorkerCount())
                .addValue("digest", checkpoint.checkpointDigest())
                .addValue("recordedAt", timestamp(recordedAt)));
    }

    public void complete(
            Lease lease,
            CalculationOutcome outcome,
            RunRevision completedRun,
            UUID receiptId,
            EventFact event,
            String auditPayload,
            Instant completedAt) {
        lockRun(lease.tenantId(), lease.runId());
        requireCurrentLease(lease);
        Integer lineCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_result_lines
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                """, params(lease.tenantId(), lease.runId())
                .addValue("attemptNo", lease.attemptNo()), Integer.class);
        if (lineCount == null || lineCount != outcome.lines().size()) {
            throw conflict("Payroll calculation cannot complete before all durable lines.", null);
        }
        results.verifyLines(results.durableLines(lease), outcome.lines());
        for (WorkerResult worker : outcome.workers()) {
            jdbc.update("""
                    INSERT INTO pay_worker_results (
                        tenant_id, run_id, attempt_no, worker_result_id,
                        worker_id, currency,
                        gross_amount, deduction_amount, net_amount,
                        employer_cost_amount, line_count,
                        worker_result_digest, calculation_result_digest, recorded_at)
                    VALUES (:tenantId, :runId, :attemptNo, :workerResultId,
                        :workerId, :currency,
                        :gross, :deductions, :net, :employerCost, :lineCount,
                        :digest, :calculationResultDigest, :recordedAt)
                    ON CONFLICT (tenant_id, run_id, attempt_no, worker_id) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", lease.tenantId()).addValue("runId", lease.runId())
                    .addValue("attemptNo", lease.attemptNo())
                    .addValue("workerResultId", worker.workerResultId())
                    .addValue("workerId", worker.workerId())
                    .addValue("currency", worker.currency())
                    .addValue("gross", worker.gross())
                    .addValue("deductions", worker.deductions())
                    .addValue("net", worker.net())
                    .addValue("employerCost", worker.employerCost())
                    .addValue("lineCount", worker.lineCount())
                    .addValue("digest", worker.workerResultDigest())
                    .addValue("calculationResultDigest", outcome.resultDigest())
                    .addValue("recordedAt", timestamp(completedAt)));
        }
        results.verifyWorkers(lease.tenantId(), lease.runId(), lease.attemptNo(),
                outcome.workers(), outcome.resultDigest());
        AttemptStatus status = attempts.currentAttemptStatus(
                lease.tenantId(), lease.runId(), lease.attemptNo()).orElseThrow();
        attempts.appendAttemptStatus(new AttemptStatus(
                lease.tenantId(), lease.runId(), lease.attemptNo(),
                status.statusSequence() + 1, AttemptState.VALIDATED, null, completedAt));
        receipts.appendReceiptStatus(lease.tenantId(), receiptId, ReceiptStatus.SUCCEEDED, completedAt);
        attempts.appendRunRevision(completedRun);
        PersistedReceipt receipt = receipts.receipt(lease.tenantId(), receiptId).orElseThrow();
        receipts.appendAudit(receipt, completedRun.revision(),
                "PAYROLL_CALCULATION_COMPLETED", auditPayload, completedAt);
        receipts.appendEvent(lease.tenantId(), event);
    }

    public void terminalFailure(
            Lease lease,
            UUID receiptId,
            AttemptState state,
            String failureCode,
            RunRevision failedRun,
            String auditPayload,
            Instant occurredAt) {
        lockRun(lease.tenantId(), lease.runId());
        requireCurrentLease(lease);
        if (!List.of(AttemptState.FAILED, AttemptState.RESULT_UNKNOWN).contains(state)) {
            throw new IllegalArgumentException("terminal worker state is invalid");
        }
        RunRevision run = attempts.currentRun(lease.tenantId(), lease.runId()).orElseThrow();
        if (run.latestAttemptNo() != lease.attemptNo()
                || !List.of(RunState.READY, RunState.CALCULATING).contains(run.state())) {
            throw conflict("The Payroll worker was fenced before terminalization.", null);
        }
        AttemptStatus current = attempts.currentAttemptStatus(
                lease.tenantId(), lease.runId(), lease.attemptNo())
                .orElseThrow();
        attempts.appendAttemptStatus(new AttemptStatus(
                lease.tenantId(), lease.runId(), lease.attemptNo(),
                current.statusSequence() + 1,
                state, failureCode, occurredAt));
        receipts.appendReceiptStatus(lease.tenantId(), receiptId,
                state == AttemptState.RESULT_UNKNOWN
                        ? ReceiptStatus.RESULT_UNKNOWN : ReceiptStatus.FAILED,
                occurredAt);
        attempts.appendRunRevision(failedRun);
        PersistedReceipt receipt = receipts.receipt(lease.tenantId(), receiptId).orElseThrow();
        receipts.appendAudit(receipt, failedRun.revision(),
                state == AttemptState.RESULT_UNKNOWN
                        ? "PAYROLL_RESULT_UNKNOWN" : "PAYROLL_CALCULATION_FAILED",
                auditPayload, occurredAt);
    }

    public boolean completionExists(
            long tenantId, UUID runId, int attemptNo, CalculationOutcome outcome) {
        bindTenant(tenantId);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pay_payroll_runs run
                  JOIN pay_outbox_events event
                    ON event.tenant_id = run.tenant_id
                   AND event.run_id = run.run_id
                   AND event.run_revision = run.run_revision
                 WHERE run.tenant_id = :tenantId AND run.run_id = :runId
                   AND event.attempt_no = :attemptNo
                   AND run.state = 'VALIDATED' AND run.result_digest = :resultDigest
                   AND event.event_type = 'PayrollCalculated'
                   AND event.event_payload -> 'data' ->> 'resultDigest' = :resultDigest
                   AND (event.event_payload -> 'data' ->> 'workerCount')::INTEGER
                       = :workerCount
                   AND event.event_payload -> 'data' -> 'controlTotals'
                       = CAST(:controlTotals AS jsonb)
                """, params(tenantId, runId)
                .addValue("attemptNo", attemptNo)
                .addValue("resultDigest", outcome.resultDigest())
                .addValue("workerCount", outcome.workers().size())
                .addValue("controlTotals", canonical.json(outcome.controlTotals().stream()
                        .map(total -> Map.of(
                                "code", total.code(),
                                "amount", total.amount().toPlainString(),
                                "currency", total.currency()))
                        .toList())), Integer.class);
        if (count == null || count == 0) return false;
        if (count != 1) {
            throw conflict("Payroll completion evidence is ambiguous.", null);
        }
        results.verifyLines(results.durableLines(tenantId, runId, attemptNo), outcome.lines());
        results.verifyWorkers(tenantId, runId, attemptNo, outcome.workers(),
                outcome.resultDigest());
        return true;
    }
}
