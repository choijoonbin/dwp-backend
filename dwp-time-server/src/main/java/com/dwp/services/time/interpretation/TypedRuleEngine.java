package com.dwp.services.time.interpretation;

import com.dwp.services.time.interpretation.InterpretationModels.InputReference;
import com.dwp.services.time.interpretation.InterpretationModels.RuleOperator;
import com.dwp.services.time.interpretation.InterpretationModels.RuleOutput;
import com.dwp.services.time.interpretation.InterpretationModels.RuleValueType;
import com.dwp.services.time.interpretation.InterpretationModels.TypedRuleAst;
import com.dwp.services.time.interpretation.InterpretationModels.TypedRuleNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates and evaluates the closed TIM_RULE_AST_V1 registry without dynamic code execution. */
public final class TypedRuleEngine {

    public static final String REGISTRY_ID = "TIM_RULE_AST_V1";
    public static final int REGISTRY_VERSION = 1;
    public static final int MAX_NODES = 512;
    public static final int MAX_DEPTH = 32;

    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern DECIMAL = Pattern.compile(
            "^(?!-0(?:\\.0+)?$)-?(0|[1-9][0-9]*)(\\.[0-9]{1,12})?$");
    private static final Set<RuleValueType> NUMERIC = Set.of(
            RuleValueType.NUMBER,
            RuleValueType.RATE,
            RuleValueType.QUANTITY_MINUTE,
            RuleValueType.QUANTITY_HOUR,
            RuleValueType.QUANTITY_DAY);

    public enum RuleError {
        UNKNOWN_INPUT,
        UNKNOWN_OPERATOR,
        ARITY_MISMATCH,
        TYPE_MISMATCH,
        UNIT_MISMATCH,
        DIVIDE_BY_ZERO,
        CYCLE,
        LIMIT_EXCEEDED,
        ROUNDING_SOURCE_STALE
    }

    public static final class RuleValidationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final RuleError code;

        RuleValidationException(RuleError code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public RuleError code() {
            return code;
        }
    }

    public record RuleInput(
            RuleValueType type,
            BigDecimal numericValue,
            Boolean booleanValue,
            InputReference evidence) {

        public RuleInput {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(evidence, "evidence");
            if (type == RuleValueType.BOOLEAN) {
                if (booleanValue == null || numericValue != null) {
                    throw new IllegalArgumentException("boolean input value is invalid");
                }
            } else if (!NUMERIC.contains(type) || numericValue == null || booleanValue != null) {
                throw new IllegalArgumentException("numeric input value is invalid");
            }
        }

        public static RuleInput quantityMinutes(long value, InputReference evidence) {
            return new RuleInput(
                    RuleValueType.QUANTITY_MINUTE, BigDecimal.valueOf(value), null, evidence);
        }
    }

    public record Value(RuleValueType type, BigDecimal numeric, Boolean bool) {
        public Value {
            Objects.requireNonNull(type, "type");
            if (type == RuleValueType.BOOLEAN) {
                if (bool == null || numeric != null) {
                    throw new IllegalArgumentException("boolean value is invalid");
                }
            } else if (!NUMERIC.contains(type) || numeric == null || bool != null) {
                throw new IllegalArgumentException("numeric value is invalid");
            }
        }

        public long exactLong() {
            return numeric.longValueExact();
        }
    }

    public record Evaluation(Value root, Map<String, Value> values) {
        public Evaluation {
            Objects.requireNonNull(root, "root");
            values = Map.copyOf(values);
        }
    }

    public void validate(TypedRuleAst ast) {
        Objects.requireNonNull(ast, "ast");
        if (!REGISTRY_ID.equals(ast.registryId()) || ast.registryVersion() != REGISTRY_VERSION) {
            throw failure(RuleError.UNKNOWN_OPERATOR, "The rule registry is not supported.");
        }
        if (ast.nodes().size() > MAX_NODES) {
            throw failure(RuleError.LIMIT_EXCEEDED, "The rule node limit was exceeded.");
        }
        Map<String, TypedRuleNode> nodes = index(ast);
        if (!nodes.containsKey(ast.rootNodeId())) {
            throw failure(RuleError.UNKNOWN_INPUT, "The root node is absent.");
        }
        validateReferences(nodes);
        validateAcyclicAndDepth(nodes);
        nodes.values().forEach(node -> validateNode(node, nodes));
    }

    public Evaluation evaluate(TypedRuleAst ast, Map<String, RuleInput> inputs) {
        validate(ast);
        Map<String, TypedRuleNode> nodes = index(ast);
        Map<String, Value> values = new LinkedHashMap<>();
        Value root = evaluateNode(ast.rootNodeId(), nodes, safe(inputs), values);
        return new Evaluation(root, values);
    }

    public static String digest(List<RuleOutput> outputs) {
        Objects.requireNonNull(outputs, "outputs");
        List<Object> canonical = new ArrayList<>();
        canonical.add("TypedRuleVersion.v1");
        outputs.stream()
                .sorted(Comparator.comparing(output -> output.outputCode().name()))
                .forEach(output -> {
                    canonical.add(output.outputCode().name());
                    appendAst(canonical, output.expression());
                });
        return InterpretationDigests.sha256(canonical.toArray());
    }

    private static void appendAst(List<Object> canonical, TypedRuleAst ast) {
        canonical.add(ast.registryId());
        canonical.add(ast.registryVersion());
        canonical.add(ast.rootNodeId());
        ast.nodes().stream().sorted(Comparator.comparing(TypedRuleNode::nodeId)).forEach(node -> {
            canonical.add(node.nodeId());
            canonical.add(node.operator().name());
            canonical.add(node.arguments());
            canonical.add(node.returnType().name());
            canonical.add(node.value());
            appendRef(canonical, node.inputRef());
            appendRef(canonical, node.roundingRef());
        });
    }

    private static void appendRef(List<Object> canonical, InputReference reference) {
        if (reference == null) {
            canonical.add(null);
            return;
        }
        canonical.add(reference.owner());
        canonical.add(reference.schemaId());
        canonical.add(reference.publicId());
        canonical.add(reference.revision());
        canonical.add(reference.contentHash());
        canonical.add(reference.validFrom());
        canonical.add(reference.validToExclusive());
    }

    private static Map<String, TypedRuleNode> index(TypedRuleAst ast) {
        Map<String, TypedRuleNode> result = new LinkedHashMap<>();
        for (TypedRuleNode node : ast.nodes()) {
            if (!NODE_ID.matcher(node.nodeId()).matches() || result.put(node.nodeId(), node) != null) {
                throw failure(RuleError.UNKNOWN_INPUT, "Rule node identities must be unique.");
            }
        }
        return result;
    }

    private static void validateReferences(Map<String, TypedRuleNode> nodes) {
        for (TypedRuleNode node : nodes.values()) {
            for (String argument : node.arguments()) {
                if (!nodes.containsKey(argument)) {
                    throw failure(RuleError.UNKNOWN_INPUT,
                            "Rule node " + node.nodeId() + " references an unknown input.");
                }
            }
        }
    }

    private static void validateAcyclicAndDepth(Map<String, TypedRuleNode> nodes) {
        Set<String> visiting = new HashSet<>();
        Map<String, Integer> depths = new HashMap<>();
        for (String nodeId : nodes.keySet().stream().sorted().toList()) {
            if (depth(nodeId, nodes, visiting, depths) > MAX_DEPTH) {
                throw failure(RuleError.LIMIT_EXCEEDED, "Typed rule AST depth exceeds 32.");
            }
        }
    }

    private static int depth(
            String nodeId,
            Map<String, TypedRuleNode> nodes,
            Set<String> visiting,
            Map<String, Integer> depths) {
        Integer known = depths.get(nodeId);
        if (known != null) return known;
        if (!visiting.add(nodeId)) {
            throw failure(RuleError.CYCLE, "Typed rule AST contains a cycle.");
        }
        int depth = 1;
        for (String argument : nodes.get(nodeId).arguments()) {
            depth = Math.max(depth, Math.addExact(
                    1, depth(argument, nodes, visiting, depths)));
        }
        visiting.remove(nodeId);
        depths.put(nodeId, depth);
        return depth;
    }

    private static void validateNode(TypedRuleNode node, Map<String, TypedRuleNode> nodes) {
        RuleOperator operator = node.operator();
        switch (operator) {
            case QUANTITY_LITERAL -> {
                arity(node, 0);
                requireQuantity(node.returnType(), node.nodeId());
                requireDecimal(node);
                requireNoReferences(node);
            }
            case RATE_LITERAL -> {
                arity(node, 0);
                requireType(node, RuleValueType.RATE);
                requireDecimal(node);
                requireNoReferences(node);
            }
            case BOOLEAN_LITERAL -> {
                arity(node, 0);
                requireType(node, RuleValueType.BOOLEAN);
                if (!(node.value() instanceof Boolean) || node.inputRef() != null
                        || node.roundingRef() != null) {
                    throw failure(RuleError.TYPE_MISMATCH, "Boolean literal is invalid.");
                }
            }
            case INPUT -> {
                arity(node, 0);
                if (node.value() != null || node.inputRef() == null || node.roundingRef() != null) {
                    throw failure(RuleError.UNKNOWN_INPUT, "Published input evidence is required.");
                }
            }
            case ADD, SUBTRACT, MIN, MAX -> {
                arity(node, 2);
                sameNumeric(node, nodes);
            }
            case MULTIPLY -> {
                arity(node, 2);
                multiply(node, nodes);
            }
            case DIVIDE -> {
                arity(node, 2);
                divideSignature(node, nodes);
            }
            case COMPARE_LT -> {
                arity(node, 2);
                requireType(node, RuleValueType.BOOLEAN);
                TypedRuleNode left = child(node, 0, nodes);
                TypedRuleNode right = child(node, 1, nodes);
                requireSameComparable(left.returnType(), right.returnType());
            }
            case COMPARE_EQ -> {
                arity(node, 2);
                requireType(node, RuleValueType.BOOLEAN);
                TypedRuleNode left = child(node, 0, nodes);
                TypedRuleNode right = child(node, 1, nodes);
                requireSameType(left.returnType(), right.returnType());
            }
            case AND, OR -> {
                arity(node, 2);
                requireType(node, RuleValueType.BOOLEAN);
                requireType(child(node, 0, nodes), RuleValueType.BOOLEAN);
                requireType(child(node, 1, nodes), RuleValueType.BOOLEAN);
            }
            case IF -> {
                arity(node, 3);
                requireType(child(node, 0, nodes), RuleValueType.BOOLEAN);
                RuleValueType trueType = child(node, 1, nodes).returnType();
                RuleValueType falseType = child(node, 2, nodes).returnType();
                requireSameType(trueType, falseType);
                requireSameType(node.returnType(), trueType);
            }
            case ROUND -> {
                arity(node, 1);
                TypedRuleNode value = child(node, 0, nodes);
                requireNumeric(value.returnType(), node.nodeId());
                requireSameType(node.returnType(), value.returnType());
                if (node.roundingRef() == null) {
                    throw failure(
                            RuleError.ROUNDING_SOURCE_STALE,
                            "ROUND requires a published rounding reference.");
                }
            }
        }
        if (!Set.of(
                        RuleOperator.QUANTITY_LITERAL,
                        RuleOperator.RATE_LITERAL,
                        RuleOperator.BOOLEAN_LITERAL,
                        RuleOperator.INPUT,
                        RuleOperator.ROUND)
                .contains(operator)
                && (node.value() != null || node.inputRef() != null || node.roundingRef() != null)) {
            throw failure(RuleError.TYPE_MISMATCH, "Operator evidence fields are invalid.");
        }
    }

    private static Value evaluateNode(
            String nodeId,
            Map<String, TypedRuleNode> nodes,
            Map<String, RuleInput> inputs,
            Map<String, Value> values) {
        Value existing = values.get(nodeId);
        if (existing != null) return existing;
        TypedRuleNode node = nodes.get(nodeId);
        Value value = switch (node.operator()) {
            case QUANTITY_LITERAL, RATE_LITERAL -> numericLiteral(node);
            case BOOLEAN_LITERAL -> new Value(node.returnType(), null, (Boolean) node.value());
            case INPUT -> inputValue(node, inputs);
            case ADD -> numericResult(node, values(node, nodes, inputs, values),
                    (left, right) -> left.add(right));
            case SUBTRACT -> numericResult(node, values(node, nodes, inputs, values),
                    (left, right) -> left.subtract(right));
            case MULTIPLY -> numericResult(node, values(node, nodes, inputs, values),
                    (left, right) -> left.multiply(right));
            case DIVIDE -> divide(node, values(node, nodes, inputs, values));
            case MIN -> numericResult(node, values(node, nodes, inputs, values), BigDecimal::min);
            case MAX -> numericResult(node, values(node, nodes, inputs, values), BigDecimal::max);
            case COMPARE_LT -> compare(node, values(node, nodes, inputs, values), true);
            case COMPARE_EQ -> compare(node, values(node, nodes, inputs, values), false);
            case AND -> booleanResult(node, values(node, nodes, inputs, values), true);
            case OR -> booleanResult(node, values(node, nodes, inputs, values), false);
            case IF -> conditional(node, nodes, inputs, values);
            case ROUND -> rounded(node, values(node, nodes, inputs, values));
        };
        values.put(nodeId, value);
        return value;
    }

    private static List<Value> values(
            TypedRuleNode node,
            Map<String, TypedRuleNode> nodes,
            Map<String, RuleInput> inputs,
            Map<String, Value> values) {
        return node.arguments().stream()
                .map(argument -> evaluateNode(argument, nodes, inputs, values))
                .toList();
    }

    private static Value numericLiteral(TypedRuleNode node) {
        return new Value(node.returnType(), new BigDecimal((String) node.value()), null);
    }

    private static Value inputValue(TypedRuleNode node, Map<String, RuleInput> inputs) {
        RuleInput input = inputs.get(node.nodeId());
        if (input == null || input.type() != node.returnType()
                || !input.evidence().equals(node.inputRef())) {
            throw failure(RuleError.UNKNOWN_INPUT,
                    "Input " + node.nodeId() + " is absent, stale, or differently typed.");
        }
        return input.type() == RuleValueType.BOOLEAN
                ? new Value(input.type(), null, input.booleanValue())
                : new Value(input.type(), input.numericValue(), null);
    }

    private static Value numericResult(
            TypedRuleNode node, List<Value> values, NumericOperation operation) {
        return new Value(
                node.returnType(), operation.apply(values.get(0).numeric(), values.get(1).numeric()), null);
    }

    private static Value divide(TypedRuleNode node, List<Value> values) {
        if (values.get(1).numeric().compareTo(BigDecimal.ZERO) == 0) {
            throw failure(RuleError.DIVIDE_BY_ZERO, "Typed rule division by zero.");
        }
        return new Value(
                node.returnType(),
                values.get(0).numeric().divide(values.get(1).numeric(), 12, RoundingMode.HALF_EVEN)
                        .stripTrailingZeros(),
                null);
    }

    private static Value compare(TypedRuleNode node, List<Value> values, boolean lessThan) {
        Value left = values.get(0);
        Value right = values.get(1);
        boolean result;
        if (left.type() == RuleValueType.BOOLEAN) {
            result = left.bool().equals(right.bool());
        } else {
            int comparison = left.numeric().compareTo(right.numeric());
            result = lessThan ? comparison < 0 : comparison == 0;
        }
        return new Value(node.returnType(), null, result);
    }

    private static Value booleanResult(
            TypedRuleNode node, List<Value> values, boolean and) {
        boolean result = and
                ? values.get(0).bool() && values.get(1).bool()
                : values.get(0).bool() || values.get(1).bool();
        return new Value(node.returnType(), null, result);
    }

    private static Value conditional(
            TypedRuleNode node,
            Map<String, TypedRuleNode> nodes,
            Map<String, RuleInput> inputs,
            Map<String, Value> values) {
        Value condition = evaluateNode(node.arguments().get(0), nodes, inputs, values);
        return evaluateNode(node.arguments().get(condition.bool() ? 1 : 2), nodes, inputs, values);
    }

    private static Value rounded(TypedRuleNode node, List<Value> values) {
        return new Value(
                node.returnType(), values.getFirst().numeric().setScale(0, RoundingMode.HALF_EVEN), null);
    }

    private static void arity(TypedRuleNode node, int expected) {
        if (node.arguments().size() != expected) {
            throw failure(
                    RuleError.ARITY_MISMATCH,
                    node.operator() + " requires exactly " + expected + " arguments.");
        }
    }

    private static void sameNumeric(TypedRuleNode node, Map<String, TypedRuleNode> nodes) {
        TypedRuleNode left = child(node, 0, nodes);
        TypedRuleNode right = child(node, 1, nodes);
        requireNumeric(left.returnType(), node.nodeId());
        requireSameType(left.returnType(), right.returnType());
        requireSameType(node.returnType(), left.returnType());
    }

    private static void multiply(TypedRuleNode node, Map<String, TypedRuleNode> nodes) {
        RuleValueType left = child(node, 0, nodes).returnType();
        RuleValueType right = child(node, 1, nodes).returnType();
        requireNumeric(left, node.nodeId());
        requireNumeric(right, node.nodeId());
        RuleValueType registered;
        if (isQuantity(left) && isQuantity(right)) {
            throw failure(RuleError.UNIT_MISMATCH,
                    "MULTIPLY does not register quantity by quantity units.");
        } else if (isQuantity(left)) {
            requireScalar(right, node.nodeId());
            registered = left;
        } else if (isQuantity(right)) {
            requireScalar(left, node.nodeId());
            registered = right;
        } else if (left == RuleValueType.RATE && right == RuleValueType.RATE) {
            throw failure(RuleError.TYPE_MISMATCH,
                    "MULTIPLY does not register RATE by RATE.");
        } else {
            registered = left == RuleValueType.RATE || right == RuleValueType.RATE
                    ? RuleValueType.RATE
                    : RuleValueType.NUMBER;
        }
        requireSameType(node.returnType(), registered);
    }

    private static void divideSignature(TypedRuleNode node, Map<String, TypedRuleNode> nodes) {
        RuleValueType left = child(node, 0, nodes).returnType();
        RuleValueType right = child(node, 1, nodes).returnType();
        requireNumeric(left, node.nodeId());
        requireNumeric(right, node.nodeId());
        RuleValueType registered;
        if (isQuantity(left) && isQuantity(right)) {
            requireSameType(left, right);
            registered = RuleValueType.NUMBER;
        } else if (isQuantity(left)) {
            requireScalar(right, node.nodeId());
            registered = left;
        } else if (isQuantity(right)) {
            throw failure(RuleError.UNIT_MISMATCH,
                    "DIVIDE does not register a scalar divided by a quantity.");
        } else if (left == RuleValueType.RATE && right == RuleValueType.RATE) {
            registered = RuleValueType.NUMBER;
        } else if (left == RuleValueType.RATE && right == RuleValueType.NUMBER) {
            registered = RuleValueType.RATE;
        } else if (left == RuleValueType.NUMBER && right == RuleValueType.NUMBER) {
            registered = RuleValueType.NUMBER;
        } else {
            throw failure(RuleError.TYPE_MISMATCH,
                    "DIVIDE operand types have no registered result.");
        }
        requireSameType(node.returnType(), registered);
    }

    private static void requireScalar(RuleValueType type, String nodeId) {
        if (type != RuleValueType.NUMBER && type != RuleValueType.RATE) {
            throw failure(RuleError.TYPE_MISMATCH, nodeId + " requires NUMBER or RATE.");
        }
    }

    private static TypedRuleNode child(
            TypedRuleNode node, int index, Map<String, TypedRuleNode> nodes) {
        return nodes.get(node.arguments().get(index));
    }

    private static void requireDecimal(TypedRuleNode node) {
        if (!(node.value() instanceof String text) || !DECIMAL.matcher(text).matches()) {
            throw failure(RuleError.TYPE_MISMATCH, "Literal decimal is not canonical.");
        }
    }

    private static void requireNoReferences(TypedRuleNode node) {
        if (node.inputRef() != null || node.roundingRef() != null) {
            throw failure(RuleError.TYPE_MISMATCH, "Literal references are forbidden.");
        }
    }

    private static void requireQuantity(RuleValueType type, String nodeId) {
        if (!Set.of(
                        RuleValueType.QUANTITY_MINUTE,
                        RuleValueType.QUANTITY_HOUR,
                        RuleValueType.QUANTITY_DAY)
                .contains(type)) {
            throw failure(RuleError.UNIT_MISMATCH, nodeId + " is not a quantity.");
        }
    }

    private static void requireNumeric(RuleValueType type, String nodeId) {
        if (!NUMERIC.contains(type)) {
            throw failure(RuleError.TYPE_MISMATCH, nodeId + " is not numeric.");
        }
    }

    private static void requireType(TypedRuleNode node, RuleValueType expected) {
        requireSameType(node.returnType(), expected);
    }

    private static void requireSameComparable(RuleValueType left, RuleValueType right) {
        requireNumeric(left, "comparison");
        requireSameType(left, right);
    }

    private static void requireSameType(RuleValueType actual, RuleValueType expected) {
        if (actual != expected) {
            RuleError code = isQuantity(actual) && isQuantity(expected)
                    ? RuleError.UNIT_MISMATCH
                    : RuleError.TYPE_MISMATCH;
            throw failure(code, "Typed rule operands do not have the same declared type.");
        }
    }

    private static boolean isQuantity(RuleValueType type) {
        return Set.of(
                        RuleValueType.QUANTITY_MINUTE,
                        RuleValueType.QUANTITY_HOUR,
                        RuleValueType.QUANTITY_DAY)
                .contains(type);
    }

    private static Map<String, RuleInput> safe(Map<String, RuleInput> inputs) {
        return inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    private static RuleValidationException failure(RuleError code, String message) {
        return new RuleValidationException(code, message);
    }

    @FunctionalInterface
    private interface NumericOperation {
        BigDecimal apply(BigDecimal left, BigDecimal right);
    }
}
