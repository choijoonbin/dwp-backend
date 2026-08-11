package com.dwp.services.platform.workspace;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

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
            long completed) {
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
            OffsetDateTime updatedAt) {
    }

    public record UpdateWorkStatusRequest(
            @NotBlank
            @Pattern(regexp = "IN_PROGRESS|WAITING|COMPLETED")
            String status,
            @NotNull @Min(0) Long version) {
    }

    public record ActivityFeed(
            List<ActivityEvent> events,
            OffsetDateTime generatedAt) {
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
            String sourceRoute) {
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
            String health,
            boolean pinned,
            OffsetDateTime lastUsedAt,
            long launchCount,
            long version) {
    }

    public record PinAppRequest(
            @NotNull Boolean pinned,
            @NotNull @Min(0) Long version) {
    }

    public record AppLaunch(
            String appId,
            String launchMode,
            String launchTarget,
            OffsetDateTime launchedAt) {
    }
}
