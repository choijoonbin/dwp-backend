package com.dwp.services.time.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Closed application/problem+json body for Time command and receipt routes. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
        name = "HrisTimeProblemV1",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record HrisTimeProblemV1(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID correlationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean retryable,
        Long currentVersion,
        UUID receiptId,
        List<FieldErrorV1> fieldErrors) {

    public HrisTimeProblemV1 {
        requireText(type, "type");
        requireText(title, "title");
        HrisTimeProblemCode problemCode;
        try {
            problemCode = HrisTimeProblemCode.valueOf(requireText(code, "code"));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("code is not a sealed Time problem code", invalid);
        }
        if (status != problemCode.status().value() || retryable != problemCode.retryable()) {
            throw new IllegalArgumentException("problem status or retryable flag is inconsistent");
        }
        Objects.requireNonNull(correlationId, "correlationId");
        if (currentVersion != null && currentVersion < 0) {
            throw new IllegalArgumentException("currentVersion must not be negative");
        }
        fieldErrors = fieldErrors == null ? null : List.copyOf(fieldErrors);
    }

    @Schema(
            name = "HrisTimeProblemFieldErrorV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record FieldErrorV1(String field, String code) {
        public FieldErrorV1 {
            requireText(field, "field");
            requireText(code, "code");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-blank and trimmed");
        }
        return value;
    }
}
