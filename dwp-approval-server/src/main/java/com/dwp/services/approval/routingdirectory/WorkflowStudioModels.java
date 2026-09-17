package com.dwp.services.approval.routingdirectory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class WorkflowStudioModels {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private WorkflowStudioModels() { }

    record CanvasSave(
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedVersion,
            @NotNull @Size(max = 4) Map<String, Object> definition) { }

    record CanvasDryRun(
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedVersion) { }

    record RetireWorkflow(
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedVersion,
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long acknowledgedActiveReferences) { }

    record WorkflowVersion(UUID workflowVersionId, int versionNumber,
            Map<String, Object> definition, String definitionSha256,
            String lifecycleState, Instant createdAt, Long createdBy) {
        WorkflowVersion { definition = Map.copyOf(definition); }
    }

    record WorkflowStudio(UUID workflowId, String workflowKey, String lifecycleState,
            int currentVersion, long workflowRevision, int slaMinutes,
            WorkflowVersion current, Instant updatedAt, Long updatedBy,
            String publishEndpoint, String runtimeSimulationEndpoint) { }

    record WorkflowDiff(UUID workflowId, int fromVersion, int toVersion,
            String fromSha256, String toSha256, List<String> addedStages,
            List<String> removedStages, List<String> changedStages) {
        WorkflowDiff {
            addedStages = List.copyOf(addedStages);
            removedStages = List.copyOf(removedStages);
            changedStages = List.copyOf(changedStages);
        }
    }

    record DryRunResult(UUID workflowId, UUID workflowVersionId, long workflowRevision,
            String definitionSha256, int workflowSlaMinutes, List<String> topologicalStageKeys,
            boolean structurallyValid, boolean runtimeSimulationRequired,
            String runtimeSimulationEndpoint) {
        DryRunResult { topologicalStageKeys = List.copyOf(topologicalStageKeys); }
    }

    record Retirement(UUID workflowId, long workflowRevision, String lifecycleState,
            long activeReferences, Instant retiredAt) { }
}
