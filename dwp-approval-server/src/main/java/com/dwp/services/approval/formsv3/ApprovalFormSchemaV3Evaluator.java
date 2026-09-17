package com.dwp.services.approval.formsv3;

import static com.dwp.services.approval.formsv3.ApprovalFormSchemaV3.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure deterministic evaluator. It performs no network, clock, directory, or provider reads. */
public final class ApprovalFormSchemaV3Evaluator {

    public Evaluation evaluate(ApprovalFormSchemaV3 schema, Map<String, Object> rawPayload,
            Set<String> actorRoles) {
        if (schema == null || rawPayload == null || actorRoles == null) {
            throw ApprovalFormSchemaV3Canonical.invalid("Schema, payload, and actor roles are required.");
        }
        Map<String, Object> boundedPayload = ApprovalFormSchemaV3Canonical.freeze(rawPayload).value();
        Set<String> rootKeys = new LinkedHashSet<>();
        for (Field field : schema.fields.values()) if (!field.nested()) rootKeys.add(field.key());
        if (!rootKeys.containsAll(boundedPayload.keySet())) {
            throw ApprovalFormSchemaV3Canonical.invalid("Payload contains a field outside the immutable Form V3 contract.");
        }
        Map<String, Object> values = mutable(boundedPayload);
        for (Field field : schema.fields.values()) {
            if (!field.nested() && !values.containsKey(field.key()) && field.defaultValue() != null) {
                values.put(field.key(), mutableValue(field.defaultValue()));
            }
        }
        for (Calculation calculation : schema.calculations) {
            Object result = calculate(calculation.expression(), values);
            Field target = schema.fields.get(calculation.target());
            values.put(calculation.target(), "CALCULATED_NUMBER".equals(target.type())
                    ? number(result) : text(result));
        }

        Set<String> visible = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>();
        Set<String> readOnly = new LinkedHashSet<>();
        for (Field field : schema.fields.values()) {
            if (field.nested()) continue;
            if (matchesRole(field.viewRoles(), actorRoles)) visible.add(field.key());
            if (field.required()) required.add(field.key());
            if (field.calculated() || !matchesRole(field.editRoles(), actorRoles)) readOnly.add(field.key());
        }
        Set<String> hiddenByRule = new LinkedHashSet<>();
        List<Violation> violations = new ArrayList<>();
        for (Rule rule : schema.rules) {
            if (!predicate(rule.when(), values, actorRoles)) continue;
            switch (rule.effect()) {
                case "SHOW" -> { if (!hiddenByRule.contains(rule.target())) visible.add(rule.target()); }
                case "HIDE" -> { visible.remove(rule.target()); hiddenByRule.add(rule.target()); }
                case "REQUIRE" -> required.add(rule.target());
                case "READ_ONLY" -> readOnly.add(rule.target());
                case "ERROR" -> violations.add(new Violation("RULE_" + rule.key(), rule.target(),
                        rule.message().ko(), rule.message().en()));
                default -> throw new IllegalStateException("Compiled rule effect is unknown.");
            }
        }
        required.retainAll(visible);
        readOnly.retainAll(visible);
        for (String key : visible) {
            Field field = schema.fields.get(key);
            if (field.displayOnly()) continue;
            Object value = values.get(key);
            if (required.contains(key) && empty(value)) {
                violations.add(violation("REQUIRED", field, "필수 값입니다.", "This field is required."));
                continue;
            }
            if (!empty(value)) validate(field, value, violations);
        }
        Map<String, Object> projection = new LinkedHashMap<>();
        for (String key : visible) {
            if (values.containsKey(key)) projection.put(key, values.get(key));
        }
        return new Evaluation(projection, visible, required, readOnly, violations, schema.sha256());
    }

    public List<ScenarioResult> evaluateScenarios(ApprovalFormSchemaV3 schema) {
        List<ScenarioResult> results = new ArrayList<>();
        for (Scenario scenario : schema.scenarios) {
            Evaluation actual = evaluate(schema, scenario.input(), scenario.roles());
            Map<String, Object> expected = scenario.expected();
            List<String> mismatches = new ArrayList<>();
            if (expected.containsKey("computed")) {
                Map<String, Object> expectedValues = map(expected.get("computed"));
                for (Map.Entry<String, Object> entry : expectedValues.entrySet()) {
                    if (!same(entry.getValue(), actual.payload().get(entry.getKey()))) {
                        mismatches.add("computed:" + entry.getKey());
                    }
                }
            }
            compareSet(expected, "visibleFields", actual.visibleFields(), mismatches);
            compareSet(expected, "requiredFields", actual.requiredFields(), mismatches);
            compareSet(expected, "readOnlyFields", actual.readOnlyFields(), mismatches);
            if (expected.containsKey("violationCodes")) {
                Set<String> actualCodes = actual.violations().stream().map(Violation::code)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                compareSet(expected, "violationCodes", actualCodes, mismatches);
            }
            results.add(new ScenarioResult(scenario.key(), mismatches.isEmpty(), mismatches));
        }
        return results;
    }

    public void requirePassingScenarios(ApprovalFormSchemaV3 schema) {
        List<ScenarioResult> failed = evaluateScenarios(schema).stream().filter(result -> !result.passed()).toList();
        if (!failed.isEmpty()) {
            throw ApprovalFormSchemaV3Canonical.invalid("Form Schema V3 scenario contract failed: "
                    + failed.getFirst().key());
        }
    }

    private void validate(Field field, Object value, List<Violation> violations) {
        try {
            switch (field.type()) {
                case "TEXT", "TEXTAREA", "RICH_TEXT", "CALCULATED_TEXT" -> string(value, field, violations);
                case "EMAIL" -> {
                    string(value, field, violations);
                    if (value instanceof String text && !text.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}")) {
                        violations.add(violation("EMAIL", field, "이메일 형식이 아닙니다.", "Expected an email address."));
                    }
                }
                case "PHONE" -> {
                    string(value, field, violations);
                    if (value instanceof String text && !text.matches("\\+?[0-9][0-9 .()-]{6,30}")) {
                        violations.add(violation("PHONE", field, "전화번호 형식이 아닙니다.", "Expected a phone number."));
                    }
                }
                case "URL" -> url(value, field, violations);
                case "NUMBER", "CURRENCY", "PERCENT", "CALCULATED_NUMBER" -> numeric(value, field, violations);
                case "DATE" -> LocalDate.parse(requireString(value));
                case "DATETIME" -> OffsetDateTime.parse(requireString(value));
                case "TIME" -> LocalTime.parse(requireString(value));
                case "BOOLEAN" -> { if (!(value instanceof Boolean)) type(field); }
                case "SINGLE_SELECT" -> selection(field, value, false, violations);
                case "MULTI_SELECT" -> selection(field, value, true, violations);
                case "USER", "ORGANIZATION", "GROUP", "LOOKUP", "SIGNATURE" -> reference(value, field);
                case "PEOPLE" -> collection(field, value, violations, item -> reference(item, field));
                case "ATTACHMENT" -> attachments(field, value, violations);
                case "ADDRESS" -> { if (!(value instanceof Map<?, ?>)) type(field); }
                case "TABLE", "REPEATING_GROUP" -> rows(field, value, violations);
                case "HEADING", "PARAGRAPH", "DIVIDER" -> { }
                default -> throw new IllegalStateException("Compiled field type is unknown.");
            }
        } catch (DateTimeParseException exception) {
            violations.add(violation("TEMPORAL", field, "날짜 또는 시간 형식이 올바르지 않습니다.",
                    "Expected an ISO date or time value."));
        } catch (TypeMismatch mismatch) {
            violations.add(violation("TYPE", field, "값의 형식이 올바르지 않습니다.", "Field value has the wrong type."));
        }
    }

    private void string(Object value, Field field, List<Violation> violations) {
        String text = requireString(value);
        int length = text.codePointCount(0, text.length());
        Integer min = integer(field.validation().get("minLength"));
        Integer max = integer(field.validation().get("maxLength"));
        if (min != null && length < min) violations.add(violation("MIN_LENGTH", field, "입력 값이 너무 짧습니다.", "Value is too short."));
        if (max != null && length > max) violations.add(violation("MAX_LENGTH", field, "입력 값이 너무 깁니다.", "Value is too long."));
        if (field.validation().get("pattern") instanceof String pattern && text.length() <= 20_000
                && !Pattern.compile(pattern).matcher(text).matches()) {
            violations.add(violation("PATTERN", field, "입력 형식이 조건과 일치하지 않습니다.", "Value does not match the required pattern."));
        }
    }

    private void numeric(Object value, Field field, List<Violation> violations) {
        BigDecimal number;
        try { number = ApprovalFormSchemaV3.decimal(value); } catch (RuntimeException exception) { type(field); return; }
        if (field.validation().containsKey("min") && number.compareTo(ApprovalFormSchemaV3.decimal(field.validation().get("min"))) < 0) {
            violations.add(violation("MIN", field, "최솟값보다 작습니다.", "Value is below the minimum."));
        }
        if (field.validation().containsKey("max") && number.compareTo(ApprovalFormSchemaV3.decimal(field.validation().get("max"))) > 0) {
            violations.add(violation("MAX", field, "최댓값보다 큽니다.", "Value exceeds the maximum."));
        }
    }

    private void url(Object value, Field field, List<Violation> violations) {
        string(value, field, violations);
        try {
            URI uri = URI.create((String) value);
            if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                violations.add(violation("URL", field, "유효한 웹 주소가 아닙니다.", "Expected a valid web URL."));
            }
        } catch (IllegalArgumentException exception) {
            violations.add(violation("URL", field, "유효한 웹 주소가 아닙니다.", "Expected a valid web URL."));
        }
    }

    private void selection(Field field, Object value, boolean multiple, List<Violation> violations) {
        Collection<?> values = multiple ? collectionValue(value) : List.of(value);
        bounds(field, values.size(), violations);
        if (!field.options().isEmpty()) {
            Set<String> allowed = field.options().stream().map(Option::value).collect(java.util.stream.Collectors.toSet());
            if (values.stream().anyMatch(item -> !(item instanceof String text) || !allowed.contains(text))) {
                violations.add(violation("OPTION", field, "허용되지 않은 선택 값입니다.", "Selection is not allowed."));
            }
        } else if (values.stream().anyMatch(item -> !(item instanceof String))) type(field);
    }

    private void reference(Object value, Field field) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 240) type(field);
    }

    private void attachments(Field field, Object value, List<Violation> violations) {
        Collection<?> files = collectionValue(value); bounds(field, files.size(), violations);
        Set<String> allowed = field.validation().containsKey("allowedMimeTypes")
                ? strings(field.validation().get("allowedMimeTypes")) : Set.of();
        long maxBytes = field.validation().containsKey("maxFileBytes")
                ? ApprovalFormSchemaV3.decimal(field.validation().get("maxFileBytes")).longValueExact() : Long.MAX_VALUE;
        for (Object file : files) {
            if (!(file instanceof Map<?, ?> map) || !(map.get("id") instanceof String)
                    || !(map.get("name") instanceof String) || !(map.get("mimeType") instanceof String mime)
                    || !(map.get("size") instanceof Number || map.get("size") instanceof String)) {
                type(field); return;
            }
            if (!allowed.isEmpty() && !allowed.contains(mime)) violations.add(violation("MIME", field, "허용되지 않은 파일 형식입니다.", "File type is not allowed."));
            if (ApprovalFormSchemaV3.decimal(map.get("size")).compareTo(BigDecimal.valueOf(maxBytes)) > 0) {
                violations.add(violation("FILE_SIZE", field, "파일 크기 제한을 초과했습니다.", "File exceeds the size limit."));
            }
        }
    }

    private void rows(Field field, Object value, List<Violation> violations) {
        Collection<?> rows = collectionValue(value); bounds(field, rows.size(), violations);
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> raw)) { type(field); return; }
            @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) raw;
            Set<String> allowed = field.columns().stream().map(Field::key).collect(java.util.stream.Collectors.toSet());
            if (!allowed.containsAll(row.keySet())) { type(field); return; }
            for (Field column : field.columns()) {
                Object columnValue = row.get(column.key());
                if (column.required() && empty(columnValue)) {
                    violations.add(violation("REQUIRED", column, "필수 값입니다.", "This field is required."));
                } else if (!empty(columnValue)) validate(column, columnValue, violations);
            }
        }
    }

    private void collection(Field field, Object value, List<Violation> violations, ItemValidator validator) {
        Collection<?> items = collectionValue(value); bounds(field, items.size(), violations);
        for (Object item : items) validator.validate(item);
    }

    private void bounds(Field field, int size, List<Violation> violations) {
        Integer min = integer(field.validation().get("minItems"));
        Integer max = integer(field.validation().get("maxItems"));
        if (field.validation().containsKey("maxFiles")) max = integer(field.validation().get("maxFiles"));
        if (min != null && size < min) violations.add(violation("MIN_ITEMS", field, "항목 수가 부족합니다.", "Too few items."));
        if (max != null && size > max) violations.add(violation("MAX_ITEMS", field, "항목 수가 제한을 초과했습니다.", "Too many items."));
    }

    private boolean predicate(Predicate predicate, Map<String, Object> values, Set<String> roles) {
        return switch (predicate.op()) {
            case "AND" -> predicate.args().stream().allMatch(item -> predicate(item, values, roles));
            case "OR" -> predicate.args().stream().anyMatch(item -> predicate(item, values, roles));
            case "NOT" -> !predicate(predicate.args().getFirst(), values, roles);
            case "ROLE_ANY" -> predicate.values().stream().map(String.class::cast).anyMatch(roles::contains);
            case "PRESENT" -> !empty(values.get(predicate.field()));
            case "EMPTY" -> empty(values.get(predicate.field()));
            case "EQ" -> same(values.get(predicate.field()), predicate.value());
            case "NE" -> !same(values.get(predicate.field()), predicate.value());
            case "IN" -> predicate.values().stream().anyMatch(value -> same(values.get(predicate.field()), value));
            case "GT", "GTE", "LT", "LTE" -> compare(values.get(predicate.field()), predicate.value(), predicate.op());
            case "CONTAINS" -> contains(values.get(predicate.field()), predicate.value());
            default -> throw new IllegalStateException("Compiled predicate operator is unknown.");
        };
    }

    private Object calculate(Expression expression, Map<String, Object> values) {
        return switch (expression.op()) {
            case "CONST" -> expression.value();
            case "FIELD" -> values.get(expression.field());
            case "ADD" -> number(calculate(expression.args().get(0), values)).add(number(calculate(expression.args().get(1), values)));
            case "SUBTRACT" -> number(calculate(expression.args().get(0), values)).subtract(number(calculate(expression.args().get(1), values)));
            case "MULTIPLY" -> number(calculate(expression.args().get(0), values)).multiply(number(calculate(expression.args().get(1), values)));
            case "DIVIDE" -> number(calculate(expression.args().get(0), values)).divide(
                    number(calculate(expression.args().get(1), values)), 8, RoundingMode.HALF_UP).stripTrailingZeros();
            case "MIN" -> number(calculate(expression.args().get(0), values)).min(number(calculate(expression.args().get(1), values)));
            case "MAX" -> number(calculate(expression.args().get(0), values)).max(number(calculate(expression.args().get(1), values)));
            case "ROUND" -> number(calculate(expression.args().getFirst(), values)).setScale(expression.scale(), RoundingMode.HALF_UP);
            case "CONCAT" -> expression.args().stream().map(item -> text(calculate(item, values))).collect(java.util.stream.Collectors.joining());
            case "IF" -> truthy(calculate(expression.args().get(0), values))
                    ? calculate(expression.args().get(1), values) : calculate(expression.args().get(2), values);
            default -> throw new IllegalStateException("Compiled calculation operator is unknown.");
        };
    }

    private boolean compare(Object left, Object right, String operator) {
        if (empty(left) || empty(right)) return false;
        int compared = number(left).compareTo(number(right));
        return switch (operator) { case "GT" -> compared > 0; case "GTE" -> compared >= 0;
            case "LT" -> compared < 0; case "LTE" -> compared <= 0; default -> false; };
    }

    private boolean contains(Object container, Object expected) {
        if (container instanceof Collection<?> values) return values.stream().anyMatch(value -> same(value, expected));
        return container instanceof String text && expected instanceof String needle && text.contains(needle);
    }

    private boolean matchesRole(Set<String> required, Set<String> actual) {
        return required.contains("*") || required.stream().anyMatch(actual::contains);
    }

    private boolean same(Object left, Object right) {
        if (left instanceof Number || right instanceof Number) {
            try { return ApprovalFormSchemaV3.decimal(left).compareTo(ApprovalFormSchemaV3.decimal(right)) == 0; }
            catch (RuntimeException ignored) { return false; }
        }
        return Objects.equals(left, right);
    }

    private boolean truthy(Object value) {
        return value instanceof Boolean bool ? bool : value instanceof Number number
                ? new BigDecimal(number.toString()).signum() != 0 : value instanceof String text && !text.isBlank();
    }

    private boolean empty(Object value) {
        return value == null || value instanceof String text && text.isBlank()
                || value instanceof Collection<?> collection && collection.isEmpty()
                || value instanceof Map<?, ?> map && map.isEmpty();
    }

    private BigDecimal number(Object value) { return ApprovalFormSchemaV3.decimal(value); }
    private String text(Object value) { return value == null ? "" : value.toString(); }
    private String requireString(Object value) { if (!(value instanceof String text)) throw new TypeMismatch(); return text; }
    private void type(Field field) { throw new TypeMismatch(); }
    private Collection<?> collectionValue(Object value) { if (!(value instanceof Collection<?> values)) throw new TypeMismatch(); return values; }
    private Integer integer(Object value) { return value == null ? null : ApprovalFormSchemaV3.decimal(value).intValueExact(); }

    private Violation violation(String code, Field field, String ko, String en) {
        return new Violation(code, field.key(), ko, en);
    }

    private void compareSet(Map<String, Object> expected, String key, Set<String> actual, List<String> mismatches) {
        if (expected.containsKey(key) && !strings(expected.get(key)).equals(actual)) mismatches.add(key);
    }

    private Set<String> strings(Object value) {
        if (!(value instanceof Collection<?> items)) throw ApprovalFormSchemaV3Canonical.invalid("Scenario expected value must be an array.");
        Set<String> result = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof String text)) throw ApprovalFormSchemaV3Canonical.invalid("Scenario array must contain strings.");
            result.add(text);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) throw ApprovalFormSchemaV3Canonical.invalid("Scenario expected value must be an object.");
        return (Map<String, Object>) value;
    }

    private Map<String, Object> mutable(Map<String, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, mutableValue(value)));
        return result;
    }

    private Object mutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put((String) key, mutableValue(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::mutableValue).toList();
        return value;
    }

    @FunctionalInterface private interface ItemValidator { void validate(Object item); }
    private static final class TypeMismatch extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
