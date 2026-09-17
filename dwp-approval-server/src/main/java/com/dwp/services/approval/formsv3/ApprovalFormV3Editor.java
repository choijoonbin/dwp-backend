package com.dwp.services.approval.formsv3;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ApprovalFormV3Editor {
    private static final Set<String> FIELD_PROPERTIES = Set.of("type", "control", "label", "help", "span",
            "required", "defaultValue", "validation", "options", "dataSource", "viewRoles", "editRoles",
            "retention", "export", "columns");

    Map<String, Object> add(Map<String, Object> source, String pageKey, String sectionKey,
            int index, Map<String, Object> field, String compatibilityMode, String baseHash) {
        Map<String, Object> schema = schema(source, compatibilityMode, baseHash);
        List<Map<String, Object>> fields = fields(schema, pageKey, sectionKey);
        if (index < 0 || index > fields.size()) throw invalid("Field insertion index is outside the section.");
        String key = key(field == null ? null : field.get("key"), "Field key");
        if (find(schema, key) != null) throw conflict("The field key already exists.");
        fields.add(index, object(field));
        return schema;
    }

    Map<String, Object> delete(Map<String, Object> source, String fieldKey,
            String compatibilityMode, String baseHash) {
        Map<String, Object> schema = schema(source, compatibilityMode, baseHash);
        FieldLocation location = require(schema, fieldKey);
        location.fields().remove(location.index());
        return schema;
    }

    Map<String, Object> cloneField(Map<String, Object> source, String sourceFieldKey,
            String targetFieldKey, int index, String compatibilityMode, String baseHash) {
        Map<String, Object> schema = schema(source, compatibilityMode, baseHash);
        FieldLocation sourceLocation = require(schema, sourceFieldKey);
        String target = key(targetFieldKey, "Target field key");
        if (find(schema, target) != null) throw conflict("The cloned field key already exists.");
        if (index < 0 || index > sourceLocation.fields().size()) {
            throw invalid("Cloned field insertion index is outside the section.");
        }
        Map<String, Object> cloned = object(sourceLocation.field());
        cloned.put("key", target);
        sourceLocation.fields().add(index, cloned);
        return schema;
    }

    Map<String, Object> reorder(Map<String, Object> source, String pageKey, String sectionKey,
            List<String> orderedFieldKeys, String baseHash) {
        Map<String, Object> schema = schema(source, "STRICT", baseHash);
        List<Map<String, Object>> fields = fields(schema, pageKey, sectionKey);
        if (orderedFieldKeys == null || orderedFieldKeys.size() != fields.size()
                || new LinkedHashSet<>(orderedFieldKeys).size() != orderedFieldKeys.size()) {
            throw invalid("Field order must contain each section field exactly once.");
        }
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) byKey.put(key(field.get("key"), "Field key"), field);
        if (!byKey.keySet().equals(new LinkedHashSet<>(orderedFieldKeys))) {
            throw conflict("Field order does not match the current section.");
        }
        fields.clear();
        orderedFieldKeys.forEach(value -> fields.add(byKey.get(value)));
        return schema;
    }

    Map<String, Object> properties(Map<String, Object> source, String fieldKey,
            Map<String, Object> properties, String compatibilityMode, String baseHash) {
        Map<String, Object> schema = schema(source, compatibilityMode, baseHash);
        if (properties == null || properties.isEmpty() || !FIELD_PROPERTIES.containsAll(properties.keySet())) {
            throw invalid("Field properties contain an unsupported or immutable member.");
        }
        FieldLocation location = require(schema, fieldKey);
        properties.forEach((name, value) -> location.field().put(name, copy(value)));
        return schema;
    }

    private Map<String, Object> schema(Map<String, Object> source, String mode, String baseHash) {
        if (!Set.of("STRICT", "BACKWARD_COMPATIBLE", "BREAKING").contains(mode)) {
            throw invalid("Editor compatibility mode is invalid.");
        }
        Map<String, Object> result = object(source);
        result.put("compatibility", new LinkedHashMap<>(Map.of(
                "mode", mode, "minimumRuntime", "3.0", "baseSchemaSha256", baseHash)));
        return result;
    }

    private List<Map<String, Object>> fields(Map<String, Object> schema, String pageKey, String sectionKey) {
        String page = key(pageKey, "Page key"), section = key(sectionKey, "Section key");
        for (Object rawPage : list(schema.get("pages"))) {
            Map<String, Object> candidatePage = castObject(rawPage);
            if (!page.equals(candidatePage.get("key"))) continue;
            for (Object rawSection : list(candidatePage.get("sections"))) {
                Map<String, Object> candidateSection = castObject(rawSection);
                if (section.equals(candidateSection.get("key"))) return castObjectList(candidateSection.get("fields"));
            }
        }
        throw conflict("The target page or section changed.");
    }

    private FieldLocation require(Map<String, Object> schema, String fieldKey) {
        FieldLocation location = find(schema, key(fieldKey, "Field key"));
        if (location == null) throw conflict("The target field changed or no longer exists.");
        return location;
    }

    private FieldLocation find(Map<String, Object> schema, String fieldKey) {
        for (Object rawPage : list(schema.get("pages"))) {
            Map<String, Object> page = castObject(rawPage);
            for (Object rawSection : list(page.get("sections"))) {
                List<Map<String, Object>> fields = castObjectList(castObject(rawSection).get("fields"));
                for (int index = 0; index < fields.size(); index++) {
                    if (fieldKey.equals(fields.get(index).get("key"))) return new FieldLocation(fields, index);
                }
            }
        }
        return null;
    }

    private String key(Object value, String label) {
        if (!(value instanceof String text) || !text.matches("[a-z][A-Za-z0-9_]{1,79}")) {
            throw invalid(label + " is invalid.");
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid("Form editor material is malformed.");
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castObjectList(Object value) {
        if (!(value instanceof List<?> list)) throw invalid("Form editor material is malformed.");
        return (List<Map<String, Object>>) list;
    }

    private List<?> list(Object value) {
        if (!(value instanceof List<?> list)) throw invalid("Form editor material is malformed.");
        return list;
    }

    private Map<String, Object> object(Map<?, ?> source) {
        if (source == null) throw invalid("Form editor object is required.");
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((name, value) -> {
            if (!(name instanceof String key)) throw invalid("Form editor object keys must be strings.");
            result.put(key, copy(value));
        });
        return result;
    }

    private Object copy(Object value) {
        if (value instanceof Map<?, ?> map) return object(map);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(copy(item)));
            return result;
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw invalid("Form editor value is not JSON compatible.");
    }

    private BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
    private BaseException conflict(String message) { return new BaseException(ErrorCode.RESOURCE_CONFLICT, message); }

    private record FieldLocation(List<Map<String, Object>> fields, int index) {
        Map<String, Object> field() { return fields.get(index); }
    }
}
