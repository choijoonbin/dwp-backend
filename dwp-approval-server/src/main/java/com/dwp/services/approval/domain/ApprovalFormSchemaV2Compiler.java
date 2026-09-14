package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2.Condition;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2.Expression;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2.Field;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2.Scope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict V2 parser with scope-local typed references and bounded dependency graphs. */
public final class ApprovalFormSchemaV2Compiler {

    private static final Set<String> TYPES = Set.of("TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT",
            "USER", "CALCULATED_NUMBER", "REPEATING_GROUP");
    private static final Set<String> FIELD_KEYS = Set.of("key", "labelKo", "labelEn", "helpKo", "helpEn",
            "type", "required", "options", "visibleWhen", "requiredWhen", "calculation", "min", "max",
            "minLength", "maxLength", "minRows", "maxRows", "fields");

    public ApprovalFormSchemaV2 compile(Map<String, Object> rawDefinition) {
        if (rawDefinition == null) throw invalid("Form schema is missing.");
        Map<String, Object> definition = ApprovalFormSchemaV2Canonical.freeze(rawDefinition);
        only(definition, Set.of("schemaContract", "schemaVersion", "fields"));
        if (!ApprovalFormSchemaV2.CONTRACT.equals(definition.get("schemaContract"))) {
            throw invalid("Expected the typed form V2 contract discriminator.");
        }
        if (integer(definition.get("schemaVersion"), 2, 2) != 2) throw invalid("Expected schema V2.");
        Budget budget = new Budget();
        Scope scope = scope(definition.get("fields"), false, budget);
        String json = ApprovalFormSchemaV2Canonical.json(definition);
        if (json.length() > 200000) throw invalid("Form schema exceeds the size limit.");
        return new ApprovalFormSchemaV2(scope, definition, json, ApprovalFormSchemaV2Canonical.sha256(json));
    }

    private Scope scope(Object rawFields, boolean row, Budget budget) {
        List<?> values = list(rawFields, 1, row ? 20 : 50);
        Map<String, Field> fields = new LinkedHashMap<>();
        for (Object value : values) {
            Field field = field(map(value), row, budget);
            if (fields.putIfAbsent(field.key(), field) != null) throw invalid("Duplicate field: " + field.key());
        }
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (Field field : fields.values()) {
            Set<String> refs = new HashSet<>();
            validateCondition(field.visibleWhen(), fields, refs);
            validateCondition(field.requiredWhen(), fields, refs);
            validateExpression(field.calculation(), fields, refs, row);
            dependencies.put(field.key(), refs);
        }
        List<Field> order = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String key : fields.keySet()) visit(key, fields, dependencies, visiting, visited, order);
        return new Scope(fields, order);
    }

    private Field field(Map<String, Object> value, boolean row, Budget budget) {
        only(value, FIELD_KEYS);
        if (++budget.fields > 250) throw invalid("Too many fields in form schema.");
        String key = text(value.get("key"), 80);
        if (!key.matches("[a-z][A-Za-z0-9_]{1,79}") || "createdFrom".equals(key)) {
            throw invalid("Invalid or reserved field key.");
        }
        text(value.get("labelKo"), 160);
        text(value.get("labelEn"), 160);
        optionalText(value.get("helpKo"), 500);
        optionalText(value.get("helpEn"), 500);
        String type = text(value.get("type"), 32);
        if (!TYPES.contains(type)) throw invalid("Unsupported field type: " + type);
        boolean required = bool(value.get("required"), false);
        List<String> options = options(value.get("options"));
        if ("SELECT".equals(type) ? options.size() < 2 : !options.isEmpty()) {
            throw invalid("Only SELECT fields accept two or more unique options.");
        }
        Condition visible = condition(value.get("visibleWhen"), 0, new Budget(), budget);
        Condition requiredWhen = condition(value.get("requiredWhen"), 0, new Budget(), budget);
        Expression calculation = expression(value.get("calculation"), 0, new Budget(), budget);
        if ("CALCULATED_NUMBER".equals(type) != (calculation != null)) {
            throw invalid("CALCULATED_NUMBER requires a typed calculation, and only that type accepts one.");
        }
        boolean numeric = Set.of("NUMBER", "CALCULATED_NUMBER").contains(type);
        boolean textual = Set.of("TEXT", "TEXTAREA", "USER").contains(type);
        if (!numeric && (value.containsKey("min") || value.containsKey("max"))) throw invalid("Numeric bounds require a number field.");
        if (!textual && (value.containsKey("minLength") || value.containsKey("maxLength"))) throw invalid("Length bounds require a text field.");
        BigDecimal min = optionalDecimal(value.get("min"));
        BigDecimal max = optionalDecimal(value.get("max"));
        if (min != null && max != null && min.compareTo(max) > 0) throw invalid("Invalid numeric bounds.");
        int minLength = optionalInteger(value.get("minLength"), 0, 10000, 0);
        int maxLength = optionalInteger(value.get("maxLength"), 1, 10000, 10000);
        if (minLength > maxLength) throw invalid("Invalid length bounds.");
        boolean group = "REPEATING_GROUP".equals(type);
        if (row && group) throw invalid("Nested repeating groups are not supported.");
        if (!group && (value.containsKey("fields") || value.containsKey("minRows") || value.containsKey("maxRows"))) {
            throw invalid("Row settings require a repeating group.");
        }
        int minRows = optionalInteger(value.get("minRows"), 0, 50, 0);
        int maxRows = optionalInteger(value.get("maxRows"), 1, 50, 20);
        if (minRows > maxRows) throw invalid("Invalid row bounds.");
        Scope children = group ? scope(value.get("fields"), true, budget) : null;
        return new Field(key, type, required, options, visible, requiredWhen, calculation, min, max,
                minLength, maxLength, minRows, maxRows, children);
    }

    private Condition condition(Object raw, int depth, Budget tree, Budget total) {
        if (raw == null) return null;
        node(depth, tree, total);
        Map<String, Object> value = map(raw);
        String op = text(value.get("op"), 16);
        if (Set.of("AND", "OR", "NOT").contains(op)) {
            only(value, Set.of("op", "args"));
            List<?> args = list(value.get("args"), "NOT".equals(op) ? 1 : 2, "NOT".equals(op) ? 1 : 20);
            List<Condition> parsed = new ArrayList<>();
            for (Object arg : args) {
                Condition child = condition(arg, depth + 1, tree, total);
                if (child == null) throw invalid("Condition operand is missing.");
                parsed.add(child);
            }
            return new Condition(op, null, List.of(), parsed);
        }
        if (!Set.of("PRESENT", "EQ", "NE", "IN", "GT", "GTE", "LT", "LTE").contains(op)) throw invalid("Unknown condition operator.");
        only(value, "PRESENT".equals(op) ? Set.of("op", "field")
                : "IN".equals(op) ? Set.of("op", "field", "values") : Set.of("op", "field", "value"));
        String field = text(value.get("field"), 80);
        List<?> literals = "PRESENT".equals(op) ? List.of() : "IN".equals(op)
                ? list(value.get("values"), 1, 50) : List.of(requiredLiteral(value.get("value")));
        List<Object> parsed = new ArrayList<>();
        for (Object literal : literals) parsed.add(requiredLiteral(literal));
        return new Condition(op, field, parsed, List.of());
    }

    private Expression expression(Object raw, int depth, Budget tree, Budget total) {
        if (raw == null) return null;
        node(depth, tree, total);
        Map<String, Object> value = map(raw);
        String op = text(value.get("op"), 16);
        switch (op) {
            case "CONST" -> {
                only(value, Set.of("op", "value"));
                return new Expression(op, null, null, ApprovalFormSchemaV2Canonical.transportDecimal(value.get("value")), 0, List.of());
            }
            case "FIELD", "SUM" -> {
                only(value, "FIELD".equals(op) ? Set.of("op", "field") : Set.of("op", "group", "field"));
                return new Expression(op, text(value.get("field"), 80),
                        "SUM".equals(op) ? text(value.get("group"), 80) : null, null, 0, List.of());
            }
            case "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "MIN", "MAX", "ROUND" -> {
                only(value, "ROUND".equals(op) ? Set.of("op", "args", "scale") : Set.of("op", "args"));
                int count = "ROUND".equals(op) ? 1 : 2;
                List<?> args = list(value.get("args"), count, count);
                List<Expression> parsed = new ArrayList<>();
                for (Object arg : args) {
                    Expression child = expression(arg, depth + 1, tree, total);
                    if (child == null) throw invalid("Calculation operand is missing.");
                    parsed.add(child);
                }
                return new Expression(op, null, null, null,
                        "ROUND".equals(op) ? integer(value.get("scale"), 0, 8) : 0, parsed);
            }
            default -> throw invalid("Unknown calculation operator.");
        }
    }

    private void validateCondition(Condition condition, Map<String, Field> fields, Set<String> refs) {
        if (condition == null) return;
        if (condition.field() == null) {
            for (Condition child : condition.args()) validateCondition(child, fields, refs);
            return;
        }
        Field field = reference(condition.field(), fields);
        if (field.group()) throw invalid("Conditions must reference a scalar field.");
        refs.add(field.key());
        boolean numericOp = Set.of("GT", "GTE", "LT", "LTE").contains(condition.op());
        if (numericOp && !field.numeric()) throw invalid("Numeric conditions require a numeric field.");
        for (Object literal : condition.values()) {
            if (field.numeric()) ApprovalFormSchemaV2Canonical.transportDecimal(literal);
            else {
                if (!(literal instanceof String string) || string.length() > 10000) throw invalid("Condition literal type mismatch.");
                if ("SELECT".equals(field.type()) && !field.options().contains(literal)) throw invalid("Unknown SELECT condition option.");
                if ("DATE".equals(field.type())) date((String) literal);
            }
        }
    }

    private void validateExpression(Expression expression, Map<String, Field> fields, Set<String> refs, boolean row) {
        if (expression == null) return;
        if ("FIELD".equals(expression.op())) {
            Field field = reference(expression.field(), fields);
            if (!field.numeric()) throw invalid("Calculation reference must be numeric.");
            refs.add(field.key());
        } else if ("SUM".equals(expression.op())) {
            if (row) throw invalid("Row calculations cannot aggregate another group.");
            Field group = reference(expression.group(), fields);
            if (!group.group()) throw invalid("SUM requires a repeating group.");
            Field child = reference(expression.field(), group.children().fields());
            if (!child.numeric()) throw invalid("SUM requires a numeric row field.");
            refs.add(group.key());
        }
        for (Expression child : expression.args()) validateExpression(child, fields, refs, row);
    }

    private Field reference(String key, Map<String, Field> fields) {
        Field field = fields.get(key);
        if (field == null) throw invalid("Unknown or cross-scope field reference: " + key);
        return field;
    }

    private void visit(String key, Map<String, Field> fields, Map<String, Set<String>> dependencies,
            Set<String> visiting, Set<String> visited, List<Field> order) {
        if (visited.contains(key)) return;
        if (!visiting.add(key)) throw invalid("Cyclic form-field dependency: " + key);
        for (String dependency : dependencies.get(key).stream().sorted().toList()) {
            visit(dependency, fields, dependencies, visiting, visited, order);
        }
        visiting.remove(key);
        visited.add(key);
        order.add(fields.get(key));
    }

    private List<String> options(Object raw) {
        if (raw == null) return List.of();
        List<?> values = list(raw, 0, 50);
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            String option = text(value, 160);
            if (result.contains(option)) throw invalid("Duplicate SELECT option.");
            result.add(option);
        }
        return result;
    }

    static BigDecimal decimal(Object value) {
        return ApprovalFormSchemaV2Canonical.decimal(value);
    }

    static void date(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid("Expected an ISO calendar date.");
        try { LocalDate.parse(value); } catch (DateTimeParseException exception) { throw invalid("Invalid calendar date."); }
    }

    private BigDecimal optionalDecimal(Object value) {
        return value == null ? null : ApprovalFormSchemaV2Canonical.transportDecimal(value);
    }
    private int optionalInteger(Object value, int min, int max, int fallback) {
        return value == null ? fallback : integer(value, min, max);
    }
    private int integer(Object value, int min, int max) {
        try {
            int result = decimal(value).intValueExact();
            if (result < min || result > max) throw invalid("Integer is out of bounds.");
            return result;
        } catch (ArithmeticException exception) { throw invalid("Expected an integer."); }
    }
    private Object requiredLiteral(Object value) {
        if (!(value instanceof String) && !(value instanceof Number)) throw invalid("Expected a scalar condition literal.");
        return value;
    }
    private String text(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.trim()) || text.length() > max) {
            throw invalid("Invalid schema text.");
        }
        return (String) value;
    }
    private void optionalText(Object value, int max) {
        if (value != null && (!(value instanceof String text) || text.length() > max)) throw invalid("Invalid help text.");
    }
    private boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean)) throw invalid("Expected a boolean.");
        return (Boolean) value;
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) throw invalid("Expected a schema object.");
        return (Map<String, Object>) value;
    }
    private List<?> list(Object value, int min, int max) {
        if (!(value instanceof List<?> list) || list.size() < min || list.size() > max) throw invalid("Invalid schema list size.");
        return (List<?>) value;
    }
    private void only(Map<String, Object> value, Set<String> allowed) {
        if (!allowed.containsAll(value.keySet())) throw invalid("Unknown schema property.");
    }
    private void node(int depth, Budget tree, Budget total) {
        if (depth > 8 || ++tree.nodes > 128 || ++total.nodes > 2048) throw invalid("Expression complexity exceeds the limit.");
    }
    static BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
    private static final class Budget { private int nodes; private int fields; }
}
