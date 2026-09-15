package com.dwp.services.approval.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApprovalDraftMigrationDtos {
    private ApprovalDraftMigrationDtos() {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Binding(
            UUID formId,
            UUID formVersionId,
            int formVersion,
            String formSchemaSha256,
            String formNameKo,
            String formNameEn,
            UUID workflowId,
            UUID workflowVersionId,
            int workflowVersion,
            String workflowDefinitionSha256,
            String workflowNameKo,
            String workflowNameEn) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Preview(
            UUID sourceRequestId,
            long sourceVersion,
            Binding source,
            Binding target,
            boolean migrationRequired,
            boolean routeCompatible,
            List<String> mappedFields,
            List<String> droppedFields,
            List<String> incompatibleFields,
            List<String> requiredFieldsToComplete,
            Instant evaluatedAt) {
        public Preview {
            mappedFields = List.copyOf(mappedFields);
            droppedFields = List.copyOf(droppedFields);
            incompatibleFields = List.copyOf(incompatibleFields);
            requiredFieldsToComplete = List.copyOf(requiredFieldsToComplete);
        }
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record MigrateRequest(
            @NotNull @Min(0) @Max(9_007_199_254_740_991L) Long expectedVersion,
            @NotNull UUID targetFormId,
            @NotNull UUID targetFormVersionId,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String targetFormSchemaSha256,
            @NotNull UUID targetWorkflowId,
            @NotNull UUID targetWorkflowVersionId,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String targetWorkflowDefinitionSha256,
            @NotBlank @Size(max = 2000) String reason) {
    }

    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Result(
            ApprovalDtos.RequestSummary draft,
            UUID sourceRequestId,
            long sourceVersion,
            Binding target,
            List<String> mappedFields,
            List<String> droppedFields,
            List<String> incompatibleFields,
            List<String> requiredFieldsToComplete) {
        public Result {
            mappedFields = List.copyOf(mappedFields);
            droppedFields = List.copyOf(droppedFields);
            incompatibleFields = List.copyOf(incompatibleFields);
            requiredFieldsToComplete = List.copyOf(requiredFieldsToComplete);
        }
    }
}
