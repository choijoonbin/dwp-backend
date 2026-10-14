package com.dwp.services.people.hris.performance.review;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "HrisProblemDetailsV1",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
record PerformanceReviewProblemDetails(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID correlationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean retryable,
        Long currentVersion,
        UUID receiptId,
        List<FieldError> fieldErrors,
        String safeDetail) {

    @Schema(name = "HrisProblemFieldErrorV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record FieldError(String field, String code) {
    }
}
