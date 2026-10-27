package com.dwp.services.payroll.calculation;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CalculatedLine;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Lease;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.WorkerResult;

/** JDBC operations for payroll results. */
final class PayrollCalculationResultJdbc extends PayrollCalculationJdbcSupport {

    PayrollCalculationResultJdbc(
            NamedParameterJdbcTemplate jdbc,
            PayrollCalculationCanonical canonical) {
        super(jdbc, canonical);
    }

    void verifyExistingLine(Lease lease, CalculatedLine line) {
        DurableLine existing = durableLines(lease).stream()
                .filter(value -> value.sourceInputId() == line.sourceInputId())
                .findFirst().orElse(null);
        if (existing == null || !lineMatches(existing, line)) {
            throw conflict("A recovered Payroll result differs from its durable fact.", null);
        }
    }

    List<DurableLine> durableLines(Lease lease) {
        return durableLines(lease.tenantId(), lease.runId(), lease.attemptNo());
    }

    List<DurableLine> durableLines(
            long tenantId, UUID runId, int attemptNo) {
        List<DurableLineHeader> headers = jdbc.query("""
                SELECT ref.input_ordinal, result.result_line_id,
                       result.compensation_plan_input_id, result.worker_id,
                       result.worker_result_id, result.component_code,
                       result.source_payload_digest,
                       result.semantic_binding_digest,
                       result.element_id,
                       result.element_version, result.formula_id,
                       result.formula_version, result.formula_ast_digest,
                       result.line_category, result.currency, result.amount,
                       result.formula_input_digest, result.formula_output_digest,
                       result.terminal_trace_id, result.trace_digest,
                       result.result_line_digest
                  FROM pay_input_snapshots snapshot
                  JOIN pay_input_snapshot_compensation_refs ref
                    ON ref.tenant_id = snapshot.tenant_id
                   AND ref.input_snapshot_internal_id = snapshot.input_snapshot_internal_id
                  JOIN pay_result_lines result
                    ON result.tenant_id = snapshot.tenant_id
                   AND result.input_snapshot_internal_id =
                       snapshot.input_snapshot_internal_id
                   AND result.compensation_plan_input_id = ref.compensation_plan_input_id
                 WHERE snapshot.tenant_id = :tenantId
                   AND snapshot.run_id = :runId
                   AND snapshot.attempt_no = :attemptNo
                 ORDER BY ref.input_ordinal
                """, params(tenantId, runId).addValue("attemptNo", attemptNo),
                (result, row) -> new DurableLineHeader(
                        result.getInt("input_ordinal"),
                        result.getObject("result_line_id", UUID.class),
                        result.getLong("compensation_plan_input_id"),
                        result.getObject("worker_id", UUID.class),
                        result.getObject("worker_result_id", UUID.class),
                        result.getString("component_code"),
                        result.getString("source_payload_digest"),
                        result.getString("semantic_binding_digest"),
                        result.getObject("element_id", UUID.class),
                        result.getLong("element_version"),
                        result.getObject("formula_id", UUID.class),
                        result.getLong("formula_version"),
                        result.getString("formula_ast_digest"),
                        result.getString("line_category"),
                        result.getString("currency"),
                        result.getBigDecimal("amount"),
                        result.getString("formula_input_digest"),
                        result.getString("formula_output_digest"),
                        result.getObject("terminal_trace_id", UUID.class),
                        result.getString("trace_digest"),
                        result.getString("result_line_digest")));
        Map<Long, List<DurableTraceNode>> traces = new LinkedHashMap<>();
        jdbc.query("""
                SELECT compensation_plan_input_id, trace_id, trace_sequence,
                       parent_trace_id, semantic_binding_digest,
                       element_id, element_version,
                       formula_id, formula_version, formula_ast_digest,
                       line_category, source_payload_digest,
                       formula_input_digest, formula_output_digest,
                       unrounded_output_amount, rounded_output_amount, currency,
                       rounding_stage, rounding_scale, rounding_mode,
                       rounding_increment, rounding_policy_digest,
                       trace_digest, engine_version
                  FROM pay_calculation_trace_nodes
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                 ORDER BY compensation_plan_input_id, trace_sequence
                """, params(tenantId, runId).addValue("attemptNo", attemptNo),
                (org.springframework.jdbc.core.RowCallbackHandler) result ->
                        traces.computeIfAbsent(
                        result.getLong("compensation_plan_input_id"),
                        ignored -> new ArrayList<>()).add(new DurableTraceNode(
                        result.getObject("trace_id", UUID.class),
                        result.getInt("trace_sequence"),
                        result.getObject("parent_trace_id", UUID.class),
                        result.getString("semantic_binding_digest"),
                        result.getObject("element_id", UUID.class),
                        result.getLong("element_version"),
                        result.getObject("formula_id", UUID.class),
                        result.getObject("formula_version", Long.class),
                        result.getString("formula_ast_digest"),
                        result.getString("line_category"),
                        result.getString("source_payload_digest"),
                        result.getString("formula_input_digest"),
                        result.getString("formula_output_digest"),
                        result.getBigDecimal("unrounded_output_amount"),
                        result.getBigDecimal("rounded_output_amount"),
                        result.getString("currency"),
                        result.getString("rounding_stage"),
                        result.getInt("rounding_scale"),
                        result.getString("rounding_mode"),
                        result.getBigDecimal("rounding_increment"),
                        result.getString("rounding_policy_digest"),
                        result.getString("trace_digest"),
                        result.getString("engine_version"))));
        return headers.stream().map(header -> new DurableLine(
                header.inputOrdinal(), header.resultLineId(), header.sourceInputId(),
                header.workerId(), header.workerResultId(), header.componentCode(),
                header.sourcePayloadDigest(), header.semanticBindingDigest(),
                header.elementId(),
                header.elementVersion(), header.formulaId(), header.formulaVersion(),
                header.formulaAstDigest(), header.category(), header.currency(),
                header.amount(), header.formulaInputDigest(),
                header.formulaOutputDigest(), header.terminalTraceId(),
                header.traceDigest(), header.lineDigest(),
                List.copyOf(traces.getOrDefault(
                        header.sourceInputId(), List.of())))).toList();
    }

    void verifyLines(
            List<DurableLine> durable, List<CalculatedLine> expected) {
        if (durable.size() != expected.size()) {
            throw conflict("Payroll durable result line count changed.", null);
        }
        for (int index = 0; index < expected.size(); index++) {
            DurableLine actual = durable.get(index);
            CalculatedLine line = expected.get(index);
            if (actual.inputOrdinal() != index + 1 || !lineMatches(actual, line)) {
                throw conflict(
                        "A durable Payroll result or trace differs from recomputation.", null);
            }
        }
    }

    boolean lineMatches(DurableLine actual, CalculatedLine line) {
        if (!actual.resultLineId().equals(line.resultLineId())
                || actual.sourceInputId() != line.sourceInputId()
                || !actual.workerId().equals(line.workerId())
                || !actual.workerResultId().equals(line.workerResultId())
                || !actual.componentCode().equals(line.componentCode())
                || !actual.sourcePayloadDigest().equals(line.sourcePayloadDigest())
                || !actual.semanticBindingDigest().equals(
                line.semanticBindingDigest())
                || !actual.elementId().equals(line.elementId())
                || actual.elementVersion() != line.elementVersion()
                || !actual.formulaId().equals(line.formulaId())
                || actual.formulaVersion() != line.formulaVersion()
                || !actual.formulaAstDigest().equals(line.formulaAstDigest())
                || !actual.category().equals(line.category().name())
                || !actual.currency().equals(line.currency())
                || actual.amount().compareTo(line.amount()) != 0
                || !actual.formulaInputDigest().equals(line.formulaInputDigest())
                || !actual.formulaOutputDigest().equals(line.formulaOutputDigest())
                || !actual.terminalTraceId().equals(line.terminalTraceId())
                || !actual.lineDigest().equals(line.resultLineDigest())
                || !actual.traceDigest().equals(line.traceDigest())
                || actual.traces().size() != line.traceNodes().size()) {
            return false;
        }
        for (int index = 0; index < line.traceNodes().size(); index++) {
            if (!traceMatches(
                    actual.traces().get(index), line.traceNodes().get(index), line)) {
                return false;
            }
        }
        return true;
    }

    boolean traceMatches(
            DurableTraceNode actual,
            PayrollCalculationModels.CalculatedTraceNode expected,
            CalculatedLine line) {
        return actual.traceId().equals(expected.traceId())
                && actual.sequence() == expected.sequence()
                && java.util.Objects.equals(
                actual.parentTraceId(), expected.parentTraceId())
                && actual.semanticBindingDigest().equals(
                expected.semanticBindingDigest())
                && actual.elementId().equals(line.elementId())
                && actual.elementVersion() == line.elementVersion()
                && java.util.Objects.equals(actual.formulaId(), expected.formulaId())
                && java.util.Objects.equals(
                actual.formulaVersion(), expected.formulaVersion())
                && java.util.Objects.equals(
                actual.formulaAstDigest(), expected.formulaAstDigest())
                && actual.category().equals(line.category().name())
                && actual.sourcePayloadDigest().equals(line.sourcePayloadDigest())
                && actual.inputDigest().equals(expected.inputDigest())
                && actual.outputDigest().equals(expected.outputDigest())
                && actual.unroundedAmount().compareTo(expected.unroundedAmount()) == 0
                && actual.roundedAmount().compareTo(expected.roundedAmount()) == 0
                && actual.currency().equals(expected.currency())
                && actual.roundingStage().equals(expected.stage().name())
                && actual.roundingScale() == expected.roundingScale()
                && actual.roundingMode().equals(expected.roundingMode().name())
                && actual.roundingIncrement().compareTo(
                expected.roundingIncrement()) == 0
                && actual.roundingPolicyDigest().equals(
                expected.roundingPolicyDigest())
                && actual.traceDigest().equals(expected.traceDigest())
                && PayrollCalculationModels.ENGINE_VERSION.equals(actual.engineVersion());
    }

    void verifyWorkers(
            long tenantId, UUID runId, int attemptNo,
            List<WorkerResult> expected,
            String calculationResultDigest) {
        Integer wrongResultDigest = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_worker_results
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                   AND calculation_result_digest <> :resultDigest
                """, params(tenantId, runId)
                .addValue("attemptNo", attemptNo)
                .addValue("resultDigest", calculationResultDigest), Integer.class);
        if (wrongResultDigest == null || wrongResultDigest != 0) {
            throw conflict("Payroll worker facts use a different result digest.", null);
        }
        List<WorkerResult> durable = jdbc.query("""
                SELECT worker_result_id, worker_id, currency,
                       gross_amount, deduction_amount,
                       net_amount, employer_cost_amount, line_count,
                       worker_result_digest, calculation_result_digest
                  FROM pay_worker_results
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                 ORDER BY worker_id
                """, params(tenantId, runId).addValue("attemptNo", attemptNo),
                (result, row) -> new WorkerResult(
                        result.getObject("worker_result_id", UUID.class),
                        result.getObject("worker_id", UUID.class),
                        result.getString("currency"),
                        result.getBigDecimal("gross_amount"),
                        result.getBigDecimal("deduction_amount"),
                        result.getBigDecimal("net_amount"),
                        result.getBigDecimal("employer_cost_amount"),
                        result.getInt("line_count"),
                        result.getString("worker_result_digest")));
        List<WorkerResult> sortedExpected = expected.stream()
                .sorted(Comparator.comparing(WorkerResult::workerId)).toList();
        if (durable.size() != sortedExpected.size()) {
            throw conflict("Payroll durable worker result count changed.", null);
        }
        for (int index = 0; index < sortedExpected.size(); index++) {
            WorkerResult actual = durable.get(index);
            WorkerResult expectedWorker = sortedExpected.get(index);
            if (!actual.workerResultId().equals(expectedWorker.workerResultId())
                    || !actual.workerId().equals(expectedWorker.workerId())
                    || !actual.currency().equals(expectedWorker.currency())
                    || actual.gross().compareTo(expectedWorker.gross()) != 0
                    || actual.deductions().compareTo(
                    expectedWorker.deductions()) != 0
                    || actual.net().compareTo(expectedWorker.net()) != 0
                    || actual.employerCost().compareTo(
                    expectedWorker.employerCost()) != 0
                    || actual.lineCount() != expectedWorker.lineCount()
                    || !actual.workerResultDigest().equals(
                    expectedWorker.workerResultDigest())) {
                throw conflict(
                        "A durable Payroll worker result differs from recomputation.", null);
            }
        }
    }

    MapSqlParameterSource lineParameters(
            Lease lease, CalculatedLine line, Instant recordedAt) {
        return params(lease.tenantId(), lease.runId())
                .addValue("attemptNo", lease.attemptNo())
                .addValue("resultLineId", line.resultLineId())
                .addValue("sourceInputId", line.sourceInputId())
                .addValue("workerId", line.workerId())
                .addValue("workerResultId", line.workerResultId())
                .addValue("componentCode", line.componentCode())
                .addValue("sourcePayloadDigest", line.sourcePayloadDigest())
                .addValue("semanticBindingDigest", line.semanticBindingDigest())
                .addValue("elementId", line.elementId())
                .addValue("elementVersion", line.elementVersion())
                .addValue("formulaId", line.formulaId())
                .addValue("formulaVersion", line.formulaVersion())
                .addValue("formulaAstDigest", line.formulaAstDigest())
                .addValue("lineCategory", line.category().name())
                .addValue("formulaInputDigest", line.formulaInputDigest())
                .addValue("formulaOutputDigest", line.formulaOutputDigest())
                .addValue("currency", line.currency()).addValue("amount", line.amount())
                .addValue("terminalTraceId", line.terminalTraceId())
                .addValue("traceDigest", line.traceDigest())
                .addValue("lineDigest", line.resultLineDigest())
                .addValue("recordedAt", timestamp(recordedAt));
    }

    MapSqlParameterSource traceParameters(
            Lease lease,
            CalculatedLine line,
            PayrollCalculationModels.CalculatedTraceNode trace,
            Instant recordedAt) {
        return params(lease.tenantId(), lease.runId())
                .addValue("attemptNo", lease.attemptNo())
                .addValue("sourceInputId", line.sourceInputId())
                .addValue("workerId", line.workerId())
                .addValue("elementId", line.elementId())
                .addValue("elementVersion", line.elementVersion())
                .addValue("lineCategory", line.category().name())
                .addValue("sourcePayloadDigest", line.sourcePayloadDigest())
                .addValue("traceId", trace.traceId())
                .addValue("traceSequence", trace.sequence())
                .addValue("parentTraceId", trace.parentTraceId())
                .addValue("semanticBindingDigest", trace.semanticBindingDigest())
                .addValue("formulaId", trace.formulaId())
                .addValue("formulaVersion", trace.formulaVersion())
                .addValue("formulaAstDigest", trace.formulaAstDigest())
                .addValue("formulaInputDigest", trace.inputDigest())
                .addValue("formulaOutputDigest", trace.outputDigest())
                .addValue("unroundedAmount", trace.unroundedAmount())
                .addValue("roundedAmount", trace.roundedAmount())
                .addValue("currency", trace.currency())
                .addValue("roundingStage", trace.stage().name())
                .addValue("roundingScale", trace.roundingScale())
                .addValue("roundingMode", trace.roundingMode().name())
                .addValue("roundingIncrement", trace.roundingIncrement())
                .addValue("roundingPolicyDigest", trace.roundingPolicyDigest())
                .addValue("nodeTraceDigest", trace.traceDigest())
                .addValue("engineVersion", PayrollCalculationModels.ENGINE_VERSION)
                .addValue("recordedAt", timestamp(recordedAt));
    }

}
