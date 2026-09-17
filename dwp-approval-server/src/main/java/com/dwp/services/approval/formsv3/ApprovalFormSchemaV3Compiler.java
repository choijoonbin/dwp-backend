package com.dwp.services.approval.formsv3;

import static com.dwp.services.approval.formsv3.ApprovalFormSchemaV3.*;

import com.dwp.core.exception.BaseException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict parser, semantic validator, and compatibility analyzer for Form Schema V3. */
public final class ApprovalFormSchemaV3Compiler {
    private static final Set<String> TOP_KEYS = Set.of("schemaContract", "schemaVersion", "locales",
            "compatibility", "pages", "rules", "calculations", "scenarios", "retention", "export");
    private static final Set<String> FIELD_KEYS = Set.of("key", "type", "control", "label", "help",
            "span", "required", "defaultValue", "validation", "options", "dataSource",
            "viewRoles", "editRoles", "retention", "export", "columns");
    private static final Set<String> TYPES = Set.of("TEXT", "TEXTAREA", "RICH_TEXT", "EMAIL", "PHONE",
            "URL", "NUMBER", "CURRENCY", "PERCENT", "DATE", "DATETIME", "TIME", "BOOLEAN",
            "SINGLE_SELECT", "MULTI_SELECT", "USER", "PEOPLE", "ORGANIZATION", "GROUP", "LOOKUP",
            "ATTACHMENT", "SIGNATURE", "ADDRESS", "TABLE", "REPEATING_GROUP", "HEADING",
            "PARAGRAPH", "DIVIDER", "CALCULATED_NUMBER", "CALCULATED_TEXT");
    private static final Map<String, Set<String>> CONTROLS = Map.ofEntries(
            Map.entry("TEXT", Set.of("TEXT_INPUT")), Map.entry("TEXTAREA", Set.of("TEXTAREA")),
            Map.entry("RICH_TEXT", Set.of("RICH_TEXT_EDITOR")), Map.entry("EMAIL", Set.of("EMAIL_INPUT")),
            Map.entry("PHONE", Set.of("PHONE_INPUT")), Map.entry("URL", Set.of("URL_INPUT")),
            Map.entry("NUMBER", Set.of("NUMBER_INPUT")), Map.entry("CURRENCY", Set.of("CURRENCY_INPUT")),
            Map.entry("PERCENT", Set.of("PERCENT_INPUT")), Map.entry("DATE", Set.of("DATE_PICKER")),
            Map.entry("DATETIME", Set.of("DATETIME_PICKER")), Map.entry("TIME", Set.of("TIME_PICKER")),
            Map.entry("BOOLEAN", Set.of("CHECKBOX", "SWITCH")), Map.entry("SINGLE_SELECT", Set.of("SELECT", "RADIO")),
            Map.entry("MULTI_SELECT", Set.of("MULTI_SELECT", "CHECKBOX_GROUP")), Map.entry("USER", Set.of("USER_PICKER")),
            Map.entry("PEOPLE", Set.of("PEOPLE_PICKER")), Map.entry("ORGANIZATION", Set.of("ORGANIZATION_PICKER")),
            Map.entry("GROUP", Set.of("GROUP_PICKER")), Map.entry("LOOKUP", Set.of("LOOKUP")),
            Map.entry("ATTACHMENT", Set.of("FILE_UPLOAD")), Map.entry("SIGNATURE", Set.of("SIGNATURE_REQUEST")),
            Map.entry("ADDRESS", Set.of("ADDRESS_INPUT")), Map.entry("TABLE", Set.of("TABLE_EDITOR")),
            Map.entry("REPEATING_GROUP", Set.of("REPEATING_GROUP")), Map.entry("HEADING", Set.of("HEADING")),
            Map.entry("PARAGRAPH", Set.of("PARAGRAPH")), Map.entry("DIVIDER", Set.of("DIVIDER")),
            Map.entry("CALCULATED_NUMBER", Set.of("CALCULATED_NUMBER")),
            Map.entry("CALCULATED_TEXT", Set.of("CALCULATED_TEXT")));
    private static final Set<String> DATA_SOURCE_TYPES = Set.of("SINGLE_SELECT", "MULTI_SELECT", "USER",
            "PEOPLE", "ORGANIZATION", "GROUP", "LOOKUP");
    private static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9_]{1,79}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.:-]{1,79}");
    private static final Pattern VAULT = Pattern.compile("vault://[A-Za-z0-9][A-Za-z0-9._/-]{2,239}");
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");

    public ApprovalFormSchemaV3 compile(Map<String, Object> raw) {
        ApprovalFormSchemaV3Canonical.Frozen frozen = ApprovalFormSchemaV3Canonical.freeze(raw);
        Map<String, Object> definition = frozen.value();
        only(definition, TOP_KEYS);
        if (!CONTRACT.equals(definition.get("schemaContract")) || integer(definition.get("schemaVersion"), 3, 3) != 3) {
            throw invalid("Expected the immutable Form Schema V3 discriminator.");
        }
        rejectSecrets(definition, null);
        Set<String> locales = strings(definition.get("locales"), 2, 2, 8, false);
        if (!locales.equals(Set.of("ko", "en"))) throw invalid("Form Schema V3 requires exactly KO and EN locales.");
        Compatibility compatibility = compatibility(map(definition.get("compatibility")));
        Retention defaultRetention = retention(map(definition.get("retention")));
        exportRoot(map(definition.get("export")));

        Budget budget = new Budget();
        Map<String, Field> fields = new LinkedHashMap<>();
        List<?> pages = list(definition.get("pages"), 1, 20);
        Set<String> pageKeys = new HashSet<>();
        for (Object rawPage : pages) {
            Map<String, Object> page = map(rawPage);
            only(page, Set.of("key", "title", "sections"));
            unique(pageKeys, key(page.get("key")), "page");
            localized(page.get("title"), 200, false);
            List<?> sections = list(page.get("sections"), 1, 30);
            Set<String> sectionKeys = new HashSet<>();
            for (Object rawSection : sections) {
                Map<String, Object> section = map(rawSection);
                only(section, Set.of("key", "title", "description", "layout", "fields"));
                unique(sectionKeys, key(section.get("key")), "section");
                localized(section.get("title"), 200, false);
                if (section.containsKey("description")) localized(section.get("description"), 1000, true);
                layout(map(section.get("layout")));
                for (Object rawField : list(section.get("fields"), 1, 80)) {
                    Field field = field(map(rawField), false, defaultRetention, fields, budget);
                    addField(fields, field);
                }
            }
        }
        if (fields.isEmpty() || fields.values().stream().noneMatch(field -> "summary".equals(field.key())
                && Set.of("TEXT", "TEXTAREA", "RICH_TEXT").contains(field.type()))) {
            throw invalid("Form Schema V3 requires a visible textual summary field.");
        }

        List<Calculation> calculations = calculations(definition.get("calculations"), fields, budget);
        List<Rule> rules = rules(definition.get("rules"), fields, budget);
        List<Scenario> scenarios = scenarios(definition.get("scenarios"), fields);
        return new ApprovalFormSchemaV3(definition, frozen.json(), frozen.sha256(), compatibility,
                fields, calculations, rules, scenarios);
    }

    public CompatibilityReport compare(ApprovalFormSchemaV3 base, ApprovalFormSchemaV3 candidate) {
        if (base == null || candidate == null) throw invalid("Both schemas are required for compatibility analysis.");
        List<CompatibilityChange> changes = new ArrayList<>();
        for (Field before : base.fields.values()) {
            Field after = candidate.fields.get(before.key());
            if (after == null) {
                changes.add(change("BREAKING", before.key(), "FIELD_REMOVED", "Existing field was removed."));
                continue;
            }
            if (!before.type().equals(after.type()) || !before.control().equals(after.control())) {
                changes.add(change("BREAKING", before.key(), "TYPE_CHANGED", "Field type or control changed."));
            }
            if (!before.required() && after.required() && after.defaultValue() == null) {
                changes.add(change("BREAKING", before.key(), "REQUIRED_WITHOUT_DEFAULT", "Optional field became required without a default."));
            }
            if (!after.viewRoles().containsAll(before.viewRoles()) || !after.editRoles().containsAll(before.editRoles())) {
                changes.add(change("BREAKING", before.key(), "ROLE_ACCESS_NARROWED", "Field view or edit roles were narrowed."));
            }
            if (classification(after.retention().classification()) < classification(before.retention().classification())) {
                changes.add(change("BREAKING", before.key(), "CLASSIFICATION_DOWNGRADED", "Field classification was reduced."));
            }
            if (!before.export().included() && after.export().included()) {
                changes.add(change("REVIEW", before.key(), "EXPORT_EXPANDED", "Previously excluded field is now exported."));
            }
        }
        for (Field after : candidate.fields.values()) {
            if (!base.fields.containsKey(after.key())) {
                String severity = after.required() && after.defaultValue() == null ? "BREAKING" : "COMPATIBLE";
                changes.add(change(severity, after.key(), "FIELD_ADDED", "New field was added."));
            }
        }
        boolean structurallyCompatible = changes.stream().noneMatch(change -> "BREAKING".equals(change.severity()));
        String mode = candidate.compatibility.mode();
        if (!"INITIAL".equals(mode) && !base.sha256().equals(candidate.compatibility.baseSchemaSha256())) {
            changes.add(change("BREAKING", null, "BASE_HASH_MISMATCH", "Compatibility base does not match the prior immutable schema."));
            structurallyCompatible = false;
        }
        boolean compatible = switch (mode) {
            case "INITIAL" -> false;
            case "BREAKING" -> true;
            case "STRICT", "BACKWARD_COMPATIBLE" -> structurallyCompatible;
            default -> false;
        };
        return new CompatibilityReport(base.sha256(), candidate.sha256(), compatible, changes);
    }

    private Compatibility compatibility(Map<String, Object> value) {
        only(value, Set.of("mode", "minimumRuntime", "baseSchemaSha256"));
        String mode = text(value.get("mode"), 32, false);
        if (!Set.of("INITIAL", "STRICT", "BACKWARD_COMPATIBLE", "BREAKING").contains(mode)) {
            throw invalid("Unsupported Form Schema V3 compatibility mode.");
        }
        String runtime = text(value.get("minimumRuntime"), 20, false);
        if (!"3.0".equals(runtime)) throw invalid("Form Schema V3 minimum runtime must be 3.0.");
        String base = optionalText(value.get("baseSchemaSha256"), 64, false);
        if ("INITIAL".equals(mode) ? base != null : base == null || !HASH.matcher(base).matches()) {
            throw invalid("Compatibility base hash does not match the selected mode.");
        }
        return new Compatibility(mode, runtime, base);
    }

    private Field field(Map<String, Object> value, boolean column, Retention defaults,
            Map<String, Field> known, Budget budget) {
        only(value, FIELD_KEYS);
        if (++budget.fields > 300) throw invalid("Form Schema V3 exceeds 300 fields.");
        String key = key(value.get("key"));
        String type = text(value.get("type"), 40, false);
        String control = text(value.get("control"), 40, false);
        if (!TYPES.contains(type) || !CONTROLS.getOrDefault(type, Set.of()).contains(control)) {
            throw invalid("Unsupported field type/control pairing: " + type + '/' + control);
        }
        if (column && Set.of("TABLE", "REPEATING_GROUP").contains(type)) {
            throw invalid("Nested table and repeating-group controls are not supported.");
        }
        Localized label = localized(value.get("label"), 200, false);
        Localized help = localized(value.get("help"), 1000, true);
        Span span = span(map(value.get("span")));
        boolean required = bool(value.get("required"), false);
        Map<String, Object> validation = value.containsKey("validation") ? map(value.get("validation")) : Map.of();
        validateValidation(validation, type);
        List<Option> options = options(value.get("options"));
        DataSource dataSource = value.containsKey("dataSource") ? dataSource(map(value.get("dataSource"))) : null;
        if (Set.of("SINGLE_SELECT", "MULTI_SELECT").contains(type) && options.size() < 2 && dataSource == null) {
            throw invalid("Selection controls require two options or a governed data source.");
        }
        if (!Set.of("SINGLE_SELECT", "MULTI_SELECT").contains(type) && !options.isEmpty()) {
            throw invalid("Only selection controls accept static options.");
        }
        if (dataSource != null && !DATA_SOURCE_TYPES.contains(type)) {
            throw invalid("This field type cannot use a data source reference.");
        }
        Set<String> viewRoles = roles(value.get("viewRoles"), true);
        Set<String> editRoles = roles(value.get("editRoles"), true);
        if (!viewRoles.contains("*") && (editRoles.contains("*") || !viewRoles.containsAll(editRoles))) {
            throw invalid("Field edit roles must be a subset of view roles.");
        }
        Retention retention = value.containsKey("retention") ? retention(map(value.get("retention"))) : defaults;
        Export export = value.containsKey("export") ? export(map(value.get("export"))) : defaultExport(label, type, retention);
        List<Field> columns = new ArrayList<>();
        boolean composite = Set.of("TABLE", "REPEATING_GROUP").contains(type);
        if (composite) {
            for (Object item : list(value.get("columns"), 1, 30)) {
                Field nested = field(map(item), true, retention, known, budget);
                if (known.containsKey(nested.key()) || columns.stream().anyMatch(existing -> existing.key().equals(nested.key()))) {
                    throw invalid("Duplicate field key: " + nested.key());
                }
                columns.add(nested);
            }
        } else if (value.containsKey("columns")) throw invalid("Columns require a table or repeating group.");
        if (new Field(key, type, control, label, help, span, required, value.get("defaultValue"), validation,
                options, dataSource, viewRoles, editRoles, retention, export, columns, column).displayOnly()
                && (required || value.containsKey("defaultValue") || !validation.isEmpty() || !editRoles.isEmpty())) {
            throw invalid("Display-only fields cannot be required, editable, defaulted, or validated.");
        }
        return new Field(key, type, control, label, help, span, required, value.get("defaultValue"), validation,
                options, dataSource, viewRoles, editRoles, retention, export, columns, column);
    }

    private void addField(Map<String, Field> fields, Field field) {
        if (fields.putIfAbsent(field.key(), field) != null) throw invalid("Duplicate field key: " + field.key());
        for (Field column : field.columns()) addField(fields, column);
    }

    private List<Calculation> calculations(Object raw, Map<String, Field> fields, Budget budget) {
        List<Calculation> values = new ArrayList<>();
        Set<String> keys = new HashSet<>(), targets = new HashSet<>();
        for (Object item : list(raw, 0, 100)) {
            Map<String, Object> value = map(item);
            only(value, Set.of("key", "target", "expression"));
            String key = key(value.get("key"));
            String target = key(value.get("target"));
            unique(keys, key, "calculation");
            unique(targets, target, "calculation target");
            Field field = requireField(fields, target);
            if (field.nested()) throw invalid("Calculations cannot target nested table fields.");
            if (!field.calculated()) throw invalid("Calculation target must use a calculated field type.");
            Set<String> dependencies = new LinkedHashSet<>();
            Expression expression = expression(value.get("expression"), fields, dependencies, 0, budget);
            values.add(new Calculation(key, target, expression, dependencies));
        }
        Map<String, Calculation> byTarget = new HashMap<>();
        for (Calculation calculation : values) byTarget.put(calculation.target(), calculation);
        List<Calculation> ordered = new ArrayList<>();
        Set<String> visiting = new HashSet<>(), visited = new HashSet<>();
        for (Calculation calculation : values) visitCalculation(calculation, byTarget, visiting, visited, ordered);
        return ordered;
    }

    private void visitCalculation(Calculation value, Map<String, Calculation> all, Set<String> visiting,
            Set<String> visited, List<Calculation> ordered) {
        if (visited.contains(value.target())) return;
        if (!visiting.add(value.target())) throw invalid("Cyclic Form Schema V3 calculation: " + value.target());
        for (String dependency : value.dependencies()) {
            Calculation calculated = all.get(dependency);
            if (calculated != null) visitCalculation(calculated, all, visiting, visited, ordered);
        }
        visiting.remove(value.target());
        visited.add(value.target());
        ordered.add(value);
    }

    private Expression expression(Object raw, Map<String, Field> fields, Set<String> dependencies,
            int depth, Budget budget) {
        node(depth, budget);
        Map<String, Object> value = map(raw);
        String op = text(value.get("op"), 20, false);
        return switch (op) {
            case "CONST" -> {
                only(value, Set.of("op", "value"));
                Object constant = value.get("value");
                if (!(constant instanceof String) && !(constant instanceof Number)) throw invalid("Invalid calculation constant.");
                yield new Expression(op, null, constant, 0, List.of());
            }
            case "FIELD" -> {
                only(value, Set.of("op", "field"));
                String field = key(value.get("field"));
                if (requireField(fields, field).nested()) throw invalid("Calculations cannot reference nested table fields.");
                dependencies.add(field);
                yield new Expression(op, field, null, 0, List.of());
            }
            case "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "MIN", "MAX", "CONCAT" -> {
                only(value, Set.of("op", "args"));
                List<Expression> args = expressions(value.get("args"), fields, dependencies, depth, budget,
                        "CONCAT".equals(op) ? 2 : 2, "CONCAT".equals(op) ? 20 : 2);
                yield new Expression(op, null, null, 0, args);
            }
            case "ROUND" -> {
                only(value, Set.of("op", "args", "scale"));
                List<Expression> args = expressions(value.get("args"), fields, dependencies, depth, budget, 1, 1);
                yield new Expression(op, null, null, integer(value.get("scale"), 0, 8), args);
            }
            case "IF" -> {
                only(value, Set.of("op", "args"));
                List<Expression> args = expressions(value.get("args"), fields, dependencies, depth, budget, 3, 3);
                yield new Expression(op, null, null, 0, args);
            }
            default -> throw invalid("Unknown Form Schema V3 calculation operator: " + op);
        };
    }

    private List<Expression> expressions(Object raw, Map<String, Field> fields, Set<String> dependencies,
            int depth, Budget budget, int min, int max) {
        List<Expression> result = new ArrayList<>();
        for (Object item : list(raw, min, max)) result.add(expression(item, fields, dependencies, depth + 1, budget));
        return result;
    }

    private List<Rule> rules(Object raw, Map<String, Field> fields, Budget budget) {
        List<Rule> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Object item : list(raw, 0, 200)) {
            Map<String, Object> value = map(item);
            only(value, Set.of("key", "when", "target", "effect", "message"));
            String key = key(value.get("key")); unique(keys, key, "rule");
            String target = key(value.get("target"));
            if (requireField(fields, target).nested()) throw invalid("Rules cannot target nested table fields.");
            String effect = text(value.get("effect"), 20, false);
            if (!Set.of("SHOW", "HIDE", "REQUIRE", "READ_ONLY", "ERROR").contains(effect)) {
                throw invalid("Unsupported Form Schema V3 rule effect.");
            }
            Localized message = value.containsKey("message") ? localized(value.get("message"), 500, false) : null;
            if ("ERROR".equals(effect) != (message != null)) throw invalid("Only ERROR rules require a message.");
            result.add(new Rule(key, predicate(value.get("when"), fields, 0, budget), target, effect, message));
        }
        return result;
    }

    private Predicate predicate(Object raw, Map<String, Field> fields, int depth, Budget budget) {
        node(depth, budget);
        Map<String, Object> value = map(raw);
        String op = text(value.get("op"), 20, false);
        if (Set.of("AND", "OR", "NOT").contains(op)) {
            only(value, Set.of("op", "args"));
            int min = "NOT".equals(op) ? 1 : 2, max = "NOT".equals(op) ? 1 : 20;
            List<Predicate> args = new ArrayList<>();
            for (Object item : list(value.get("args"), min, max)) args.add(predicate(item, fields, depth + 1, budget));
            return new Predicate(op, null, null, List.of(), args);
        }
        if ("ROLE_ANY".equals(op)) {
            only(value, Set.of("op", "values"));
            return new Predicate(op, null, null, new ArrayList<>(roles(value.get("values"), false)), List.of());
        }
        if (!Set.of("PRESENT", "EMPTY", "EQ", "NE", "IN", "GT", "GTE", "LT", "LTE", "CONTAINS").contains(op)) {
            throw invalid("Unknown Form Schema V3 predicate operator.");
        }
        Set<String> allowed = Set.of("PRESENT", "EMPTY").contains(op) ? Set.of("op", "field")
                : "IN".equals(op) ? Set.of("op", "field", "values") : Set.of("op", "field", "value");
        only(value, allowed);
        String field = key(value.get("field"));
        if (requireField(fields, field).nested()) throw invalid("Rules cannot reference nested table fields.");
        Object literal = value.get("value");
        List<Object> values = "IN".equals(op) ? new ArrayList<>(list(value.get("values"), 1, 100)) : List.of();
        return new Predicate(op, field, literal, values, List.of());
    }

    private List<Scenario> scenarios(Object raw, Map<String, Field> fields) {
        List<Scenario> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Object item : list(raw, 0, 50)) {
            Map<String, Object> value = map(item);
            only(value, Set.of("key", "input", "roles", "expected"));
            String key = key(value.get("key")); unique(keys, key, "scenario");
            Map<String, Object> input = map(value.get("input"));
            if (!fields.keySet().containsAll(input.keySet())) throw invalid("Scenario contains an unknown field.");
            Map<String, Object> expected = map(value.get("expected"));
            only(expected, Set.of("computed", "visibleFields", "requiredFields", "readOnlyFields", "violationCodes"));
            if (expected.containsKey("computed") && !fields.keySet().containsAll(map(expected.get("computed")).keySet())) {
                throw invalid("Scenario expects an unknown computed field.");
            }
            for (String property : List.of("visibleFields", "requiredFields", "readOnlyFields")) {
                if (expected.containsKey(property) && !fields.keySet().containsAll(strings(expected.get(property), 0, 300, 80, false))) {
                    throw invalid("Scenario expects an unknown field state.");
                }
            }
            result.add(new Scenario(key, input, roles(value.get("roles"), true), expected));
        }
        return result;
    }

    private void layout(Map<String, Object> value) {
        only(value, Set.of("columns", "gap"));
        integer(value.get("columns"), 1, 12);
        if (!Set.of("COMPACT", "STANDARD", "RELAXED").contains(text(value.get("gap"), 20, false))) {
            throw invalid("Unknown section layout gap.");
        }
    }

    private Span span(Map<String, Object> value) {
        only(value, Set.of("desktop", "tablet", "mobile"));
        return new Span(integer(value.get("desktop"), 1, 12), integer(value.get("tablet"), 1, 12),
                integer(value.get("mobile"), 1, 12));
    }

    private List<Option> options(Object raw) {
        if (raw == null) return List.of();
        List<Option> result = new ArrayList<>();
        Set<String> values = new HashSet<>();
        for (Object item : list(raw, 0, 200)) {
            Map<String, Object> value = map(item); only(value, Set.of("value", "label"));
            String code = text(value.get("value"), 160, false); unique(values, code, "option");
            result.add(new Option(code, localized(value.get("label"), 200, false)));
        }
        return result;
    }

    private DataSource dataSource(Map<String, Object> value) {
        only(value, Set.of("providerKey", "resource", "credentialRef", "valuePath", "labelPath", "cacheTtlSeconds"));
        String provider = text(value.get("providerKey"), 80, false);
        if (!CODE.matcher(provider).matches()) throw invalid("Invalid data-source provider key.");
        String resource = text(value.get("resource"), 240, false);
        try {
            URI parsed = URI.create(resource);
            if (parsed.isAbsolute() || resource.contains("..") || resource.contains("@")) throw invalid("Data-source resource must be an opaque provider path.");
        } catch (IllegalArgumentException exception) { throw invalid("Invalid data-source resource."); }
        String credential = optionalText(value.get("credentialRef"), 248, false);
        if (credential != null && !VAULT.matcher(credential).matches()) {
            throw invalid("Data-source credentials must be vault:// references.");
        }
        String valuePath = text(value.get("valuePath"), 160, false);
        String labelPath = text(value.get("labelPath"), 160, false);
        int ttl = integer(value.get("cacheTtlSeconds"), 0, 86_400);
        return new DataSource(provider, resource, credential, valuePath, labelPath, ttl);
    }

    private Retention retention(Map<String, Object> value) {
        only(value, Set.of("classification", "retentionClass", "redactAfterDays", "legalHoldEligible"));
        String classification = text(value.get("classification"), 20, false);
        String retention = text(value.get("retentionClass"), 20, false);
        if (!Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED").contains(classification)
                || !Set.of("STANDARD", "EXTENDED", "LEGAL").contains(retention)) {
            throw invalid("Invalid field retention metadata.");
        }
        Integer redact = value.containsKey("redactAfterDays") ? integer(value.get("redactAfterDays"), 1, 36_500) : null;
        return new Retention(classification, retention, redact, bool(value.get("legalHoldEligible"), false));
    }

    private Export export(Map<String, Object> value) {
        only(value, Set.of("included", "label", "format", "mask"));
        boolean included = bool(value.get("included"), false);
        Localized label = localized(value.get("label"), 200, false);
        String format = text(value.get("format"), 24, false);
        String mask = text(value.get("mask"), 16, false);
        if (!Set.of("TEXT", "NUMBER", "DATE", "DATETIME", "BOOLEAN", "REFERENCE", "BINARY_MANIFEST").contains(format)
                || !Set.of("NONE", "PARTIAL", "FULL").contains(mask)) throw invalid("Invalid field export metadata.");
        return new Export(included, label, format, mask);
    }

    private Export defaultExport(Localized label, String type, Retention retention) {
        String format = Set.of("NUMBER", "CURRENCY", "PERCENT", "CALCULATED_NUMBER").contains(type) ? "NUMBER"
                : "DATE".equals(type) ? "DATE" : "DATETIME".equals(type) ? "DATETIME"
                : "BOOLEAN".equals(type) ? "BOOLEAN" : "ATTACHMENT".equals(type) ? "BINARY_MANIFEST" : "TEXT";
        String mask = Set.of("CONFIDENTIAL", "RESTRICTED").contains(retention.classification()) ? "PARTIAL" : "NONE";
        return new Export(true, label, format, mask);
    }

    private void exportRoot(Map<String, Object> value) {
        only(value, Set.of("formatVersion", "includeAuditTrail"));
        if (!"1".equals(text(value.get("formatVersion"), 10, false))) throw invalid("Unsupported export format version.");
        bool(value.get("includeAuditTrail"), false);
    }

    private void validateValidation(Map<String, Object> value, String type) {
        only(value, Set.of("min", "max", "minLength", "maxLength", "pattern", "minItems", "maxItems",
                "allowedMimeTypes", "maxFileBytes", "maxFiles"));
        BigDecimal min = value.containsKey("min") ? decimal(value.get("min")) : null;
        BigDecimal max = value.containsKey("max") ? decimal(value.get("max")) : null;
        if ((min != null || max != null) && !Set.of("NUMBER", "CURRENCY", "PERCENT", "CALCULATED_NUMBER").contains(type)) {
            throw invalid("Numeric validation requires a numeric field.");
        }
        if (min != null && max != null && min.compareTo(max) > 0) throw invalid("Invalid numeric validation range.");
        int minLength = value.containsKey("minLength") ? integer(value.get("minLength"), 0, 20_000) : 0;
        int maxLength = value.containsKey("maxLength") ? integer(value.get("maxLength"), 1, 20_000) : 20_000;
        if (minLength > maxLength) throw invalid("Invalid text validation range.");
        if (value.containsKey("pattern")) safePattern(text(value.get("pattern"), 512, false));
        int minItems = value.containsKey("minItems") ? integer(value.get("minItems"), 0, 200) : 0;
        int maxItems = value.containsKey("maxItems") ? integer(value.get("maxItems"), 1, 200) : 200;
        if (minItems > maxItems) throw invalid("Invalid collection validation range.");
        if (value.keySet().stream().anyMatch(Set.of("allowedMimeTypes", "maxFileBytes", "maxFiles")::contains)
                && !"ATTACHMENT".equals(type)) throw invalid("File validation requires an attachment field.");
        if (value.containsKey("allowedMimeTypes")) {
            for (String mime : strings(value.get("allowedMimeTypes"), 1, 30, 100, false)) {
                if (!mime.matches("[a-z0-9.+-]+/[a-z0-9.+*-]+")) throw invalid("Invalid attachment MIME type.");
            }
        }
        if (value.containsKey("maxFileBytes")) decimalRange(value.get("maxFileBytes"), 1, 1_073_741_824L);
        if (value.containsKey("maxFiles")) integer(value.get("maxFiles"), 1, 50);
    }

    private void safePattern(String value) {
        if (value.contains("(?") || value.matches(".*\\\\[1-9].*")
                || value.matches(".*[+*}]\\s*[+*{].*")
                || value.matches(".*\\([^)]*[+*][^)]*\\)[+*{].*")) {
            throw invalid("Unsafe regular-expression features are not allowed.");
        }
        try { Pattern.compile(value); } catch (RuntimeException exception) { throw invalid("Invalid validation pattern."); }
    }

    private Localized localized(Object raw, int max, boolean blankAllowed) {
        Map<String, Object> value = map(raw); only(value, Set.of("ko", "en"));
        return new Localized(text(value.get("ko"), max, blankAllowed), text(value.get("en"), max, blankAllowed));
    }

    private Set<String> roles(Object raw, boolean allowWildcard) {
        Set<String> values = strings(raw, 0, 100, 80, false);
        for (String value : values) {
            if (!(allowWildcard && "*".equals(value)) && !CODE.matcher(value).matches()) throw invalid("Invalid field role code.");
        }
        return values;
    }

    private Set<String> strings(Object raw, int min, int max, int length, boolean blank) {
        Set<String> result = new LinkedHashSet<>();
        for (Object item : list(raw, min, max)) unique(result, text(item, length, blank), "list value");
        return Set.copyOf(result);
    }

    private void rejectSecrets(Object value, String parent) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = (String) entry.getKey();
                String normalized = key.toLowerCase(java.util.Locale.ROOT).replace("-", "_");
                if (normalized.matches(".*(^|_)(secret|password|token|api_?key|private_?key|access_?token)($|_).*")
                        || "credential".equals(normalized)) throw invalid("Literal secret material is forbidden in Form Schema V3.");
                if ("credentialRef".equals(key) && entry.getValue() instanceof String reference
                        && !VAULT.matcher(reference).matches()) throw invalid("Credential references must use vault://.");
                rejectSecrets(entry.getValue(), key);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) rejectSecrets(item, parent);
        }
    }

    private Field requireField(Map<String, Field> fields, String key) {
        Field field = fields.get(key);
        if (field == null) throw invalid("Unknown Form Schema V3 field reference: " + key);
        return field;
    }

    private String key(Object value) {
        String key = text(value, 80, false);
        if (!KEY.matcher(key).matches()) throw invalid("Invalid Form Schema V3 key.");
        return key;
    }

    private String text(Object value, int max, boolean blankAllowed) {
        if (!(value instanceof String text) || text.length() > max || !text.equals(text.strip())
                || !blankAllowed && text.isBlank() || text.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Invalid Form Schema V3 text value.");
        }
        return text;
    }

    private String optionalText(Object value, int max, boolean blankAllowed) {
        return value == null ? null : text(value, max, blankAllowed);
    }

    private boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw invalid("Expected a boolean value.");
        return result;
    }

    private int integer(Object value, int min, int max) {
        try {
            int result = decimal(value).intValueExact();
            if (result < min || result > max) throw invalid("Integer is outside the allowed range.");
            return result;
        } catch (ArithmeticException exception) { throw invalid("Expected an integer value."); }
    }

    private long decimalRange(Object value, long min, long max) {
        try {
            long result = decimal(value).longValueExact();
            if (result < min || result > max) throw invalid("Integer is outside the allowed range.");
            return result;
        } catch (ArithmeticException exception) { throw invalid("Expected an integer value."); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) throw invalid("Expected a Form Schema V3 object.");
        return (Map<String, Object>) value;
    }

    private List<?> list(Object value, int min, int max) {
        if (!(value instanceof List<?> result) || result.size() < min || result.size() > max) {
            throw invalid("Form Schema V3 list size is outside the allowed range.");
        }
        return result;
    }

    private void only(Map<String, Object> value, Set<String> allowed) {
        if (!allowed.containsAll(value.keySet())) throw invalid("Unknown Form Schema V3 property.");
    }

    private void unique(Set<String> values, String value, String kind) {
        if (!values.add(value)) throw invalid("Duplicate " + kind + ": " + value);
    }

    private void node(int depth, Budget budget) {
        if (depth > 10 || ++budget.expressionNodes > 4_096) throw invalid("Rule or calculation complexity exceeds the limit.");
    }

    private int classification(String value) {
        return List.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED").indexOf(value);
    }

    private CompatibilityChange change(String severity, String field, String code, String detail) {
        return new CompatibilityChange(severity, field, code, detail);
    }

    private BaseException invalid(String message) { return ApprovalFormSchemaV3Canonical.invalid(message); }
    private static final class Budget { private int fields; private int expressionNodes; }
}
