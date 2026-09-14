package com.dwp.services.approval.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Decimal transport strings are numeric only when the immutable typed schema declares them numeric. */
final class ApprovalWorkflowTypedRouteCondition {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    private ApprovalWorkflowTypedRouteCondition() { }

    static Boolean match(String storedSchema, String storedCondition, Map<String, Object> payload) {
        if (storedSchema == null) return null;
        try {
            Map<String, Object> rawSchema = JSON.readValue(storedSchema, OBJECT);
            if (!rawSchema.containsKey("schemaContract")) return null;
            if (!ApprovalFormSchemaV2.CONTRACT.equals(rawSchema.get("schemaContract"))) {
                throw ApprovalFormSchemaV2Compiler.invalid("Unknown marked form schema cannot select an approval route.");
            }
            ApprovalFormSchemaV2 schema = new ApprovalFormSchemaV2Compiler().compile(rawSchema);
            Map<String, Object> condition = JSON.readValue(storedCondition, OBJECT);
            if (!(condition.get("all") instanceof List<?> clauses) || clauses.isEmpty() || clauses.size() > 50) return false;
            for (Object raw : clauses) {
                if (!(raw instanceof Map<?, ?> clause)) return false;
                String field = clause.get("field") == null ? "" : clause.get("field").toString().trim();
                var definition = schema.scope.fields().get(field);
                if (definition == null || definition.group() || !payload.containsKey(field)) return false;
                String op = clause.get("operator") == null ? "EQ"
                        : clause.get("operator").toString().trim().toUpperCase(Locale.ROOT);
                Object actual = payload.get(field);
                Object expected = clause.get("value");
                if (actual == null || expected == null) return false;
                if ("IN".equals(op) && (!(expected instanceof List<?> values) || values.isEmpty() || values.size() > 50)) return false;
                boolean matched;
                if (definition.numeric()) {
                    BigDecimal number = ApprovalFormSchemaV2Canonical.transportDecimal(actual);
                    matched = "IN".equals(op) ? expected instanceof List<?> values
                            && values.stream().anyMatch(value -> compare(number, value, "EQ"))
                            : compare(number, expected, op);
                } else {
                    matched = switch (op) {
                        case "EQ" -> java.util.Objects.equals(actual, expected);
                        case "IN" -> expected instanceof List<?> values && values.contains(actual);
                        default -> false;
                    };
                }
                if (!matched) return false;
            }
            return true;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw ApprovalFormSchemaV2Compiler.invalid("Stored typed route schema or condition is invalid.");
        }
    }

    private static boolean compare(BigDecimal actual, Object expected, String op) {
        if (expected == null) return false;
        int comparison = actual.compareTo(ApprovalFormSchemaV2Canonical.transportDecimal(expected));
        return switch (op) {
            case "EQ" -> comparison == 0;
            case "GTE" -> comparison >= 0;
            case "GT" -> comparison > 0;
            case "LTE" -> comparison <= 0;
            case "LT" -> comparison < 0;
            default -> false;
        };
    }
}
