package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ApprovalWorkflowStageCondition {
    private ApprovalWorkflowStageCondition() { }

    static Map<String, Object> compile(Map<String, Object> condition) {
        if (condition == null) return null;
        if (!condition.keySet().equals(Set.of("all")) || !(condition.get("all") instanceof List<?> clauses)
                || clauses.isEmpty() || clauses.size() > 50) throw invalid("A stage condition requires 1 to 50 exact clauses.");
        for (var raw : clauses) {
            if (!(raw instanceof Map<?, ?> clause) || !clause.keySet().equals(Set.of("field", "operator", "value"))
                    || !(clause.get("field") instanceof String field) || !field.matches("[a-zA-Z][a-zA-Z0-9_]{0,79}")
                    || !(clause.get("operator") instanceof String op) || !Set.of("EQ", "IN", "GT", "GTE", "LT", "LTE").contains(op)) {
                throw invalid("Stage conditions cannot reference system fields, groups or unknown operators.");
            }
            Object value = clause.get("value");
            if ("IN".equals(op)) {
                if (!(value instanceof List<?> values) || values.isEmpty() || values.size() > 50) throw invalid("IN requires bounded operands.");
                values.forEach(ApprovalWorkflowStageCondition::scalar);
            } else scalar(value);
        }
        return ApprovalFormSchemaV2Canonical.freeze(condition);
    }

    private static void scalar(Object value) {
        if (value instanceof String text && text.length() <= 2000 && text.codePoints().noneMatch(Character::isISOControl)) return;
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof BigDecimal) return;
        throw invalid("A condition operand must be a bounded exact scalar.");
    }

    static boolean matches(String schemaJson, Map<String, Object> payload, Map<String, Object> condition) {
        if (condition == null) return true;
        try {
            var mapper = new ObjectMapper();
            var raw = mapper.readValue(schemaJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            if (!ApprovalFormSchemaV2.CONTRACT.equals(raw.get("schemaContract"))) {
                throw unavailable("Conditional quorum stages require an immutable typed form schema.");
            }
            var schema = new ApprovalFormSchemaV2Compiler().compile(raw);
            for (var item : (List<?>) condition.get("all")) {
                var clause = (Map<?, ?>) item;
                var field = schema.scope.fields().get(clause.get("field"));
                if (field == null || field.group() || field.visibleWhen() != null) {
                    throw invalid("A routing field must exist and remain visible in the pinned schema.");
                }
                if (!field.numeric() && !Set.of("EQ", "IN").contains(clause.get("operator"))) {
                    throw invalid("Ordered comparisons require a schema-declared numeric field.");
                }
            }
            return Boolean.TRUE.equals(ApprovalWorkflowTypedRouteCondition.match(schemaJson, mapper.writeValueAsString(condition), payload));
        } catch (java.io.IOException exception) { throw invalid("Pinned stage routing evidence is invalid."); }
    }
}
