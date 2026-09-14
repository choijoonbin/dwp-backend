package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ApprovalCommandPayloadSupport {

    private final ObjectMapper objectMapper;

    ApprovalCommandPayloadSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Approval payload could not be serialized.", exception);
        }
    }

    Map<String, Object> object(String value, String invalidMessage) {
        try {
            return objectMapper.readValue(
                    value, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INVALID_STATE, invalidMessage);
        }
    }

    void validateWorkflowInput(
            String category,
            String classification,
            int workflowSlaMinutes,
            List<ApprovalDtos.WorkflowStepInput> steps) {
        normalized(category, Set.of("FINANCE", "PEOPLE", "PROCUREMENT", "ACCESS", "GENERAL"));
        normalized(classification, Set.of("INTERNAL", "CONFIDENTIAL", "RESTRICTED"));
        if (steps == null || steps.isEmpty() || steps.size() > 20) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        Set<String> keys = new HashSet<>();
        long totalStepSla = 0;
        for (ApprovalDtos.WorkflowStepInput step : steps) {
            normalized(step.mode(), Set.of("ANY"));
            if (!keys.add(step.key().trim().toUpperCase(Locale.ROOT))) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
            }
            if (!step.candidateRole().trim().matches("[A-Z][A-Z0-9_]{1,79}")) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
            }
            totalStepSla += step.slaMinutes();
        }
        if (totalStepSla > workflowSlaMinutes) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The sum of step SLAs cannot exceed the workflow SLA.");
        }
    }

    List<ApprovalCommandRepository.RuntimeStep> runtimeSteps(
            String definition,
            int workflowSlaMinutes) {
        ApprovalWorkflowQuorumDefinition typed = quorumDefinition(definition);
        if (typed != null) {
            if (typed.slaMinutes() != workflowSlaMinutes) throw new BaseException(ErrorCode.INVALID_STATE);
            return typed.topologicalStages().stream().map(stage -> new ApprovalCommandRepository.RuntimeStep(
                    stage.key(), stage.name(), stage.quorum().mode().name(), stage.candidateRole(), stage.slaMinutes())).toList();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    definition, new TypeReference<Map<String, Object>>() { });
            Object rawSteps = payload.get("steps");
            if (!(rawSteps instanceof List<?> values) || values.isEmpty()) {
                return List.of(defaultRuntimeStep(workflowSlaMinutes));
            }
            List<ApprovalCommandRepository.RuntimeStep> steps = new ArrayList<>();
            for (Object rawStep : values) {
                if (!(rawStep instanceof Map<?, ?> value)) {
                    throw new BaseException(ErrorCode.INVALID_STATE);
                }
                String key = requiredRuntimeString(value, "key").toUpperCase(Locale.ROOT);
                String name = runtimeString(value, "name", key);
                String mode = normalized(runtimeString(value, "mode", "ANY"), Set.of("ANY"));
                String candidateRole = requiredRuntimeString(value, "candidateRole")
                        .toUpperCase(Locale.ROOT);
                Object rawSlaMinutes = value.get("slaMinutes");
                int slaMinutes = rawSlaMinutes instanceof Number number
                        ? number.intValue()
                        : Integer.parseInt(String.valueOf(rawSlaMinutes));
                if (slaMinutes < 15 || slaMinutes > 525600
                        || !key.matches("[A-Z][A-Z0-9_]{1,79}")
                        || !candidateRole.matches("[A-Z][A-Z0-9_]{1,79}")) {
                    throw new BaseException(ErrorCode.INVALID_STATE);
                }
                steps.add(new ApprovalCommandRepository.RuntimeStep(
                        key, name, mode, candidateRole, slaMinutes));
            }
            return List.copyOf(steps);
        } catch (JsonProcessingException | NumberFormatException exception) {
            throw new BaseException(ErrorCode.INVALID_STATE);
        }
    }

    void validateFormFields(List<ApprovalDtos.FormFieldInput> fields) {
        if (fields == null || fields.isEmpty() || fields.size() > 50) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Legacy forms require 1 to 50 fields.");
        }
        Set<String> keys = new HashSet<>();
        for (ApprovalDtos.FormFieldInput field : fields) {
            String type = normalized(
                    field.type(), Set.of("TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT", "USER"));
            if (!keys.add(field.key().trim())) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
            }
            List<String> options = normalizedOptions(field.options());
            if ("SELECT".equals(type) && options.size() < 2) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Select fields require at least two unique options.");
            }
            if (!"SELECT".equals(type) && !options.isEmpty()) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Only select fields can define options.");
            }
        }
    }

    Map<String, Object> workflowDefinition(List<ApprovalDtos.WorkflowStepInput> steps) {
        return Map.of(
                "schemaVersion", 1,
                "steps", steps.stream().map(step -> Map.<String, Object>of(
                        "key", step.key().trim().toUpperCase(Locale.ROOT),
                        "name", step.name().trim(),
                        "mode", normalized(step.mode(), Set.of("ANY")),
                        "candidateRole", step.candidateRole().trim(),
                        "slaMinutes", step.slaMinutes())).toList(),
                "guardrails", Map.of(
                        "selfApproval", false,
                        "requireReasonOnReject", true,
                        "optimisticConcurrency", true));
    }

    ApprovalWorkflowQuorumDefinition quorumDefinition(String definition) {
        Map<String, Object> value = object(definition, "Stored approval workflow is invalid.");
        if (!value.containsKey("schemaContract")) return null;
        if (!ApprovalWorkflowQuorum.CONTRACT.equals(value.get("schemaContract"))) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Unknown stored workflow contract.");
        }
        return ApprovalWorkflowQuorumDefinition.compile(definition);
    }

    String workflowDefinitionJson(String category, String classification, int slaMinutes,
            List<ApprovalDtos.WorkflowStepInput> steps, Map<String, Object> typedDefinition) {
        if (typedDefinition == null) {
            validateWorkflowInput(category, classification, slaMinutes, steps);
            return json(workflowDefinition(steps));
        }
        normalized(category, Set.of("FINANCE", "PEOPLE", "PROCUREMENT", "ACCESS", "GENERAL"));
        normalized(classification, Set.of("INTERNAL", "CONFIDENTIAL", "RESTRICTED"));
        if (steps != null && !steps.isEmpty()) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                "Choose a legacy workflow or a typed quorum definition, not both.");
        var compiled = ApprovalWorkflowQuorumDefinition.compile(typedDefinition);
        if (compiled.slaMinutes() != slaMinutes) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                "Workflow SLA must equal the sealed typed definition SLA.");
        return compiled.canonicalJson();
    }

    Map<String, Object> defaultFormSchema() {
        return Map.of(
                "schemaVersion", 1,
                "fields", List.of(
                        Map.of("key", "summary", "labelKo", "요청 내용",
                                "labelEn", "Request summary", "type", "TEXTAREA", "required", true),
                        Map.of("key", "amount", "labelKo", "금액",
                                "labelEn", "Amount", "type", "NUMBER", "required", false),
                        Map.of("key", "neededBy", "labelKo", "필요 일자",
                                "labelEn", "Needed by", "type", "DATE", "required", false)));
    }

    Map<String, Object> formSchema(List<ApprovalDtos.FormFieldInput> fields) {
        return Map.of(
                "schemaVersion", 1,
                "fields", fields.stream().map(this::formFieldSchema).toList());
    }

    String formSchemaJson(List<ApprovalDtos.FormFieldInput> fields, Map<String, Object> typedSchema) {
        if (typedSchema != null) {
            if (fields != null && !fields.isEmpty()) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Choose either legacy fields or a typed schema.");
            }
            ApprovalFormSchemaV2 compiled = new ApprovalFormSchemaV2Compiler().compile(typedSchema);
            requireTypedSummary(compiled);
            return compiled.canonicalJson();
        }
        validateFormFields(fields);
        return json(formSchema(fields));
    }

    boolean isTypedFormSchema(Map<String, Object> definition) {
        if (!definition.containsKey("schemaContract")) return false;
        if (!ApprovalFormSchemaV2.CONTRACT.equals(definition.get("schemaContract"))) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Unknown stored approval schema contract.");
        }
        return true;
    }

    boolean isTypedFormSchema(String schema) {
        return isTypedFormSchema(object(schema, "Stored approval form schema is invalid."));
    }

    void validateStoredFormSchema(String schema) {
        Map<String, Object> definition = object(schema, "Stored approval form schema is invalid.");
        if (isTypedFormSchema(definition)) requireTypedSummary(new ApprovalFormSchemaV2Compiler().compile(definition));
    }

    private void requireTypedSummary(ApprovalFormSchemaV2 schema) {
        ApprovalFormSchemaV2.Field summary = schema.scope.fields().get("summary");
        if (summary == null || !Set.of("TEXT", "TEXTAREA").contains(summary.type()) || summary.visibleWhen() != null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Typed request forms require an always-visible summary text field.");
        }
    }

    private Map<String, Object> formFieldSchema(ApprovalDtos.FormFieldInput field) {
        String type = normalized(
                field.type(), Set.of("TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT", "USER"));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("key", field.key().trim());
        value.put("labelKo", field.labelKo().trim());
        value.put("labelEn", field.labelEn().trim());
        value.put("helpKo", normalizedOptional(field.helpKo()));
        value.put("helpEn", normalizedOptional(field.helpEn()));
        value.put("type", type);
        value.put("required", field.required());
        value.put("options", "SELECT".equals(type) ? normalizedOptions(field.options()) : List.of());
        return value;
    }

    private ApprovalCommandRepository.RuntimeStep defaultRuntimeStep(int slaMinutes) {
        return new ApprovalCommandRepository.RuntimeStep(
                "PRIMARY_REVIEW", "Primary review", "ANY", "APPROVAL_OPERATOR", slaMinutes);
    }

    private String requiredRuntimeString(Map<?, ?> value, String key) {
        Object raw = value.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new BaseException(ErrorCode.INVALID_STATE);
        }
        return String.valueOf(raw).trim();
    }

    private String runtimeString(Map<?, ?> value, String key, String fallback) {
        Object raw = value.get(key);
        return raw == null || String.valueOf(raw).isBlank() ? fallback : String.valueOf(raw).trim();
    }

    private List<String> normalizedOptions(List<String> options) {
        if (options == null) return List.of();
        return options.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private String normalizedOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalized(String value, Set<String> allowed) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        return normalized;
    }
}
