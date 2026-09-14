package com.dwp.services.approval.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable compiled contract; construction is restricted to the validating compiler. */
public final class ApprovalFormSchemaV2 {

    public static final String CONTRACT = "DWP_APPROVAL_FORM_TYPED_V2";

    final Scope scope;
    private final Map<String, Object> definition;
    private final String canonicalJson;
    private final String sha256;

    ApprovalFormSchemaV2(Scope scope, Map<String, Object> definition, String canonicalJson, String sha256) {
        this.scope = scope;
        this.definition = definition;
        this.canonicalJson = canonicalJson;
        this.sha256 = sha256;
    }

    public Map<String, Object> definition() { return definition; }
    public String canonicalJson() { return canonicalJson; }
    public String sha256() { return sha256; }

    record Scope(Map<String, Field> fields, List<Field> order) {
        Scope { fields = Map.copyOf(fields); order = List.copyOf(order); }
    }

    record Field(String key, String type, boolean required, List<String> options,
            Condition visibleWhen, Condition requiredWhen, Expression calculation,
            BigDecimal min, BigDecimal max, int minLength, int maxLength,
            int minRows, int maxRows, Scope children) {
        Field { options = List.copyOf(options); }
        boolean numeric() { return Set.of("NUMBER", "CALCULATED_NUMBER").contains(type); }
        boolean group() { return "REPEATING_GROUP".equals(type); }
    }

    record Condition(String op, String field, List<Object> values, List<Condition> args) {
        Condition { values = List.copyOf(values); args = List.copyOf(args); }
    }

    record Expression(String op, String field, String group, BigDecimal value,
            int scale, List<Expression> args) {
        Expression { args = List.copyOf(args); }
    }

    public record Evaluation(Map<String, Object> payload, Set<String> visibleFields,
            Set<String> requiredFields, String schemaSha256) {
        public Evaluation {
            payload = ApprovalFormSchemaV2Canonical.freeze(payload);
            visibleFields = Set.copyOf(visibleFields);
            requiredFields = Set.copyOf(requiredFields);
        }
    }
}
