package com.dwp.services.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ProductSurfaceStepUpDtos {

    private ProductSurfaceStepUpDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @Schema(
            name = "ProductSurfaceStepUpIssueRequest",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record IssueRequest(
            @NotBlank @Pattern(regexp = "POST|PUT|PATCH|DELETE") String commandMethod,
            @NotBlank @Size(max = 500) String commandPath,
            @Size(max = 500) String contextKey,
            @Size(max = 500) String contextScopeKey,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String targetType,
            @NotBlank @Size(max = 240) String targetId,
            @NotNull @Min(0) Long expectedObjectVersion,
            @NotBlank @Size(max = 200) String idempotencyKey,
            @NotNull JsonNode payload,
            @Size(max = 100) String providerKey,
            @Size(max = 500) String returnTo) {
    }

    @Schema(
            name = "ProductSurfaceStepUpIssueResponse",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record IssueResponse(
            String state,
            String challenge,
            String challengeId,
            String decisionRevision,
            Instant expiresAt) {
    }

    @Schema(
            name = "ProductSurfaceStepUpContinuationRequired",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ContinuationRequired(
            String state,
            Continuation continuation) {
    }

    @Schema(
            name = "ProductSurfaceStepUpContinuation",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Continuation(
            String type,
            String authorizationUrl,
            Instant expiresAt,
            String flowRef,
            List<String> providerKeys) {

        public Continuation {
            providerKeys = providerKeys == null ? List.of() : List.copyOf(providerKeys);
        }
    }
}
