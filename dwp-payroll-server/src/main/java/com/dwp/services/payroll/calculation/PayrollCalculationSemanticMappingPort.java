package com.dwp.services.payroll.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FormulaBinding;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FrozenSnapshot;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.SourceInput;

/**
 * Owner boundary for the semantic meaning of a frozen compensation component.
 * A component code is an opaque identifier; it never implies a payroll category.
 */
interface PayrollCalculationSemanticMappingPort {

    enum Availability { EXACT, UNAVAILABLE }

    enum Category { EARNING, DEDUCTION, EMPLOYER_COST }

    enum RoundingStage {
        SOURCE_NORMALIZE,
        FORMULA_DECLARED,
        ELEMENT,
        WORKER_RESULT,
        SETTLEMENT_OR_STATUTORY
    }

    record QuantizationStep(
            int sequence,
            Integer parentSequence,
            RoundingStage stage,
            FormulaBinding formula,
            String inputDigest,
            String outputDigest,
            BigDecimal unroundedAmount,
            BigDecimal roundedAmount,
            int scale,
            RoundingMode mode,
            BigDecimal increment,
            String roundingPolicyDigest) {

        private static final Set<RoundingMode> ALLOWED_MODES = Set.of(
                RoundingMode.HALF_UP,
                RoundingMode.HALF_EVEN,
                RoundingMode.DOWN,
                RoundingMode.UP,
                RoundingMode.FLOOR,
                RoundingMode.CEILING);

        public QuantizationStep {
            if (sequence < 1 || (parentSequence != null
                    && (parentSequence < 1 || parentSequence >= sequence))) {
                throw new IllegalArgumentException(
                        "calculation trace lineage is invalid");
            }
            Objects.requireNonNull(stage, "stage");
            PayrollCalculationModels.digest(inputDigest, "inputDigest");
            PayrollCalculationModels.digest(outputDigest, "outputDigest");
            unroundedAmount = calculationDecimal(unroundedAmount, "unroundedAmount");
            roundedAmount = calculationDecimal(roundedAmount, "roundedAmount");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(increment, "increment");
            if (scale < 0 || scale > 8 || !ALLOWED_MODES.contains(mode)) {
                throw new IllegalArgumentException(
                        "calculation rounding evidence is invalid");
            }
            try {
                increment = increment.setScale(scale, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "calculation rounding increment is invalid", exception);
            }
            if (increment.signum() <= 0 || increment.precision() > 24) {
                throw new IllegalArgumentException(
                        "calculation rounding increment is invalid");
            }
            BigDecimal expected = unroundedAmount.divide(increment, 0, mode)
                    .multiply(increment).setScale(scale, RoundingMode.UNNECESSARY);
            if (expected.compareTo(roundedAmount) != 0) {
                throw new IllegalArgumentException(
                        "roundedAmount does not match the declared rounding policy");
            }
            PayrollCalculationModels.digest(
                    roundingPolicyDigest, "roundingPolicyDigest");
        }

        private static BigDecimal calculationDecimal(BigDecimal value, String field) {
            Objects.requireNonNull(value, field);
            if (value.signum() < 0 || value.scale() > 8) {
                throw new IllegalArgumentException(
                        field + " must be a nonnegative CalculationDecimal");
            }
            try {
                value = value.setScale(8, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        field + " must fit CalculationDecimal scale 8", exception);
            }
            if (value.precision() > 24) {
                throw new IllegalArgumentException(
                        field + " must fit NUMERIC(24,8)");
            }
            return value;
        }
    }

    record EvaluatedLine(
            UUID sourcePublicId,
            String sourcePayloadDigest,
            String componentCode,
            UUID elementId,
            long elementVersion,
            String elementSemanticDigest,
            String valueCurrencyMappingId,
            String valueCurrencyMappingDigest,
            FormulaBinding formula,
            List<FormulaBinding> formulaDependencies,
            String functionSetVersion,
            Category category,
            BigDecimal evaluatedAmount,
            String currency,
            List<QuantizationStep> traceSteps) {

        public EvaluatedLine {
            Objects.requireNonNull(sourcePublicId, "sourcePublicId");
            PayrollCalculationModels.digest(
                    sourcePayloadDigest, "sourcePayloadDigest");
            if (componentCode == null || componentCode.isBlank()) {
                throw new IllegalArgumentException("componentCode is required");
            }
            componentCode = componentCode.trim();
            Objects.requireNonNull(elementId, "elementId");
            if (elementVersion < 1) {
                throw new IllegalArgumentException("elementVersion must be positive");
            }
            PayrollCalculationModels.digest(
                    elementSemanticDigest, "elementSemanticDigest");
            if (valueCurrencyMappingId == null || valueCurrencyMappingId.isBlank()
                    || valueCurrencyMappingId.length() > 160) {
                throw new IllegalArgumentException(
                        "valueCurrencyMappingId is required");
            }
            valueCurrencyMappingId = valueCurrencyMappingId.trim();
            PayrollCalculationModels.digest(
                    valueCurrencyMappingDigest, "valueCurrencyMappingDigest");
            Objects.requireNonNull(formula, "formula");
            formulaDependencies = List.copyOf(Objects.requireNonNull(
                    formulaDependencies, "formulaDependencies"));
            if (formulaDependencies.isEmpty() || !formulaDependencies.contains(formula)) {
                throw new IllegalArgumentException(
                        "formula dependency closure must include the terminal formula");
            }
            if (functionSetVersion == null || functionSetVersion.isBlank()
                    || functionSetVersion.length() > 64) {
                throw new IllegalArgumentException("functionSetVersion is required");
            }
            functionSetVersion = functionSetVersion.trim();
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(evaluatedAmount, "evaluatedAmount");
            try {
                evaluatedAmount = evaluatedAmount.setScale(4, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "evaluatedAmount must fit canonical scale 4", exception);
            }
            if (evaluatedAmount.signum() < 0 || evaluatedAmount.precision() > 19) {
                throw new IllegalArgumentException(
                        "evaluatedAmount must be a nonnegative NUMERIC(19,4)");
            }
            if (currency == null || !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currency must be ISO-4217 shaped");
            }
            traceSteps = List.copyOf(Objects.requireNonNull(traceSteps, "traceSteps"));
            if (traceSteps.isEmpty() || traceSteps.size() > 64) {
                throw new IllegalArgumentException(
                        "an evaluated line requires 1..64 trace steps");
            }
            RoundingStage previousStage = null;
            String previousOutputDigest = null;
            for (int index = 0; index < traceSteps.size(); index++) {
                QuantizationStep step = traceSteps.get(index);
                int expectedSequence = index + 1;
                Integer expectedParent = index == 0 ? null : index;
                if (step.sequence() != expectedSequence
                        || !Objects.equals(step.parentSequence(), expectedParent)
                        || (previousOutputDigest != null
                        && !previousOutputDigest.equals(step.inputDigest()))
                        || (previousStage != null
                        && step.stage().ordinal() < previousStage.ordinal())) {
                    throw new IllegalArgumentException(
                            "calculation trace steps must form one ordered pipeline");
                }
                previousStage = step.stage();
                previousOutputDigest = step.outputDigest();
            }
            QuantizationStep terminal = traceSteps.getLast();
            if (terminal.formula() == null || !formula.equals(terminal.formula())
                    || evaluatedAmount.compareTo(terminal.roundedAmount()) != 0) {
                throw new IllegalArgumentException(
                        "evaluatedAmount must equal the terminal formula trace amount");
            }
        }
    }

    record Resolution(Availability availability, EvaluatedLine evaluatedLine) {

        public Resolution {
            Objects.requireNonNull(availability, "availability");
            if ((availability == Availability.EXACT) != (evaluatedLine != null)) {
                throw new IllegalArgumentException(
                        "an exact semantic resolution requires one evaluated line");
            }
        }

        static Resolution exact(EvaluatedLine evaluatedLine) {
            return new Resolution(
                    Availability.EXACT, Objects.requireNonNull(evaluatedLine));
        }

        static Resolution unavailable() {
            return new Resolution(Availability.UNAVAILABLE, null);
        }
    }

    Resolution resolve(FrozenSnapshot snapshot, SourceInput input);
}
