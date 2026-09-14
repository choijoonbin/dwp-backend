package com.dwp.services.approval.workflowplanning;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(name="ApprovalWorkflowPlanningResult",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record WorkflowPlanningResult(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues="ROLE_POOL_PREVIEW") String mode,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues="NOT_EVALUATED") String runtimeEligibility,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED,allowableValues="NOT_EVALUATED") String requesterExclusion,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String snapshotSha256,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String authorityRevision,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Instant expiresAt,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<Stage> stages) {
    public WorkflowPlanningResult {stages=List.copyOf(stages);}
    @Schema(name="ApprovalWorkflowPlanningStage",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Stage(
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String stepKey,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean selected,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<String> predecessors,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String roleCode,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String quorumMode,
            Integer quorumValue,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED,minimum="0",maximum="1000") int activeMemberCount,
            Integer indicativeThreshold,
            String poolWarning) {public Stage {predecessors=List.copyOf(predecessors);}}
}
