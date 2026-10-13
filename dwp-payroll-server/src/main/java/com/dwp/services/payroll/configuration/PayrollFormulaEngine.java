package com.dwp.services.payroll.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaErrorCode;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaFinding;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaNode;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaOperator;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PayFormulaAst;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PayValueType;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.RoundingPolicy;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.SimulationReport;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.TypedValue;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.ValidationReport;

/** A bounded interpreter for the allowlisted payroll AST; it cannot execute source text. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.payroll-configuration.wave2.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollFormulaEngine {

    static final int MAX_NODES = 2_048;
    static final int MAX_DEPTH = 64;
    static final int MAX_EVALUATED_NODES = 4_096;
    private static final MathContext INTERMEDIATE = new MathContext(34, RoundingMode.HALF_EVEN);

    private final PayrollConfigurationCanonical canonical;
    private final LongSupplier nanoTime;

    @Autowired
    PayrollFormulaEngine(ObjectMapper objectMapper) {
        this(new PayrollConfigurationCanonical(objectMapper), System::nanoTime);
    }

    PayrollFormulaEngine(PayrollConfigurationCanonical canonical, LongSupplier nanoTime) {
        this.canonical = canonical;
        this.nanoTime = nanoTime;
    }

    ValidationReport validate(PayFormulaAst ast, Instant now) {
        Objects.requireNonNull(ast, "ast");
        Objects.requireNonNull(now, "now");
        String astDigest = canonical.digest(ast);
        List<FormulaFinding> findings = new ArrayList<>();
        if (ast.nodes().size() > MAX_NODES) {
            findings.add(finding(
                    FormulaErrorCode.LIMIT_EXCEEDED, null,
                    "Formula exceeds the maximum node count."));
        }

        Map<String, FormulaNode> nodes = new LinkedHashMap<>();
        for (FormulaNode node : ast.nodes()) {
            if (nodes.putIfAbsent(node.nodeId(), node) != null) {
                findings.add(finding(
                        FormulaErrorCode.DUPLICATE_NODE, node.nodeId(),
                        "Formula node identifiers must be unique."));
            }
        }
        if (!nodes.containsKey(ast.rootNodeId())) {
            findings.add(finding(
                    FormulaErrorCode.UNKNOWN_NODE, ast.rootNodeId(),
                    "Formula root node does not exist."));
        }

        for (FormulaNode node : ast.nodes()) {
            for (String argument : node.arguments()) {
                if (!nodes.containsKey(argument)) {
                    findings.add(finding(
                            FormulaErrorCode.UNKNOWN_NODE, node.nodeId(),
                            "Formula argument refers to an unknown node."));
                }
            }
            validateShape(node, findings);
        }
        detectCyclesAndDepth(ast, nodes, findings);
        if (findings.stream().noneMatch(finding ->
                finding.code() == FormulaErrorCode.CYCLE
                        || finding.code() == FormulaErrorCode.UNKNOWN_NODE
                        || finding.code() == FormulaErrorCode.DUPLICATE_NODE
                        || finding.code() == FormulaErrorCode.LIMIT_EXCEEDED)) {
            validateTypes(ast, nodes, findings);
        }
        if (nodes.containsKey(ast.rootNodeId())
                && nodes.get(ast.rootNodeId()).returnType() != ast.declaredReturnType()) {
            findings.add(finding(
                    FormulaErrorCode.TYPE, ast.rootNodeId(),
                    "Root type does not match declaredReturnType."));
        }
        String validationDigest = canonical.digest(Map.of(
                "astDigest", astDigest,
                "registry", PayrollConfigurationModels.AST_REGISTRY_ID,
                "registryVersion", PayrollConfigurationModels.AST_REGISTRY_VERSION,
                "findings", findings));
        return new ValidationReport(
                findings.isEmpty(), astDigest, validationDigest, findings, now);
    }

    SimulationReport simulate(
            PayFormulaAst ast,
            Map<String, TypedValue> inputs,
            RoundingPolicy rounding,
            long timeoutMillis,
            Instant now) {
        ValidationReport validation = validate(ast, now);
        String roundingDigest = canonical.digest(rounding);
        if (!validation.valid()) {
            FormulaErrorCode code = validation.findings().getFirst().code();
            return failed(validation.astDigest(), roundingDigest, code, 0, now);
        }
        long started = nanoTime.getAsLong();
        long timeoutNanos;
        try {
            timeoutNanos = Math.multiplyExact(timeoutMillis, 1_000_000L);
        } catch (ArithmeticException exception) {
            timeoutNanos = Long.MAX_VALUE;
        }
        long deadline = started > Long.MAX_VALUE - timeoutNanos
                ? Long.MAX_VALUE : started + timeoutNanos;
        EvaluationState state = new EvaluationState(deadline);
        Map<String, FormulaNode> nodes = new LinkedHashMap<>();
        ast.nodes().forEach(node -> nodes.put(node.nodeId(), node));
        try {
            TypedValue result = evaluate(
                    ast.rootNodeId(), nodes, inputs, rounding, state, new HashSet<>());
            if (result.type() != ast.declaredReturnType()) {
                throw new FormulaFailure(FormulaErrorCode.TYPE);
            }
            if (result.type().numeric()) {
                BigDecimal rounded = rounding.apply(result.decimal());
                result = new TypedValue(
                        result.type(), rounded.toPlainString(), null, result.currency());
            }
            String outputDigest = canonical.digest(Map.of(
                    "astDigest", validation.astDigest(),
                    "roundingDigest", roundingDigest,
                    "result", result));
            return new SimulationReport(
                    true, result, outputDigest, null, validation.astDigest(),
                    roundingDigest, state.evaluatedNodes, now);
        } catch (FormulaFailure failure) {
            return failed(
                    validation.astDigest(), roundingDigest, failure.code,
                    state.evaluatedNodes, now);
        }
    }

    private SimulationReport failed(
            String astDigest,
            String roundingDigest,
            FormulaErrorCode code,
            int evaluatedNodes,
            Instant now) {
        return new SimulationReport(
                false, null, null, code, astDigest, roundingDigest,
                evaluatedNodes, now);
    }

    private TypedValue evaluate(
            String nodeId,
            Map<String, FormulaNode> nodes,
            Map<String, TypedValue> inputs,
            RoundingPolicy rounding,
            EvaluationState state,
            Set<String> active) {
        state.check(nanoTime);
        TypedValue cached = state.cache.get(nodeId);
        if (cached != null) return cached;
        if (!active.add(nodeId)) {
            throw new FormulaFailure(FormulaErrorCode.CYCLE);
        }
        FormulaNode node = nodes.get(nodeId);
        if (node == null) throw new FormulaFailure(FormulaErrorCode.UNKNOWN_NODE);
        List<TypedValue> arguments = node.arguments().stream()
                .map(argument -> evaluate(argument, nodes, inputs, rounding, state, active))
                .toList();
        TypedValue result = switch (node.operator()) {
            case INPUT -> input(node, inputs);
            case LITERAL -> literal(node);
            case ADD, SUBTRACT, MIN, MAX -> sameTypeArithmetic(node, arguments);
            case MULTIPLY -> multiply(node, arguments);
            case DIVIDE -> divide(node, arguments);
            case COMPARE_EQ -> compareEqual(node, arguments);
            case COMPARE_LT -> compareLess(node, arguments);
            case AND, OR -> booleanOperation(node, arguments);
            case IF -> conditional(node, arguments);
            case ROUND -> rounded(node, arguments, rounding);
            case CONVERT_MINUTE_HOUR -> convertMinuteHour(node, arguments);
            case SUM -> sameTypeArithmetic(node, arguments);
            case LOOKUP, DATE_DIFFERENCE ->
                    throw new FormulaFailure(FormulaErrorCode.SANDBOX_VIOLATION);
        };
        active.remove(nodeId);
        state.cache.put(nodeId, result);
        return result;
    }

    private TypedValue input(FormulaNode node, Map<String, TypedValue> inputs) {
        TypedValue value = inputs.get(node.inputKey());
        if (value == null) throw new FormulaFailure(FormulaErrorCode.INPUT_REQUIRED);
        requireType(node.returnType(), value.type());
        requireCurrency(node.currency(), value.currency());
        return value;
    }

    private TypedValue literal(FormulaNode node) {
        if (node.returnType() == PayValueType.BOOLEAN) {
            if (!(node.value() instanceof Boolean booleanValue)) {
                throw new FormulaFailure(FormulaErrorCode.INVALID_LITERAL);
            }
            return new TypedValue(PayValueType.BOOLEAN, null, booleanValue, null);
        }
        if (!(node.value() instanceof String decimal)) {
            throw new FormulaFailure(FormulaErrorCode.INVALID_LITERAL);
        }
        try {
            return new TypedValue(node.returnType(), decimal, null, node.currency());
        } catch (IllegalArgumentException exception) {
            throw new FormulaFailure(FormulaErrorCode.INVALID_LITERAL);
        }
    }

    private TypedValue sameTypeArithmetic(FormulaNode node, List<TypedValue> values) {
        requireArity(values, node.operator() == FormulaOperator.SUM ? 1 : 2, 3);
        TypedValue first = values.getFirst();
        if (!first.type().numeric()) throw new FormulaFailure(FormulaErrorCode.TYPE);
        for (TypedValue value : values) {
            requireType(first.type(), value.type());
            requireCurrency(first.currency(), value.currency());
        }
        BigDecimal result = first.decimal();
        for (int index = 1; index < values.size(); index++) {
            BigDecimal value = values.get(index).decimal();
            result = switch (node.operator()) {
                case ADD, SUM -> result.add(value, INTERMEDIATE);
                case SUBTRACT -> result.subtract(value, INTERMEDIATE);
                case MIN -> result.min(value);
                case MAX -> result.max(value);
                default -> throw new FormulaFailure(FormulaErrorCode.SANDBOX_VIOLATION);
            };
        }
        requireType(node.returnType(), first.type());
        requireCurrency(node.currency(), first.currency());
        return decimalValue(node.returnType(), result, node.currency());
    }

    private TypedValue multiply(FormulaNode node, List<TypedValue> values) {
        requireArity(values, 2, 2);
        TypedValue left = values.get(0);
        TypedValue right = values.get(1);
        PayValueType expected = multiplicationType(left.type(), right.type());
        requireType(node.returnType(), expected);
        String currency = currencyForProduct(left, right);
        requireCurrency(node.currency(), currency);
        return decimalValue(
                expected, left.decimal().multiply(right.decimal(), INTERMEDIATE), currency);
    }

    private TypedValue divide(FormulaNode node, List<TypedValue> values) {
        requireArity(values, 2, 2);
        TypedValue numerator = values.get(0);
        TypedValue denominator = values.get(1);
        if (!numerator.type().numeric() || !denominator.type().numeric()) {
            throw new FormulaFailure(FormulaErrorCode.TYPE);
        }
        if (denominator.decimal().signum() == 0) {
            throw new FormulaFailure(FormulaErrorCode.SANDBOX_VIOLATION);
        }
        PayValueType expected = divisionType(numerator.type(), denominator.type());
        requireType(node.returnType(), expected);
        String currency = expected.currencyBearing() ? numerator.currency() : null;
        requireCurrency(node.currency(), currency);
        return decimalValue(
                expected,
                numerator.decimal().divide(denominator.decimal(), INTERMEDIATE),
                currency);
    }

    private TypedValue compareEqual(FormulaNode node, List<TypedValue> values) {
        requireArity(values, 2, 2);
        TypedValue left = values.get(0);
        TypedValue right = values.get(1);
        requireType(left.type(), right.type());
        requireCurrency(left.currency(), right.currency());
        requireType(node.returnType(), PayValueType.BOOLEAN);
        boolean equal = left.type() == PayValueType.BOOLEAN
                ? left.booleanValue().equals(right.booleanValue())
                : left.decimal().compareTo(right.decimal()) == 0;
        return new TypedValue(PayValueType.BOOLEAN, null, equal, null);
    }

    private TypedValue compareLess(FormulaNode node, List<TypedValue> values) {
        requireArity(values, 2, 2);
        TypedValue left = values.get(0);
        TypedValue right = values.get(1);
        if (!left.type().numeric()) throw new FormulaFailure(FormulaErrorCode.TYPE);
        requireType(left.type(), right.type());
        requireCurrency(left.currency(), right.currency());
        requireType(node.returnType(), PayValueType.BOOLEAN);
        return new TypedValue(
                PayValueType.BOOLEAN, null,
                left.decimal().compareTo(right.decimal()) < 0, null);
    }

    private TypedValue booleanOperation(FormulaNode node, List<TypedValue> values) {
        requireArity(values, 2, 2);
        requireType(node.returnType(), PayValueType.BOOLEAN);
        values.forEach(value -> requireType(PayValueType.BOOLEAN, value.type()));
        boolean result = node.operator() == FormulaOperator.AND
                ? values.stream().allMatch(TypedValue::booleanValue)
                : values.stream().anyMatch(TypedValue::booleanValue);
        return new TypedValue(PayValueType.BOOLEAN, null, result, null);
    }

    private TypedValue conditional(FormulaNode node, List<TypedValue> values) {
        requireArity(values, 3, 3);
        requireType(PayValueType.BOOLEAN, values.get(0).type());
        requireType(values.get(1).type(), values.get(2).type());
        requireType(node.returnType(), values.get(1).type());
        requireCurrency(values.get(1).currency(), values.get(2).currency());
        requireCurrency(node.currency(), values.get(1).currency());
        return values.get(0).booleanValue() ? values.get(1) : values.get(2);
    }

    private TypedValue rounded(
            FormulaNode node, List<TypedValue> values, RoundingPolicy rounding) {
        requireArity(values, 1, 1);
        TypedValue value = values.getFirst();
        if (!value.type().numeric()) throw new FormulaFailure(FormulaErrorCode.TYPE);
        requireType(node.returnType(), value.type());
        requireCurrency(node.currency(), value.currency());
        BigDecimal rounded = rounding.apply(value.decimal());
        return new TypedValue(
                value.type(), rounded.toPlainString(), null, value.currency());
    }

    private TypedValue convertMinuteHour(FormulaNode node, List<TypedValue> values) {
        requireArity(values, 1, 1);
        requireType(PayValueType.QUANTITY_MINUTE, values.getFirst().type());
        requireType(PayValueType.QUANTITY_HOUR, node.returnType());
        return decimalValue(
                PayValueType.QUANTITY_HOUR,
                values.getFirst().decimal().divide(new BigDecimal("60"), INTERMEDIATE),
                null);
    }

    private void validateShape(FormulaNode node, List<FormulaFinding> findings) {
        int arity = node.arguments().size();
        int minimum;
        int maximum;
        switch (node.operator()) {
            case INPUT, LITERAL -> {
                minimum = 0;
                maximum = 0;
            }
            case ROUND, CONVERT_MINUTE_HOUR, LOOKUP, DATE_DIFFERENCE -> {
                minimum = 1;
                maximum = 1;
            }
            case IF -> {
                minimum = 3;
                maximum = 3;
            }
            case SUM -> {
                minimum = 1;
                maximum = 3;
            }
            default -> {
                minimum = 2;
                maximum = 2;
            }
        }
        if (arity < minimum || arity > maximum) {
            findings.add(finding(
                    FormulaErrorCode.INVALID_ARITY, node.nodeId(),
                    "Formula operator argument count is invalid."));
        }
        if (node.operator() == FormulaOperator.INPUT && node.inputKey() == null) {
            findings.add(finding(
                    FormulaErrorCode.INPUT_REQUIRED, node.nodeId(),
                    "INPUT nodes require inputKey."));
        }
        if (node.operator() == FormulaOperator.LITERAL) {
            if (node.returnType() == PayValueType.BOOLEAN
                    ? !(node.value() instanceof Boolean)
                    : !(node.value() instanceof String)) {
                findings.add(finding(
                        FormulaErrorCode.INVALID_LITERAL, node.nodeId(),
                        "Literal value must match its declared type."));
            } else if (node.value() instanceof String decimal) {
                try {
                    PayrollConfigurationModels.canonicalDecimal(decimal, "literal");
                } catch (IllegalArgumentException exception) {
                    findings.add(finding(
                            FormulaErrorCode.INVALID_LITERAL, node.nodeId(),
                            "Numeric literal must be a canonical decimal string."));
                }
            }
        }
        if (node.returnType().currencyBearing() != (node.currency() != null)) {
            findings.add(finding(
                    FormulaErrorCode.CURRENCY_MISMATCH, node.nodeId(),
                    "Currency must be declared exactly for currency-bearing nodes."));
        }
        if (node.operator() == FormulaOperator.ROUND && node.roundingRef() == null) {
            findings.add(finding(
                    FormulaErrorCode.ROUNDING_REQUIRED, node.nodeId(),
                    "ROUND nodes require a versioned rounding reference."));
        }
        if (node.operator() == FormulaOperator.LOOKUP && node.lookupRef() == null) {
            findings.add(finding(
                    FormulaErrorCode.SANDBOX_VIOLATION, node.nodeId(),
                    "LOOKUP requires a versioned owner reference."));
        }
    }

    private void detectCyclesAndDepth(
            PayFormulaAst ast,
            Map<String, FormulaNode> nodes,
            List<FormulaFinding> findings) {
        Map<String, Visit> visits = new HashMap<>();
        detect(ast.rootNodeId(), nodes, visits, 0, findings);
    }

    private void detect(
            String nodeId,
            Map<String, FormulaNode> nodes,
            Map<String, Visit> visits,
            int depth,
            List<FormulaFinding> findings) {
        Visit visit = visits.get(nodeId);
        if (visit == Visit.ACTIVE) {
            findings.add(finding(
                    FormulaErrorCode.CYCLE, nodeId,
                    "Formula dependency graph must be acyclic."));
            return;
        }
        if (visit == Visit.DONE) return;
        if (depth > MAX_DEPTH) {
            findings.add(finding(
                    FormulaErrorCode.LIMIT_EXCEEDED, nodeId,
                    "Formula dependency depth exceeds the sandbox limit."));
            return;
        }
        FormulaNode node = nodes.get(nodeId);
        if (node == null) return;
        visits.put(nodeId, Visit.ACTIVE);
        node.arguments().forEach(argument ->
                detect(argument, nodes, visits, depth + 1, findings));
        visits.put(nodeId, Visit.DONE);
    }

    private void validateTypes(
            PayFormulaAst ast,
            Map<String, FormulaNode> nodes,
            List<FormulaFinding> findings) {
        for (FormulaNode node : ast.nodes()) {
            try {
                List<FormulaNode> arguments = node.arguments().stream().map(nodes::get).toList();
                validateStaticType(node, arguments);
            } catch (FormulaFailure failure) {
                findings.add(finding(
                        failure.code, node.nodeId(),
                        failure.code == FormulaErrorCode.CURRENCY_MISMATCH
                                ? "Formula currency types are incompatible."
                                : "Formula value types or units are incompatible."));
            }
        }
    }

    private void validateStaticType(FormulaNode node, List<FormulaNode> arguments) {
        switch (node.operator()) {
            case INPUT, LITERAL, LOOKUP, DATE_DIFFERENCE -> {
                return;
            }
            case ADD, SUBTRACT, MIN, MAX, SUM -> {
                PayValueType type = arguments.getFirst().returnType();
                String currency = arguments.getFirst().currency();
                arguments.forEach(argument -> {
                    requireType(type, argument.returnType());
                    requireCurrency(currency, argument.currency());
                });
                requireType(node.returnType(), type);
                requireCurrency(node.currency(), currency);
            }
            case MULTIPLY -> {
                PayValueType type = multiplicationType(
                        arguments.get(0).returnType(), arguments.get(1).returnType());
                requireType(node.returnType(), type);
                requireCurrency(node.currency(), currencyForProduct(
                        arguments.get(0), arguments.get(1)));
            }
            case DIVIDE -> {
                PayValueType type = divisionType(
                        arguments.get(0).returnType(), arguments.get(1).returnType());
                requireType(node.returnType(), type);
            }
            case COMPARE_EQ, COMPARE_LT -> {
                requireType(arguments.get(0).returnType(), arguments.get(1).returnType());
                requireCurrency(arguments.get(0).currency(), arguments.get(1).currency());
                requireType(node.returnType(), PayValueType.BOOLEAN);
            }
            case AND, OR -> {
                arguments.forEach(argument ->
                        requireType(PayValueType.BOOLEAN, argument.returnType()));
                requireType(node.returnType(), PayValueType.BOOLEAN);
            }
            case IF -> {
                requireType(PayValueType.BOOLEAN, arguments.get(0).returnType());
                requireType(arguments.get(1).returnType(), arguments.get(2).returnType());
                requireType(node.returnType(), arguments.get(1).returnType());
                requireCurrency(arguments.get(1).currency(), arguments.get(2).currency());
                requireCurrency(node.currency(), arguments.get(1).currency());
            }
            case ROUND -> {
                requireType(node.returnType(), arguments.getFirst().returnType());
                requireCurrency(node.currency(), arguments.getFirst().currency());
            }
            case CONVERT_MINUTE_HOUR -> {
                requireType(PayValueType.QUANTITY_MINUTE, arguments.getFirst().returnType());
                requireType(PayValueType.QUANTITY_HOUR, node.returnType());
            }
        }
    }

    private PayValueType multiplicationType(PayValueType left, PayValueType right) {
        if (left == PayValueType.NUMBER) return right;
        if (right == PayValueType.NUMBER) return left;
        if (pair(left, right, PayValueType.MONEY, PayValueType.RATE)) {
            return PayValueType.MONEY;
        }
        if (pair(left, right, PayValueType.QUANTITY_HOUR, PayValueType.UNIT_PRICE_PER_HOUR)
                || pair(left, right, PayValueType.QUANTITY_MINUTE, PayValueType.UNIT_PRICE_PER_MINUTE)
                || pair(left, right, PayValueType.QUANTITY_DAY, PayValueType.UNIT_PRICE_PER_DAY)) {
            return PayValueType.MONEY;
        }
        throw new FormulaFailure(FormulaErrorCode.UNIT_MISMATCH);
    }

    private PayValueType divisionType(PayValueType numerator, PayValueType denominator) {
        if (denominator == PayValueType.NUMBER) return numerator;
        if (numerator == PayValueType.MONEY) {
            return switch (denominator) {
                case QUANTITY_MINUTE -> PayValueType.UNIT_PRICE_PER_MINUTE;
                case QUANTITY_HOUR -> PayValueType.UNIT_PRICE_PER_HOUR;
                case QUANTITY_DAY -> PayValueType.UNIT_PRICE_PER_DAY;
                default -> throw new FormulaFailure(FormulaErrorCode.UNIT_MISMATCH);
            };
        }
        if (numerator == denominator) return PayValueType.NUMBER;
        throw new FormulaFailure(FormulaErrorCode.UNIT_MISMATCH);
    }

    private String currencyForProduct(TypedValue left, TypedValue right) {
        if (left.currency() != null && right.currency() != null) {
            requireCurrency(left.currency(), right.currency());
        }
        return left.currency() == null ? right.currency() : left.currency();
    }

    private String currencyForProduct(FormulaNode left, FormulaNode right) {
        if (left.currency() != null && right.currency() != null) {
            requireCurrency(left.currency(), right.currency());
        }
        return left.currency() == null ? right.currency() : left.currency();
    }

    private boolean pair(
            PayValueType left,
            PayValueType right,
            PayValueType first,
            PayValueType second) {
        return (left == first && right == second) || (left == second && right == first);
    }

    private TypedValue decimalValue(
            PayValueType type, BigDecimal decimal, String currency) {
        BigDecimal canonicalValue = decimal.stripTrailingZeros();
        if (canonicalValue.scale() < 0) canonicalValue = canonicalValue.setScale(0);
        if (canonicalValue.scale() > 12) {
            canonicalValue = canonicalValue.setScale(12, RoundingMode.HALF_EVEN).stripTrailingZeros();
        }
        return new TypedValue(type, canonicalValue.toPlainString(), null, currency);
    }

    private void requireArity(List<?> values, int minimum, int maximum) {
        if (values.size() < minimum || values.size() > maximum) {
            throw new FormulaFailure(FormulaErrorCode.INVALID_ARITY);
        }
    }

    private void requireType(PayValueType expected, PayValueType actual) {
        if (expected != actual) throw new FormulaFailure(FormulaErrorCode.UNIT_MISMATCH);
    }

    private void requireCurrency(String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            throw new FormulaFailure(FormulaErrorCode.CURRENCY_MISMATCH);
        }
    }

    private FormulaFinding finding(
            FormulaErrorCode code, String nodeId, String message) {
        return new FormulaFinding(code, nodeId, message);
    }

    private enum Visit { ACTIVE, DONE }

    private static final class EvaluationState {
        private final long deadline;
        private final Map<String, TypedValue> cache = new HashMap<>();
        private int evaluatedNodes;

        private EvaluationState(long deadline) {
            this.deadline = deadline;
        }

        private void check(LongSupplier nanoTime) {
            evaluatedNodes++;
            if (evaluatedNodes > MAX_EVALUATED_NODES) {
                throw new FormulaFailure(FormulaErrorCode.LIMIT_EXCEEDED);
            }
            if (nanoTime.getAsLong() > deadline) {
                throw new FormulaFailure(FormulaErrorCode.TIMEOUT);
            }
        }
    }

    private static final class FormulaFailure extends RuntimeException {
        private final FormulaErrorCode code;

        private FormulaFailure(FormulaErrorCode code) {
            super(code.name(), null, false, false);
            this.code = code;
        }
    }
}
