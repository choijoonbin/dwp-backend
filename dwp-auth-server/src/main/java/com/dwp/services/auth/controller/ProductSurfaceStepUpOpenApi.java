package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.ProductSurfaceStepUpDtos;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** Closed OpenAPI envelopes for the governed product step-up issuer. */
public final class ProductSurfaceStepUpOpenApi {

    private ProductSurfaceStepUpOpenApi() {
    }

    @Schema(name = "ProductSurfaceStepUpIssuedResponse")
    public static final class IssuedResponse
            extends ApiResponse<ProductSurfaceStepUpDtos.IssueResponse> {
    }

    @Schema(name = "ProductSurfaceStepUpRequiredResponse")
    public static final class RequiredResponse
            extends ApiResponse<ProductSurfaceStepUpDtos.ContinuationRequired> {
    }

    @Schema(
            name = "ProductSurfaceStepUpAuthenticationError",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record AuthenticationError(
            String status,
            String message,
            @Schema(allowableValues = {"E2000", "E2003", "E2004", "E2005"})
            String errorCode,
            LocalDateTime timestamp,
            Boolean success,
            String correlationId) {
    }

    @Schema(
            name = "ProductSurfaceStepUpForbiddenError",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ForbiddenError(
            String status,
            String message,
            ProductSurfaceStepUpDtos.ContinuationRequired data,
            @Schema(allowableValues = {"STEP_UP_REQUIRED", "SOD_CONFLICT", "E2001"})
            String errorCode,
            LocalDateTime timestamp,
            Boolean success,
            String correlationId) {
    }

    @Schema(
            name = "ProductSurfaceStepUpConflictError",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ConflictError(
            String status,
            String message,
            @Schema(allowableValues = {
                    "STEP_UP_CHALLENGE_MISMATCH", "DECISION_REVISION_CONFLICT", "E1009"})
            String errorCode,
            LocalDateTime timestamp,
            Boolean success,
            String correlationId) {
    }

    @Schema(
            name = "ProductSurfaceStepUpValidationError",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ValidationError(
            String status,
            String message,
            @Schema(allowableValues = {"E1001", "E2006", "E4000", "E4002"})
            String errorCode,
            LocalDateTime timestamp,
            Boolean success,
            String correlationId) {
    }

    @Schema(
            name = "ProductSurfaceStepUpAuthorityUnavailableError",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record AuthorityUnavailableError(
            String status,
            String message,
            @Schema(allowableValues = {"AUTHORITY_RESOLUTION_UNAVAILABLE"})
            String errorCode,
            LocalDateTime timestamp,
            Boolean success,
            String correlationId) {
    }
}
