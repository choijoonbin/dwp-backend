package com.dwp.services.approval.formsv3;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable compiled Form Schema V3 contract. Instances are created only by the strict compiler. */
public final class ApprovalFormSchemaV3 {
    public static final String CONTRACT = "DWP_APPROVAL_FORM_TYPED_V3";

    private final Map<String, Object> definition;
    private final String canonicalJson;
    private final String sha256;
    final Compatibility compatibility;
    final Map<String, Field> fields;
    final List<Calculation> calculations;
    final List<Rule> rules;
    final List<Scenario> scenarios;

    ApprovalFormSchemaV3(Map<String, Object> definition, String canonicalJson, String sha256,
            Compatibility compatibility, Map<String, Field> fields,
            List<Calculation> calculations, List<Rule> rules, List<Scenario> scenarios) {
        this.definition = definition;
        this.canonicalJson = canonicalJson;
        this.sha256 = sha256;
        this.compatibility = compatibility;
        this.fields = Map.copyOf(fields);
        this.calculations = List.copyOf(calculations);
        this.rules = List.copyOf(rules);
        this.scenarios = List.copyOf(scenarios);
    }

    public Map<String, Object> definition() { return definition; }
    public String canonicalJson() { return canonicalJson; }
    public String sha256() { return sha256; }
    public String compatibilityMode() { return compatibility.mode(); }
    public String baseSchemaSha256() { return compatibility.baseSchemaSha256(); }
    public Set<String> fieldKeys() { return fields.keySet(); }

    record Compatibility(String mode, String minimumRuntime, String baseSchemaSha256) { }
    record Localized(String ko, String en) { }
    record Span(int desktop, int tablet, int mobile) { }
    record Option(String value, Localized label) { }
    record DataSource(String providerKey, String resource, String credentialRef,
            String valuePath, String labelPath, int cacheTtlSeconds) { }
    record Retention(String classification, String retentionClass,
            Integer redactAfterDays, boolean legalHoldEligible) { }
    record Export(boolean included, Localized label, String format, String mask) { }
    record Field(String key, String type, String control, Localized label, Localized help,
            Span span, boolean required, Object defaultValue, Map<String, Object> validation,
            List<Option> options, DataSource dataSource, Set<String> viewRoles,
            Set<String> editRoles, Retention retention, Export export, List<Field> columns,
            boolean nested) {
        Field {
            validation = Map.copyOf(validation);
            options = List.copyOf(options);
            viewRoles = Set.copyOf(viewRoles);
            editRoles = Set.copyOf(editRoles);
            columns = List.copyOf(columns);
        }
        boolean calculated() { return type.startsWith("CALCULATED_"); }
        boolean displayOnly() { return Set.of("HEADING", "PARAGRAPH", "DIVIDER").contains(type); }
        boolean collection() { return Set.of("MULTI_SELECT", "ATTACHMENT", "PEOPLE", "TABLE", "REPEATING_GROUP").contains(type); }
    }
    record Predicate(String op, String field, Object value, List<Object> values,
            List<Predicate> args) {
        Predicate { values = List.copyOf(values); args = List.copyOf(args); }
    }
    record Expression(String op, String field, Object value, int scale,
            List<Expression> args) {
        Expression { args = List.copyOf(args); }
    }
    record Calculation(String key, String target, Expression expression, Set<String> dependencies) {
        Calculation { dependencies = Set.copyOf(dependencies); }
    }
    record Rule(String key, Predicate when, String target, String effect, Localized message) { }
    record Scenario(String key, Map<String, Object> input, Set<String> roles,
            Map<String, Object> expected) {
        Scenario {
            input = ApprovalFormSchemaV3Canonical.copy(input);
            roles = Set.copyOf(roles);
            expected = ApprovalFormSchemaV3Canonical.copy(expected);
        }
    }

    public record Violation(String code, String fieldKey, String messageKo, String messageEn) { }
    public record Evaluation(Map<String, Object> payload, Set<String> visibleFields,
            Set<String> requiredFields, Set<String> readOnlyFields,
            List<Violation> violations, String schemaSha256) {
        public Evaluation {
            payload = ApprovalFormSchemaV3Canonical.copy(payload);
            visibleFields = Set.copyOf(visibleFields);
            requiredFields = Set.copyOf(requiredFields);
            readOnlyFields = Set.copyOf(readOnlyFields);
            violations = List.copyOf(violations);
        }
    }
    public record ScenarioResult(String key, boolean passed, List<String> mismatches) {
        public ScenarioResult { mismatches = List.copyOf(mismatches); }
    }
    public record CompatibilityChange(String severity, String fieldKey, String code,
            String detail) { }
    public record CompatibilityReport(String baseSchemaSha256, String candidateSchemaSha256,
            boolean compatible, List<CompatibilityChange> changes) {
        public CompatibilityReport { changes = List.copyOf(changes); }
    }

    static BigDecimal decimal(Object value) {
        return ApprovalFormSchemaV3Canonical.decimal(value);
    }
}
