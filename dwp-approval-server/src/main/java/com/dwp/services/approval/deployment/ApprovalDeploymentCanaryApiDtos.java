package com.dwp.services.approval.deployment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentApiDtos.ExternalEvidence;
import static com.dwp.services.approval.deployment.ApprovalDeploymentCanaryModels.*;

final class ApprovalDeploymentCanaryApiDtos {
    private ApprovalDeploymentCanaryApiDtos() {
    }

    interface StrictInput {
        @JsonAnySetter
        default void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown canary input field: " + key);
        }
    }

    @Schema(name = "ApprovalDeploymentCanaryControl", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record ControlInput(
            @NotNull CanaryState state,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @Min(0) long expectedPromotionVersion,
            @Min(0) long expectedControlVersion) implements StrictInput {
        ControlCommand command() {
            return new ControlCommand(
                    state, reason, expectedPromotionVersion, expectedControlVersion);
        }
    }

    @Schema(name = "ApprovalDeploymentCanaryTelemetry", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record TelemetryInput(
            @NotNull UUID telemetryId,
            @Min(0) @Max(100) int stableWeight,
            @Min(0) @Max(100) int canaryWeight,
            @NotEmpty @Size(max = 64) Map<String, Object> metrics,
            @Min(0) long expectedPromotionVersion,
            @NotNull @Valid ExternalEvidence evidence) implements StrictInput {
        TelemetryCommand command() {
            return new TelemetryCommand(
                    telemetryId, stableWeight, canaryWeight, metrics,
                    expectedPromotionVersion, evidence.submission());
        }
    }
}
