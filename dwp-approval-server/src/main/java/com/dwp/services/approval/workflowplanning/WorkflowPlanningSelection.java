package com.dwp.services.approval.workflowplanning;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name="ApprovalWorkflowPlanningSelection",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record WorkflowPlanningSelection(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotNull UUID workflowId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotNull UUID workflowVersionId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @Min(0) long workflowRevision,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank @Pattern(regexp="[a-f0-9]{64}") String workflowSha256,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank @Pattern(regexp="[A-Z][A-Z0-9_]{2,79}") String managementResourceSetKey,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotNull @Valid PolicyPin policy,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotNull @Size(max=100) List<@Valid FormPin> forms,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,nullable=true) UUID selectedFormId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotNull Instant generatedAt) {
    public WorkflowPlanningSelection {forms=List.copyOf(forms);}
    @Schema(name="ApprovalWorkflowPlanningPolicyPin",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record PolicyPin(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @Min(1) long version,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank @Pattern(regexp="[a-f0-9]{64}") String sha256) { }
    @Schema(name="ApprovalWorkflowPlanningFormPin",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record FormPin(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotNull UUID formId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotNull UUID formVersionId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @Min(0) long formRevision,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @Min(1) int formVersion,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) @NotBlank @Pattern(regexp="[a-f0-9]{64}") String formSchemaSha256) { }
}
