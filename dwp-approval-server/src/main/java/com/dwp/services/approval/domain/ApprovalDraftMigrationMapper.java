package com.dwp.services.approval.domain;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Component
public class ApprovalDraftMigrationMapper {
    private final ObjectMapper mapper;
    private final ApprovalCommandRepository commands;

    public ApprovalDraftMigrationMapper(ObjectMapper mapper, ApprovalCommandRepository commands) {
        this.mapper = mapper;
        this.commands = commands;
    }

    public Mapping map(UUID sourceRequestId, Map<String, Object> sourcePayload,
                       ApprovalDraftMigrationRepository.Target target) {
        Map<String, Object> definition = definition(target.formSchema());
        List<String> targetFields = fieldKeys(definition);
        Set<String> targetSet = Set.copyOf(targetFields);
        Set<String> dropped = new TreeSet<>();
        Set<String> incompatible = new TreeSet<>();
        List<String> mapped = new ArrayList<>();
        Map<String, Object> accepted = new LinkedHashMap<>();

        sourcePayload.keySet().stream()
                .filter(key -> !"createdFrom".equals(key) && !targetSet.contains(key))
                .forEach(dropped::add);
        for (String key : targetFields) {
            if (!sourcePayload.containsKey(key)) continue;
            Map<String, Object> candidate = new LinkedHashMap<>(accepted);
            candidate.put(key, sourcePayload.get(key));
            candidate.put("createdFrom", sourceRequestId.toString());
            try {
                Map<String, Object> normalized = commands.normalizeRequestPayload(
                        target.formSchema(), candidate, false);
                if (normalized.containsKey(key)) {
                    accepted.put(key, sourcePayload.get(key));
                    mapped.add(key);
                } else {
                    dropped.add(key);
                }
            } catch (BaseException exception) {
                incompatible.add(key);
            }
        }
        accepted.put("createdFrom", sourceRequestId.toString());
        Map<String, Object> normalized = commands.normalizeRequestPayload(
                target.formSchema(), accepted, false);
        List<String> required = requiredMissing(definition, normalized);
        boolean routeCompatible = !"CONDITIONAL".equals(target.bindingType())
                || commands.matchesRouteCondition(
                target.bindingCondition(), normalized, target.formSchema());
        return new Mapping(normalized, mapped, List.copyOf(dropped),
                List.copyOf(incompatible), required, routeCompatible);
    }

    private Map<String, Object> definition(String schema) {
        try {
            return mapper.readValue(schema, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("Stored migration target schema is invalid.", exception);
        }
    }

    private List<String> fieldKeys(Map<String, Object> definition) {
        if (ApprovalFormSchemaV2.CONTRACT.equals(definition.get("schemaContract"))) {
            return new ApprovalFormSchemaV2Compiler().compile(definition).scope.order().stream()
                    .filter(field -> field.calculation() == null)
                    .map(ApprovalFormSchemaV2.Field::key).toList();
        }
        Object fields = definition.get("fields");
        if (!(fields instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalStateException("Stored migration target schema has no fields.");
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> field) || !(field.get("key") instanceof String key)
                    || key.isBlank() || !keys.add(key)) {
                throw new IllegalStateException("Stored migration target schema has invalid fields.");
            }
        }
        return List.copyOf(keys);
    }

    private List<String> requiredMissing(Map<String, Object> definition, Map<String, Object> payload) {
        if (ApprovalFormSchemaV2.CONTRACT.equals(definition.get("schemaContract"))) {
            ApprovalFormSchemaV2 schema = new ApprovalFormSchemaV2Compiler().compile(definition);
            ApprovalFormSchemaV2.Evaluation evaluation =
                    new ApprovalFormSchemaV2Evaluator().evaluate(schema, payload, false);
            return evaluation.requiredFields().stream()
                    .filter(path -> !present(schema.scope, evaluation.payload(), path, ""))
                    .sorted().toList();
        }
        List<String> missing = new ArrayList<>();
        for (Object value : (List<?>) definition.get("fields")) {
            Map<?, ?> field = (Map<?, ?>) value;
            String key = String.valueOf(field.get("key"));
            if (Boolean.TRUE.equals(field.get("required")) && empty(payload.get(key))) missing.add(key);
        }
        return List.copyOf(missing);
    }

    private boolean present(ApprovalFormSchemaV2.Scope scope, Map<String, Object> payload,
                            String expectedPath, String prefix) {
        for (ApprovalFormSchemaV2.Field field : scope.order()) {
            String path = prefix + field.key();
            Object value = payload.get(field.key());
            if (path.equals(expectedPath)) return !empty(value);
            if (field.group() && value instanceof List<?> rows) {
                for (int index = 0; index < rows.size(); index++) {
                    Object row = rows.get(index);
                    if (row instanceof Map<?, ?> values) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) values;
                        if (present(field.children(), typed, expectedPath,
                                path + "[" + index + "].")) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean empty(Object value) {
        return value == null || value instanceof String text && text.isBlank()
                || value instanceof List<?> values && values.isEmpty();
    }

    public record Mapping(
            Map<String, Object> payload,
            List<String> mappedFields,
            List<String> droppedFields,
            List<String> incompatibleFields,
            List<String> requiredFieldsToComplete,
            boolean routeCompatible) {
        public Mapping {
            payload = Map.copyOf(payload);
            mappedFields = List.copyOf(mappedFields);
            droppedFields = List.copyOf(droppedFields);
            incompatibleFields = List.copyOf(incompatibleFields);
            requiredFieldsToComplete = List.copyOf(requiredFieldsToComplete);
        }
    }
}
