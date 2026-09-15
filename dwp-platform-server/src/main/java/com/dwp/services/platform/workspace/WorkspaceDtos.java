package com.dwp.services.platform.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkspaceDtos {

    private WorkspaceDtos() {
    }

    public record WorkSummary(
            long total,
            long dueSoon,
            long inProgress,
            long waiting,
            long completed,
            long active,
            long overdue) {
        public WorkSummary(long total, long dueSoon, long inProgress, long waiting, long completed) {
            this(total, dueSoon, inProgress, waiting, completed, Math.max(0, total - completed), 0);
        }
    }

    public record WorkQueue(
            WorkSummary summary,
            List<WorkItem> items,
            OffsetDateTime generatedAt) {
    }

    public record WorkItem(
            UUID workItemId,
            String id,
            String title,
            String summary,
            String dataClassification,
            @Schema(allowableValues = {"APPROVAL", "TASK", "SERVICE", "REQUIRED", "REVIEW"})
            String type,
            String priority,
            String status,
            String owner,
            OffsetDateTime dueAt,
            String sourceSystem,
            String sourceReference,
            String sourceRoute,
            String reason,
            String recommendedNext,
            String latestActivity,
            long version,
            OffsetDateTime updatedAt,
            WorkCapabilities capabilities) {
    }

    public record WorkCapabilities(boolean canStart, boolean canComplete, boolean canWait) {
    }

    public record UpdateWorkStatusRequest(
            @NotBlank
            @Pattern(regexp = "IN_PROGRESS|WAITING|COMPLETED")
            String status,
            @NotNull @Min(0) Long version) {
    }

    public record WorkStatusChange(
            @NotNull UUID workItemId,
            @NotNull @Min(0) Long version) {
    }

    public record BatchUpdateWorkStatusRequest(
            @NotEmpty @Size(max = 50) List<@Valid WorkStatusChange> items,
            @NotBlank
            @Pattern(regexp = "IN_PROGRESS|WAITING|COMPLETED")
            String status) {
    }

    public record ActivityFeed(
            List<ActivityEvent> events,
            OffsetDateTime generatedAt,
            String nextCursor,
            boolean hasMore,
            ActivityCoverage coverage,
            OffsetDateTime snapshotAt,
            String startCursor) {
        public ActivityFeed(List<ActivityEvent> events, OffsetDateTime generatedAt) {
            this(events, generatedAt, null, false, new ActivityCoverage(
                    List.of("WORK_ITEM", "WORKSPACE_APP"), true, false,
                    List.of("SAMPLE", "QUARANTINED"), "WORKSPACE"), generatedAt, null);
        }
    }

    public record ActivityCoverage(
            List<String> supportedObjectTypes, boolean includesLegacy,
            boolean includesUsage, List<String> excludedProvenance, String sourceScope) {
    }

    public record ExecutionSummary(
            long total, long running, long needsInput, long policyBlocked,
            long completed, long failed, long cancelled, long unknown, OffsetDateTime generatedAt,
            ActivityCoverage coverage) {
    }

    public record ActivityEvent(
            UUID id,
            OffsetDateTime occurredAt,
            String actor,
            String actorName,
            String state,
            String title,
            String summary,
            String objectType,
            String objectLabel,
            String source,
            String tool,
            String auditId,
            Integer progress,
            String sourceRoute,
            String eventKind,
            String sourceEventId,
            String objectId,
            String sourceReference,
            Long resourceVersion,
            UUID idempotencyKey,
            String resultState,
            String executionId,
            Long executionVersion,
            Integer attempt,
            String workStatus,
            String correlationId,
            UUID auditRecordId,
            String dataProvenance,
            String sourceAccess,
            String auditAccess,
            String auditStatus,
            String resumeCursor) {
        public ActivityEvent(
                UUID id, OffsetDateTime occurredAt, String actor, String actorName,
                String state, String title, String summary, String objectType,
                String objectLabel, String source, String tool, String auditId,
                Integer progress, String sourceRoute) {
            this(id, occurredAt, actor, actorName, state, title, summary, objectType,
                    objectLabel, source, tool, auditId, progress, sourceRoute,
                    "CHANGE", id.toString(), null, null, null, null, null, null,
                    null, null, null, null, null, "LEGACY", "AVAILABLE", "RESTRICTED",
                    "LEGACY_UNLINKED", null);
        }
    }

    public record WorkspaceApp(
            String id,
            String name,
            String description,
            String owner,
            String category,
            String launchMode,
            String launchTarget,
            String iconKey,
            String resourceKey,
            String requiredPermissionCode,
            String badgeSourceKey,
            String health,
            boolean pinned,
            OffsetDateTime lastUsedAt,
            long launchCount,
            long version,
            String accessState,
            UUID accessRequestId,
            String accessRequestState,
            OffsetDateTime accessRequestUpdatedAt,
            Long accessRequestVersion) {
        public WorkspaceApp(
                String id, String name, String description, String owner, String category,
                String launchMode, String launchTarget, String iconKey, String resourceKey,
                String health, boolean pinned, OffsetDateTime lastUsedAt, long launchCount,
                long version, String accessState, UUID accessRequestId,
                String accessRequestState, OffsetDateTime accessRequestUpdatedAt,
                Long accessRequestVersion) {
            this(id, name, description, owner, category, launchMode, launchTarget,
                    iconKey, resourceKey, "VIEW", null, health, pinned, lastUsedAt,
                    launchCount, version, accessState, accessRequestId, accessRequestState,
                    accessRequestUpdatedAt, accessRequestVersion);
        }
    }

    public record PinAppRequest(
            @NotNull Boolean pinned,
            @NotNull @Min(0) Long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record AppLaunch(
            String appId,
            String launchMode,
            String launchTarget,
            OffsetDateTime launchedAt) {
    }

    public record CreateAppAccessRequest(
            @NotBlank @Size(min = 10, max = 1000) String justification,
            OffsetDateTime requestedUntil) {
    }

    public record AppAccessDecisionRequest(
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @NotBlank @Size(min = 10, max = 1000) String decisionNote,
            @NotNull @Min(0) Long version) {
    }

    public record AppAccessFulfillmentRequest(
            @NotBlank @Size(min = 10, max = 1000) String note,
            @NotNull @Min(0) Long version) {
    }

    public record AppAccessRequest(
            UUID requestId,
            Long userId,
            String appId,
            String appName,
            String resourceKey,
            String requestedPermissionCode,
            String justification,
            String state,
            OffsetDateTime requestedUntil,
            String decisionNote,
            OffsetDateTime decidedAt,
            Long decidedBy,
            String fulfillmentState,
            int fulfillmentAttempts,
            String fulfillmentNote,
            OffsetDateTime lastFulfillmentAt,
            String lastFulfillmentError,
            OffsetDateTime fulfilledAt,
            Long fulfilledBy,
            OffsetDateTime revokedAt,
            Long revokedBy,
            String revocationNote,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }
}
