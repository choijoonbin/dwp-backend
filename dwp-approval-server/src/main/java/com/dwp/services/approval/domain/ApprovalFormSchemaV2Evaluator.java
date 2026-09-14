package com.dwp.services.approval.domain;

import com.dwp.services.approval.domain.ApprovalFormSchemaV2.Condition;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2.Evaluation;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2.Expression;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2.Field;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2.Scope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evaluates only compiler-approved ASTs; hidden inputs never reach the returned payload. */
public final class ApprovalFormSchemaV2Evaluator {

    /** Remove derived values from a trusted stored amendment base, not from client-supplied patches. */
    public Map<String, Object> withoutComputedValues(ApprovalFormSchemaV2 schema, Map<String, Object> stored) {
        return withoutComputedValues(schema.scope, stored);
    }

    private Map<String, Object> withoutComputedValues(Scope scope, Map<String, Object> stored) {
        Map<String, Object> result = new LinkedHashMap<>(stored);
        for (Field field : scope.fields().values()) {
            if (field.calculation() != null) result.remove(field.key());
            if (field.group() && result.get(field.key()) instanceof List<?> rows) {
                List<Map<String, Object>> cleanRows = new ArrayList<>();
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?>)) invalid(field.key());
                    cleanRows.add(withoutComputedValues(field.children(), rowMap(row)));
                }
                result.put(field.key(), cleanRows);
            }
        }
        return result;
    }

    public Evaluation evaluate(ApprovalFormSchemaV2 schema, Map<String, Object> input, boolean submitting) {
        if (schema == null || input == null) throw ApprovalFormSchemaV2Compiler.invalid("Schema and payload are required.");
        Set<String> visible = new HashSet<>();
        Set<String> required = new HashSet<>();
        Budget budget = new Budget();
        Map<String, Object> result = scope(schema.scope, input, submitting, "", visible, required, budget);
        if (input.containsKey("createdFrom")) {
            Object marker = input.get("createdFrom");
            if (!(marker instanceof String text) || text.length() > 160) invalid("createdFrom");
            result.put("createdFrom", marker);
        }
        return new Evaluation(result, visible, required, schema.sha256());
    }

    private Map<String, Object> scope(Scope schema, Map<String, Object> input, boolean submitting,
            String prefix, Set<String> visible, Set<String> required, Budget budget) {
        if (input.size() > schema.fields().size() + (prefix.isEmpty() ? 1 : 0)) invalid(prefix);
        for (String key : input.keySet()) {
            if (!schema.fields().containsKey(key) && !(prefix.isEmpty() && "createdFrom".equals(key))) invalid(prefix + key);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        // Dependency order ensures conditions consume normalized, visible inputs rather than stale raw data.
        for (Field field : schema.order()) {
            String path = prefix + field.key();
            if (field.visibleWhen() != null && !condition(field.visibleWhen(), schema, result, budget)) continue;
            visible.add(path);
            boolean mandatory = field.required()
                    || (field.requiredWhen() != null && condition(field.requiredWhen(), schema, result, budget));
            if (mandatory) required.add(path);
            Object raw = input.get(field.key());
            if (field.calculation() != null) {
                BigDecimal calculated = expression(field.calculation(), result, budget);
                if (!empty(raw) && (calculated == null
                        || ApprovalFormSchemaV2Canonical.transportDecimal(raw).compareTo(calculated) != 0)) invalid(path);
                raw = calculated;
            }
            if (empty(raw)) {
                if (submitting && mandatory) invalid(path);
                continue;
            }
            if (field.group()) {
                List<Map<String, Object>> rows = rows(field, raw, submitting, path, visible, required, budget);
                if (submitting && rows.size() < Math.max(field.minRows(), mandatory ? 1 : 0)) invalid(path);
                result.put(field.key(), rows);
            } else {
                Object value = scalar(field, raw, path);
                result.put(field.key(), value);
            }
        }
        return result;
    }

    private List<Map<String, Object>> rows(Field field, Object raw, boolean submitting, String path,
            Set<String> visible, Set<String> required, Budget budget) {
        if (!(raw instanceof List<?> values) || values.size() > field.maxRows()) invalid(path);
        List<Map<String, Object>> result = new ArrayList<>();
        List<?> values = (List<?>) raw;
        for (int index = 0; index < values.size(); index++) {
            Object row = values.get(index);
            if (!(row instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) invalid(path);
            result.add(scope(field.children(), rowMap(row), submitting, path + "[" + index + "].", visible, required, budget));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rowMap(Object value) { return (Map<String, Object>) value; }

    private Object scalar(Field field, Object raw, String path) {
        if (field.numeric()) {
            BigDecimal value = field.calculation() == null ? ApprovalFormSchemaV2Canonical.transportDecimal(raw)
                    : ApprovalFormSchemaV2Compiler.decimal(raw);
            if ((field.min() != null && value.compareTo(field.min()) < 0)
                    || (field.max() != null && value.compareTo(field.max()) > 0)) invalid(path);
            return value.toPlainString();
        }
        if (!(raw instanceof String text) || text.length() > 10000) invalid(path);
        String text = (String) raw;
        switch (field.type()) {
            case "DATE" -> ApprovalFormSchemaV2Compiler.date(text);
            case "SELECT" -> { if (!field.options().contains(text)) invalid(path); }
            case "TEXT", "TEXTAREA", "USER" -> {
                // Count Unicode code points, matching Array.from(value).length in the client contract.
                int length = text.codePointCount(0, text.length());
                if (length < field.minLength() || length > field.maxLength()) invalid(path);
            }
            default -> invalid(path);
        }
        return text;
    }

    private boolean condition(Condition condition, Scope schema, Map<String, Object> values, Budget budget) {
        tick(budget);
        switch (condition.op()) {
            case "AND" -> { return condition.args().stream().allMatch(child -> condition(child, schema, values, budget)); }
            case "OR" -> { return condition.args().stream().anyMatch(child -> condition(child, schema, values, budget)); }
            case "NOT" -> { return !condition(condition.args().getFirst(), schema, values, budget); }
            default -> {
                Object actual = values.get(condition.field());
                if ("PRESENT".equals(condition.op())) return !empty(actual);
                if (empty(actual)) return false;
                boolean numeric = schema.fields().get(condition.field()).numeric();
                if ("IN".equals(condition.op())) {
                    return condition.values().stream().anyMatch(literal -> compare(actual, literal, numeric) == 0);
                }
                int comparison = compare(actual, condition.values().getFirst(), numeric);
                return switch (condition.op()) {
                    case "EQ" -> comparison == 0;
                    case "NE" -> comparison != 0;
                    case "GT" -> comparison > 0;
                    case "GTE" -> comparison >= 0;
                    case "LT" -> comparison < 0;
                    case "LTE" -> comparison <= 0;
                    default -> throw ApprovalFormSchemaV2Compiler.invalid("Unknown compiled condition.");
                };
            }
        }
    }

    private int compare(Object actual, Object literal, boolean numeric) {
        return numeric ? ApprovalFormSchemaV2Compiler.decimal(actual).compareTo(ApprovalFormSchemaV2Compiler.decimal(literal))
                : ((String) actual).compareTo((String) literal);
    }

    private BigDecimal expression(Expression expression, Map<String, Object> values, Budget budget) {
        tick(budget);
        if ("CONST".equals(expression.op())) return expression.value();
        if ("FIELD".equals(expression.op())) {
            Object value = values.get(expression.field());
            return value == null ? null : ApprovalFormSchemaV2Compiler.decimal(value);
        }
        if ("SUM".equals(expression.op())) {
            Object group = values.get(expression.group());
            if (group == null) return null;
            BigDecimal sum = BigDecimal.ZERO;
            for (Object row : (List<?>) group) {
                tick(budget);
                Object value = ((Map<?, ?>) row).get(expression.field());
                if (value == null) return null;
                sum = bounded(sum.add(ApprovalFormSchemaV2Compiler.decimal(value)));
            }
            return sum;
        }
        BigDecimal left = expression(expression.args().getFirst(), values, budget);
        if (left == null) return null;
        if ("ROUND".equals(expression.op())) return bounded(left.setScale(expression.scale(), RoundingMode.HALF_UP));
        BigDecimal right = expression(expression.args().get(1), values, budget);
        if (right == null) return null;
        try {
            return bounded(switch (expression.op()) {
                case "ADD" -> left.add(right);
                case "SUBTRACT" -> left.subtract(right);
                case "MULTIPLY" -> left.multiply(right);
                case "DIVIDE" -> left.divide(right, 8, RoundingMode.HALF_UP);
                case "MIN" -> left.min(right);
                case "MAX" -> left.max(right);
                default -> throw ApprovalFormSchemaV2Compiler.invalid("Unknown compiled calculation.");
            });
        } catch (ArithmeticException exception) {
            throw ApprovalFormSchemaV2Compiler.invalid("Invalid calculation or division by zero.");
        }
    }

    private BigDecimal bounded(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.precision() + Math.max(0, -normalized.scale()) > 28 || Math.max(0, normalized.scale()) > 8) {
            throw ApprovalFormSchemaV2Compiler.invalid("Calculation exceeds decimal precision or scale.");
        }
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    private boolean empty(Object value) { return value == null || value instanceof String text && text.isBlank(); }
    private void tick(Budget budget) {
        if (++budget.operations > 200000) throw ApprovalFormSchemaV2Compiler.invalid("Form evaluation exceeds the operation limit.");
    }
    private void invalid(String path) { throw ApprovalFormSchemaV2Compiler.invalid("Invalid approval field: " + path); }
    private static final class Budget { private int operations; }
}
