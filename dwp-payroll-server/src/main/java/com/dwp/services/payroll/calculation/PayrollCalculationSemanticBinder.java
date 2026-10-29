package com.dwp.services.payroll.calculation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticContract.FormulaBinding;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FrozenSnapshot;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.QuantizationBinding;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.SemanticBinding;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.SourceInput;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticContract.Availability;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticContract.EvaluatedLine;

/** Seals authoritative component semantics before an attempt or receipt can exist. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollCalculationSemanticBinder {

    private final PayrollCalculationSemanticMappingPort mappings;
    private final PayrollCalculationCanonical canonical;

    PayrollCalculationSemanticBinder(
            PayrollCalculationSemanticMappingPort mappings,
            PayrollCalculationCanonical canonical) {
        this.mappings = mappings;
        this.canonical = canonical;
    }

    FrozenSnapshot bind(FrozenSnapshot draft) {
        if (!draft.semanticBindings().isEmpty()) {
            throw invalid("Payroll semantic bindings were already present before binding.");
        }
        List<SemanticBinding> bindings = draft.inputs().stream()
                .sorted(Comparator.comparingInt(SourceInput::lineSequence)
                        .thenComparing(SourceInput::sourcePublicId))
                .map(input -> binding(
                        draft, input, requireExact(draft, input), draft.formulas()))
                .toList();
        String aggregateDigest = canonical.digest(Map.of(
                "schema", "PayrollSemanticBindingSet.v1",
                "engineVersion", PayrollCalculationModels.ENGINE_VERSION,
                "bindingDigests", bindings.stream()
                        .map(SemanticBinding::bindingDigest).toList()));
        return new FrozenSnapshot(
                draft.tenantId(), draft.runId(), draft.attemptNo(),
                draft.sourceSnapshotId(), draft.sourceSnapshotRevision(),
                draft.sourcePayloadDigest(), draft.formulaSetDigest(),
                draft.targetPopulationDigest(), draft.compensationInputDigest(),
                aggregateDigest, draft.inputs(), draft.formulas(), bindings,
                draft.pinnedAt());
    }

    EvaluatedLine evaluate(FrozenSnapshot snapshot, SourceInput input) {
        SemanticBinding sealed = snapshot.semanticBindings().stream()
                .filter(binding -> binding.sourcePublicId().equals(input.sourcePublicId()))
                .findFirst().orElseThrow(() -> invalid(
                        "The frozen Payroll input has no sealed semantic binding."));
        EvaluatedLine evaluated = requireExact(snapshot, input);
        SemanticBinding current = binding(
                snapshot, input, evaluated, snapshot.formulas());
        if (!sealed.equals(current)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The authoritative Payroll semantics changed after the attempt was frozen.");
        }
        String aggregateDigest = canonical.digest(Map.of(
                "schema", "PayrollSemanticBindingSet.v1",
                "engineVersion", PayrollCalculationModels.ENGINE_VERSION,
                "bindingDigests", snapshot.semanticBindings().stream()
                        .map(SemanticBinding::bindingDigest).toList()));
        if (!aggregateDigest.equals(snapshot.semanticBindingDigest())) {
            throw invalid("The frozen Payroll semantic binding digest is invalid.");
        }
        return evaluated;
    }

    private EvaluatedLine requireExact(FrozenSnapshot snapshot, SourceInput input) {
        PayrollCalculationSemanticContract.Resolution resolution =
                mappings.resolve(snapshot, input);
        if (resolution == null || resolution.availability() == Availability.UNAVAILABLE) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Authoritative payroll component semantics are unavailable.");
        }
        EvaluatedLine evaluated = resolution.evaluatedLine();
        if (!input.sourcePublicId().equals(evaluated.sourcePublicId())
                || !input.payloadDigest().equals(evaluated.sourcePayloadDigest())
                || !input.componentCode().equals(evaluated.componentCode())
                || !input.currency().equals(evaluated.currency())) {
            throw invalid("The semantic binding does not match its PAY-016 source.");
        }
        return evaluated;
    }

    private SemanticBinding binding(
            FrozenSnapshot snapshot,
            SourceInput input,
            EvaluatedLine evaluated,
            List<FormulaBinding> frozenFormulaSet) {
        List<FormulaBinding> dependencies = evaluated.formulaDependencies().stream()
                .distinct()
                .sorted(Comparator.comparing(FormulaBinding::formulaId)
                        .thenComparingLong(FormulaBinding::version))
                .toList();
        if (dependencies.size() != evaluated.formulaDependencies().size()
                || !frozenFormulaSet.containsAll(dependencies)
                || evaluated.traceSteps().stream()
                .map(PayrollCalculationSemanticContract.QuantizationStep::formula)
                .filter(Objects::nonNull).anyMatch(formula -> !dependencies.contains(formula))) {
            throw invalid("Formula dependency closure is not part of the frozen formula set.");
        }
        List<QuantizationBinding> policies = evaluated.traceSteps().stream()
                .map(step -> new QuantizationBinding(
                        step.sequence(), step.parentSequence(), step.stage(),
                        step.formula(),
                        step.scale(), step.mode(), step.increment(),
                        step.roundingPolicyDigest()))
                .toList();
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schema", "PayrollSemanticBinding.v1");
        material.put("sourcePublicId", input.sourcePublicId());
        material.put("sourcePayloadDigest", input.payloadDigest());
        material.put("elementId", evaluated.elementId());
        material.put("elementVersion", evaluated.elementVersion());
        material.put("elementSemanticDigest", evaluated.elementSemanticDigest());
        material.put("category", evaluated.category().name());
        material.put("valueCurrencyMappingId", evaluated.valueCurrencyMappingId());
        material.put("valueCurrencyMappingDigest",
                evaluated.valueCurrencyMappingDigest());
        material.put("formulaDependencies", dependencies);
        material.put("quantizationPolicies", policies);
        material.put("engineVersion", PayrollCalculationModels.ENGINE_VERSION);
        material.put("functionSetVersion", evaluated.functionSetVersion());
        String digest = canonical.digest(material);
        return new SemanticBinding(
                input.sourcePublicId(), evaluated.elementId(),
                evaluated.elementVersion(), evaluated.elementSemanticDigest(),
                evaluated.category(), evaluated.valueCurrencyMappingId(),
                evaluated.valueCurrencyMappingDigest(), dependencies, policies,
                PayrollCalculationModels.ENGINE_VERSION,
                evaluated.functionSetVersion(), digest);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
