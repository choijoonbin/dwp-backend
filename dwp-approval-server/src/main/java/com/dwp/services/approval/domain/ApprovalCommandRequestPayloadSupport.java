package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates and normalizes mutable approval request and information-response payloads. */
final class ApprovalCommandRequestPayloadSupport {

    void validate(
            Map<String, Object> definition,
            boolean typedFormSchema,
            Map<String, Object> payload,
            boolean requireRequiredFields) {
        if (typedFormSchema) {
            new ApprovalFormSchemaV2Evaluator().evaluate(
                    new ApprovalFormSchemaV2Compiler().compile(definition),
                    payload,
                    requireRequiredFields);
            return;
        }
        Object rawFields = definition.get("fields");
        if (!(rawFields instanceof List<?> fields) || fields.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_STATE);
        }
        Set<String> knownKeys = new HashSet<>();
        for (Object rawField : fields) {
            if (!(rawField instanceof Map<?, ?> field)) {
                throw new BaseException(ErrorCode.INVALID_STATE);
            }
            String key = requiredString(field, "key");
            String type = normalized(requiredString(field, "type"),
                    Set.of("TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT", "USER"));
            boolean required = Boolean.parseBoolean(String.valueOf(field.get("required")));
            knownKeys.add(key);
            Object value = payload.get(key);
            boolean empty = value == null || (value instanceof String text && text.isBlank());
            if (requireRequiredFields && required && empty) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Required approval field is missing: " + key);
            }
            if (!empty) validateField(key, type, value, field.get("options"));
        }
        for (String key : payload.keySet()) {
            if (!knownKeys.contains(key) && !"createdFrom".equals(key)) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Unknown approval field: " + key);
            }
        }
    }

    Map<String, Object> informationBase(
            Map<String, Object> definition,
            boolean typedFormSchema,
            Map<String, Object> currentPayload) {
        if (!typedFormSchema) {
            return new LinkedHashMap<>(currentPayload);
        }
        return new ApprovalFormSchemaV2Evaluator().withoutComputedValues(
                new ApprovalFormSchemaV2Compiler().compile(definition), currentPayload);
    }

    void validateInformationPatch(Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) return;
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isBlank() || "createdFrom".equals(key)) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Information responses cannot modify system-owned fields.");
            }
            if (!(value instanceof String) && !(value instanceof Number)) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Information-response values must be non-null form scalars.");
            }
            if (value instanceof String text && text.length() > 10000) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Information-response field value is too large: " + key);
            }
        }
    }

    void validateTypedInformationPatch(Map<String, Object> patch) {
        if (patch == null) return;
        if (patch.containsKey("createdFrom")
                || patch.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Information responses cannot clear fields or change system-owned markers.");
        }
    }

    void validateField(String key, String type, Object value, Object rawOptions) {
        String text = String.valueOf(value).trim();
        try {
            switch (type) {
                case "NUMBER" -> new BigDecimal(text);
                case "DATE" -> LocalDate.parse(text);
                case "SELECT" -> {
                    if (!(rawOptions instanceof List<?> options)
                            || options.stream().map(String::valueOf).noneMatch(text::equals)) {
                        throw new BaseException(
                                ErrorCode.INVALID_INPUT_VALUE,
                                "Invalid option for approval field: " + key);
                    }
                }
                case "TEXT", "TEXTAREA", "USER" -> {
                    if (!(value instanceof String)) {
                        throw new BaseException(
                                ErrorCode.INVALID_INPUT_VALUE,
                                "Approval field must be text: " + key);
                    }
                }
                default -> throw new BaseException(ErrorCode.INVALID_STATE);
            }
        } catch (NumberFormatException | DateTimeParseException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Invalid value for approval field: " + key);
        }
    }

    String requiredString(Map<?, ?> value, String key) {
        Object raw = value.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new BaseException(ErrorCode.INVALID_STATE);
        }
        return String.valueOf(raw).trim();
    }

    private String normalized(String value, Set<String> allowed) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        return normalized;
    }
}
