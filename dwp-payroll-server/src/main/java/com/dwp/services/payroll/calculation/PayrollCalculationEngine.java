package com.dwp.services.payroll.calculation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CalculatedLine;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CalculatedTraceNode;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CalculationOutcome;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ControlTotal;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FrozenSnapshot;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.SemanticBinding;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.SourceInput;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.WorkerResult;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticMappingPort.Category;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticMappingPort.EvaluatedLine;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticMappingPort.QuantizationStep;

/** Pure, bounded calculation over an already frozen PAY-016 snapshot. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollCalculationEngine {

    private static final int MAX_INPUTS = 100_000;
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final PayrollCalculationCanonical canonical;
    private final PayrollCalculationSemanticBinder semanticBinder;

    @Autowired
    PayrollCalculationEngine(
            PayrollCalculationCanonical canonical,
            PayrollCalculationSemanticBinder semanticBinder) {
        this.canonical = canonical;
        this.semanticBinder = semanticBinder;
    }

    PayrollCalculationEngine(
            PayrollCalculationCanonical canonical,
            PayrollCalculationSemanticMappingPort semanticMappings) {
        this(canonical, new PayrollCalculationSemanticBinder(
                semanticMappings, canonical));
    }

    /** Package-local convenience remains fail closed, matching the production default. */
    PayrollCalculationEngine(PayrollCalculationCanonical canonical) {
        this(canonical, (snapshot, input) ->
                PayrollCalculationSemanticMappingPort.Resolution.unavailable());
    }

    CalculationOutcome calculate(FrozenSnapshot snapshot) {
        validate(snapshot);
        List<SourceInput> inputs = snapshot.inputs().stream()
                .sorted(Comparator.comparingInt(SourceInput::lineSequence)
                        .thenComparing(SourceInput::sourceInputId))
                .toList();
        List<EvaluatedInput> evaluatedInputs = inputs.stream()
                .map(input -> new EvaluatedInput(
                        input, requireExact(snapshot, input), binding(snapshot, input)))
                .toList();
        String currency = inputs.getFirst().currency();
        ArrayList<CalculatedLine> lines = new ArrayList<>(inputs.size());
        LinkedHashMap<UUID, BigDecimal> workerEarnings = new LinkedHashMap<>();
        LinkedHashMap<UUID, BigDecimal> workerDeductions = new LinkedHashMap<>();
        LinkedHashMap<UUID, BigDecimal> workerEmployerCosts = new LinkedHashMap<>();

        for (EvaluatedInput evaluatedInput : evaluatedInputs) {
            SourceInput input = evaluatedInput.input();
            EvaluatedLine evaluated = evaluatedInput.evaluated();
            SemanticBinding semanticBinding = evaluatedInput.binding();
            BigDecimal amount = evaluatedMoney(evaluated.evaluatedAmount());
            List<CalculatedTraceNode> traceNodes = traceNodes(
                    snapshot, input, evaluated, semanticBinding);
            String traceDigest = canonical.digest(Map.of(
                    "schema", "PayrollCalculationTraceGraph.v1",
                    "nodeDigests", traceNodes.stream()
                            .map(CalculatedTraceNode::traceDigest).toList()));
            CalculatedTraceNode terminalTrace = traceNodes.getLast();
            Map<String, Object> lineMaterial = new LinkedHashMap<>();
            lineMaterial.put("engineVersion", PayrollCalculationModels.ENGINE_VERSION);
            lineMaterial.put("sourcePublicId", input.sourcePublicId());
            lineMaterial.put("sourcePayloadDigest", input.payloadDigest());
            lineMaterial.put("workerId", input.workerId());
            lineMaterial.put("assignmentId", input.assignmentId());
            lineMaterial.put("componentCode", input.componentCode());
            lineMaterial.put("elementId", evaluated.elementId());
            lineMaterial.put("elementVersion", evaluated.elementVersion());
            lineMaterial.put("elementSemanticDigest",
                    evaluated.elementSemanticDigest());
            lineMaterial.put("valueCurrencyMappingId",
                    evaluated.valueCurrencyMappingId());
            lineMaterial.put("valueCurrencyMappingDigest",
                    evaluated.valueCurrencyMappingDigest());
            lineMaterial.put("functionSetVersion", evaluated.functionSetVersion());
            lineMaterial.put("semanticBindingDigest",
                    semanticBinding.bindingDigest());
            lineMaterial.put("formulaId", evaluated.formula().formulaId());
            lineMaterial.put("formulaVersion", evaluated.formula().version());
            lineMaterial.put("formulaAstDigest", evaluated.formula().astDigest());
            lineMaterial.put("formulaInputDigest", terminalTrace.inputDigest());
            lineMaterial.put("formulaOutputDigest", terminalTrace.outputDigest());
            lineMaterial.put("category", evaluated.category().name());
            lineMaterial.put("traceDigest", traceDigest);
            lineMaterial.put("amount", amount.toPlainString());
            lineMaterial.put("currency", input.currency());
            lineMaterial.put("formulaSetDigest", snapshot.formulaSetDigest());
            String lineDigest = canonical.digest(lineMaterial);
            UUID resultLineId = canonical.deterministicUuid(
                    "payroll-calculation-result-line-v1", Map.of(
                            "runId", snapshot.runId(),
                            "attemptNo", snapshot.attemptNo(),
                            "sourceInputId", input.sourceInputId(),
                            "lineDigest", lineDigest));
            lines.add(new CalculatedLine(
                    resultLineId, input.sourceInputId(), input.workerId(),
                    workerResultId(snapshot, input.workerId()),
                    input.componentCode(), input.payloadDigest(),
                    semanticBinding.bindingDigest(), evaluated.elementId(),
                    evaluated.elementVersion(),
                    evaluated.formula().formulaId(), evaluated.formula().version(),
                    evaluated.formula().astDigest(), evaluated.category(),
                    currency, amount, terminalTrace.inputDigest(),
                    terminalTrace.outputDigest(), terminalTrace.traceId(),
                    traceNodes, lineDigest, traceDigest));
            if (evaluated.category() == Category.DEDUCTION) {
                workerDeductions.merge(input.workerId(), amount, BigDecimal::add);
            } else if (evaluated.category() == Category.EMPLOYER_COST) {
                workerEmployerCosts.merge(input.workerId(), amount, BigDecimal::add);
            } else {
                workerEarnings.merge(input.workerId(), amount, BigDecimal::add);
            }
        }

        List<UUID> workerIds = inputs.stream().map(SourceInput::workerId)
                .distinct().sorted().toList();
        List<WorkerResult> workers = workerIds.stream()
                .map(workerId -> worker(
                        workerResultId(snapshot, workerId), workerId, currency,
                        workerEarnings.getOrDefault(workerId, zero()),
                        workerDeductions.getOrDefault(workerId, zero()),
                        workerEmployerCosts.getOrDefault(workerId, zero()),
                        Math.toIntExact(lines.stream()
                                .filter(line -> line.workerId().equals(workerId)).count())))
                .toList();
        BigDecimal gross = workers.stream().map(WorkerResult::gross)
                .reduce(zero(), BigDecimal::add);
        BigDecimal deductions = workers.stream().map(WorkerResult::deductions)
                .reduce(zero(), BigDecimal::add);
        BigDecimal employerCost = workers.stream().map(WorkerResult::employerCost)
                .reduce(zero(), BigDecimal::add);
        gross = postedMoney(gross, "gross control total");
        deductions = postedMoney(deductions, "deduction control total");
        employerCost = postedMoney(employerCost, "employer-cost control total");
        BigDecimal net = postedMoney(gross.subtract(deductions), "net control total");
        if (net.signum() < 0) {
            throw invalid("Payroll deductions exceed gross earnings.");
        }
        int workerLineCount = workers.stream().mapToInt(WorkerResult::lineCount).sum();
        if (workerLineCount != lines.size()) {
            throw invalid("Payroll worker result lines do not reconcile.");
        }
        List<ControlTotal> controlTotals = List.of(
                new ControlTotal("GROSS", gross, currency),
                new ControlTotal("DEDUCTIONS", deductions, currency),
                new ControlTotal("NET", net, currency),
                new ControlTotal("EMPLOYER_COST", employerCost, currency));
        Map<String, Object> resultMaterial = new LinkedHashMap<>();
        resultMaterial.put("schema", "PayrollCalculationResult.v1");
        resultMaterial.put("engineVersion", PayrollCalculationModels.ENGINE_VERSION);
        resultMaterial.put("sourceSnapshotId", snapshot.sourceSnapshotId());
        resultMaterial.put("sourceSnapshotRevision", snapshot.sourceSnapshotRevision());
        resultMaterial.put("sourcePayloadDigest", snapshot.sourcePayloadDigest());
        resultMaterial.put("formulaSetDigest", snapshot.formulaSetDigest());
        resultMaterial.put("targetPopulationDigest", snapshot.targetPopulationDigest());
        resultMaterial.put("compensationInputDigest", snapshot.compensationInputDigest());
        resultMaterial.put("semanticBindingDigest", snapshot.semanticBindingDigest());
        resultMaterial.put("lineDigests",
                lines.stream().map(CalculatedLine::resultLineDigest).toList());
        resultMaterial.put("workerDigests",
                workers.stream().map(WorkerResult::workerResultDigest).toList());
        resultMaterial.put("controlTotals", controlTotals.stream().map(total -> Map.of(
                "code", total.code(),
                "amount", total.amount().toPlainString(),
                "currency", total.currency())).toList());
        String resultDigest = canonical.digest(resultMaterial);
        return new CalculationOutcome(
                lines, workers, controlTotals, gross, net, currency, resultDigest);
    }

    private List<CalculatedTraceNode> traceNodes(
            FrozenSnapshot snapshot,
            SourceInput input,
            EvaluatedLine evaluated,
            SemanticBinding semanticBinding) {
        ArrayList<CalculatedTraceNode> nodes = new ArrayList<>(
                evaluated.traceSteps().size());
        UUID parentTraceId = null;
        for (QuantizationStep step : evaluated.traceSteps()) {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("schema", "PayrollCalculationTraceNode.v1");
            material.put("engineVersion", PayrollCalculationModels.ENGINE_VERSION);
            material.put("sourcePublicId", input.sourcePublicId());
            material.put("sourcePayloadDigest", input.payloadDigest());
            material.put("formulaSetDigest", snapshot.formulaSetDigest());
            material.put("semanticBindingDigest", semanticBinding.bindingDigest());
            material.put("elementId", evaluated.elementId());
            material.put("elementVersion", evaluated.elementVersion());
            material.put("elementSemanticDigest", evaluated.elementSemanticDigest());
            material.put("valueCurrencyMappingId", evaluated.valueCurrencyMappingId());
            material.put("valueCurrencyMappingDigest",
                    evaluated.valueCurrencyMappingDigest());
            material.put("functionSetVersion", evaluated.functionSetVersion());
            material.put("category", evaluated.category().name());
            material.put("sequence", step.sequence());
            material.put("parentSequence", step.parentSequence());
            material.put("stage", step.stage().name());
            material.put("formula", step.formula() == null ? null : Map.of(
                    "formulaId", step.formula().formulaId(),
                    "version", step.formula().version(),
                    "astDigest", step.formula().astDigest()));
            material.put("inputDigest", step.inputDigest());
            material.put("outputDigest", step.outputDigest());
            material.put("unroundedAmount", step.unroundedAmount().toPlainString());
            material.put("roundedAmount", step.roundedAmount().toPlainString());
            material.put("currency", evaluated.currency());
            material.put("roundingScale", step.scale());
            material.put("roundingMode", step.mode().name());
            material.put("roundingIncrement", step.increment().toPlainString());
            material.put("roundingPolicyDigest", step.roundingPolicyDigest());
            String nodeDigest = canonical.digest(material);
            UUID traceId = canonical.deterministicUuid(
                    "payroll-calculation-trace-node-v1", Map.of(
                            "runId", snapshot.runId(),
                            "attemptNo", snapshot.attemptNo(),
                            "sourcePublicId", input.sourcePublicId(),
                            "sequence", step.sequence(),
                            "traceDigest", nodeDigest));
            nodes.add(new CalculatedTraceNode(
                    traceId, step.sequence(), parentTraceId,
                    semanticBinding.bindingDigest(), step.stage(),
                    step.formula() == null ? null : step.formula().formulaId(),
                    step.formula() == null ? null : step.formula().version(),
                    step.formula() == null ? null : step.formula().astDigest(),
                    step.inputDigest(), step.outputDigest(),
                    step.unroundedAmount(), step.roundedAmount(), evaluated.currency(),
                    step.scale(), step.mode(), step.increment(),
                    step.roundingPolicyDigest(), nodeDigest));
            parentTraceId = traceId;
        }
        return List.copyOf(nodes);
    }

    private WorkerResult worker(
            UUID workerResultId,
            UUID workerId,
            String currency,
            BigDecimal rawGross,
            BigDecimal rawDeductions,
            BigDecimal rawEmployerCost,
            int lineCount) {
        BigDecimal gross = postedMoney(rawGross, "worker gross");
        BigDecimal deductions = postedMoney(rawDeductions, "worker deductions");
        BigDecimal employerCost = postedMoney(
                rawEmployerCost, "worker employer cost");
        BigDecimal net = postedMoney(gross.subtract(deductions), "worker net");
        if (net.signum() < 0 || lineCount < 1) {
            throw invalid("Payroll worker deductions exceed gross earnings.");
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schema", "PayrollWorkerResult.v1");
        material.put("engineVersion", PayrollCalculationModels.ENGINE_VERSION);
        material.put("workerId", workerId);
        material.put("currency", currency);
        material.put("gross", gross.toPlainString());
        material.put("deductions", deductions.toPlainString());
        material.put("net", net.toPlainString());
        material.put("employerCost", employerCost.toPlainString());
        material.put("lineCount", lineCount);
        String digest = canonical.digest(material);
        return new WorkerResult(
                workerResultId, workerId, currency, gross, deductions, net,
                employerCost, lineCount, digest);
    }

    private UUID workerResultId(FrozenSnapshot snapshot, UUID workerId) {
        return canonical.deterministicUuid(
                "payroll-worker-result-v1", Map.of(
                        "tenantId", snapshot.tenantId(),
                        "runId", snapshot.runId(),
                        "attemptNo", snapshot.attemptNo(),
                        "workerId", workerId));
    }

    private EvaluatedLine requireExact(FrozenSnapshot snapshot, SourceInput input) {
        return semanticBinder.evaluate(snapshot, input);
    }

    private SemanticBinding binding(FrozenSnapshot snapshot, SourceInput input) {
        return snapshot.semanticBindings().stream()
                .filter(value -> value.sourcePublicId().equals(input.sourcePublicId()))
                .findFirst().orElseThrow(() -> invalid(
                        "The frozen input has no sealed semantic binding."));
    }

    private void validate(FrozenSnapshot snapshot) {
        if (snapshot.inputs().size() > MAX_INPUTS) {
            throw invalid("Frozen payroll input exceeds the bounded engine limit.");
        }
        if (snapshot.sourceSnapshotRevision() < 1) {
            throw invalid("A positive frozen PAY-016 snapshot revision is required.");
        }
        PayrollCalculationModels.digest(snapshot.sourcePayloadDigest(), "sourcePayloadDigest");
        PayrollCalculationModels.digest(snapshot.formulaSetDigest(), "formulaSetDigest");
        PayrollCalculationModels.digest(
                snapshot.targetPopulationDigest(), "targetPopulationDigest");
        PayrollCalculationModels.digest(
                snapshot.compensationInputDigest(), "compensationInputDigest");
        PayrollCalculationModels.digest(
                snapshot.semanticBindingDigest(), "semanticBindingDigest");
        Set<String> currencies = snapshot.inputs().stream()
                .map(SourceInput::currency).collect(java.util.stream.Collectors.toSet());
        if (currencies.size() != 1 || currencies.stream().anyMatch(
                value -> value == null || !value.matches("[A-Z]{3}"))) {
            throw invalid("A calculation attempt requires one exact currency.");
        }
        long distinctSources = snapshot.inputs().stream()
                .map(SourceInput::sourceInputId).distinct().count();
        if (distinctSources != snapshot.inputs().size()) {
            throw invalid("Frozen payroll inputs contain a duplicate source row.");
        }
        for (SourceInput input : snapshot.inputs()) {
            if (!snapshot.sourceSnapshotId().equals(input.snapshotId())
                    || snapshot.sourceSnapshotRevision() != input.snapshotRevision()
                    || !snapshot.sourcePayloadDigest().equals(input.snapshotPayloadDigest())
                    || input.approvedAmount() == null
                    || input.approvedAmount().signum() < 0
                    || input.approvedAmount().scale() > SCALE) {
                throw invalid("PAY-016 input rows do not match the frozen snapshot binding.");
            }
        }
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, ROUNDING);
    }

    private BigDecimal postedMoney(BigDecimal value, String label) {
        BigDecimal posted = value.setScale(SCALE, ROUNDING);
        if (posted.precision() > 19) {
            throw invalid("Payroll " + label + " exceeds NUMERIC(19,4).");
        }
        return posted;
    }

    private BigDecimal evaluatedMoney(BigDecimal value) {
        if (value == null || value.scale() != SCALE || value.signum() < 0
                || value.precision() > 19) {
            throw invalid("The evaluated result line is not canonical NUMERIC(19,4).");
        }
        return value;
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private record EvaluatedInput(
            SourceInput input,
            EvaluatedLine evaluated,
            SemanticBinding binding) {
    }
}
