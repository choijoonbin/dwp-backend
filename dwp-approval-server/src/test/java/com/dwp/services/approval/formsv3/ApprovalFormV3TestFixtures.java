package com.dwp.services.approval.formsv3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ApprovalFormV3TestFixtures {
    private ApprovalFormV3TestFixtures() { }

    static Map<String, Object> schema(String mode, String baseHash, List<Map<String, Object>> fields,
            List<Map<String, Object>> calculations, List<Map<String, Object>> rules,
            List<Map<String, Object>> scenarios) {
        Map<String, Object> compatibility = map("mode", mode, "minimumRuntime", "3.0");
        if (baseHash != null) compatibility.put("baseSchemaSha256", baseHash);
        return map("schemaContract", ApprovalFormSchemaV3.CONTRACT, "schemaVersion", 3,
                "locales", List.of("ko", "en"), "compatibility", compatibility,
                "pages", List.of(map("key", "request", "title", localized("요청", "Request"),
                        "sections", List.of(map("key", "details", "title", localized("상세", "Details"),
                                "layout", map("columns", 12, "gap", "STANDARD"), "fields", fields)))),
                "rules", rules, "calculations", calculations, "scenarios", scenarios,
                "retention", retention(), "export", map("formatVersion", "1", "includeAuditTrail", true));
    }

    static Map<String, Object> enterpriseSchema() {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("summary", "TEXTAREA", "TEXTAREA", true));
        fields.add(field("amount", "CURRENCY", "CURRENCY_INPUT", true));
        fields.add(field("tax", "NUMBER", "NUMBER_INPUT", false));
        fields.add(field("total", "CALCULATED_NUMBER", "CALCULATED_NUMBER", false));
        Map<String, Object> evidence = field("evidence", "ATTACHMENT", "FILE_UPLOAD", false);
        evidence.put("validation", map("maxFiles", 3, "maxFileBytes", 1_000_000,
                "allowedMimeTypes", List.of("application/pdf")));
        fields.add(evidence);
        fields.add(selection("country", "SINGLE_SELECT", "SELECT"));
        fields.add(field("reviewer", "USER", "USER_PICKER", false));
        List<Map<String, Object>> calculations = List.of(map("key", "totalCalculation", "target", "total",
                "expression", map("op", "ADD", "args", List.of(
                        map("op", "FIELD", "field", "amount"), map("op", "FIELD", "field", "tax")))));
        List<Map<String, Object>> rules = List.of(map("key", "highAmountEvidence", "target", "evidence",
                "effect", "REQUIRE", "when", map("op", "GT", "field", "total", "value", "1000")));
        List<Map<String, Object>> scenarios = List.of(map("key", "highAmount", "input",
                map("summary", "Purchase", "amount", "1000", "tax", "100",
                        "country", "KR", "evidence", List.of(map("id", "a", "name", "a.pdf",
                                "mimeType", "application/pdf", "size", 1000))),
                "roles", List.of("APPROVAL_OPERATOR"), "expected", map("computed", map("total", "1100"),
                        "requiredFields", List.of("summary", "amount", "evidence"), "violationCodes", List.of())));
        return schema("INITIAL", null, fields, calculations, rules, scenarios);
    }

    static Map<String, Object> field(String key, String type, String control, boolean required) {
        return map("key", key, "type", type, "control", control,
                "label", localized("필드 " + key, "Field " + key),
                "help", localized("도움말", "Help"),
                "span", map("desktop", 6, "tablet", 12, "mobile", 12),
                "required", required, "validation", map(), "options", List.of(),
                "viewRoles", List.of("*"), "editRoles",
                type.startsWith("CALCULATED_") || List.of("HEADING", "PARAGRAPH", "DIVIDER").contains(type)
                        ? List.of() : List.of("*"),
                "retention", retention(), "export", export(key, type));
    }

    static Map<String, Object> selection(String key, String type, String control) {
        Map<String, Object> field = field(key, type, control, false);
        field.put("options", List.of(map("value", "KR", "label", localized("한국", "Korea")),
                map("value", "US", "label", localized("미국", "United States"))));
        return field;
    }

    static Map<String, Object> localized(String ko, String en) { return map("ko", ko, "en", en); }
    static Map<String, Object> retention() { return map("classification", "INTERNAL",
            "retentionClass", "STANDARD", "legalHoldEligible", true); }
    static Map<String, Object> export(String key, String type) { return map("included", true,
            "label", localized("필드 " + key, "Field " + key),
            "format", type.contains("NUMBER") || "CURRENCY".equals(type) || "PERCENT".equals(type) ? "NUMBER" : "TEXT",
            "mask", "NONE"); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> copy(Map<String, Object> source) {
        return (Map<String, Object>) mutable(source);
    }

    private static Object mutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put((String) key, mutable(item)));
            return result;
        }
        if (value instanceof List<?> list) return new ArrayList<>(list.stream().map(ApprovalFormV3TestFixtures::mutable).toList());
        return value;
    }

    static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }
}
