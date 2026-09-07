package com.dwp.services.platform.workhub.personal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class PersonalWorkDtos {
    private PersonalWorkDtos() { }

    public enum Status { OPEN, IN_PROGRESS, WAITING, COMPLETED, ARCHIVED }
    public enum Priority { LOW, NORMAL, HIGH, URGENT }

    @Schema(name = "PersonalWorkAccessContext")
    public record AccessContext(Long tenantId, Long userId, String permissions,
                                UUID personPublicId, String groupRefs, String locale) { }

    /** Identity only. A caller cannot supply a trusted title, URL, or source status. */
    @Schema(name = "PersonalWorkSourceReference")
    public record SourceReference(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String sourceSystem,
            @NotBlank @Size(max = 256) String sourceReference,
            @Size(max = 160) String obligationKey) { }

    /**
     * Adapters return current authorized metadata or an identity-only bookmark with all
     * metadata null. The latter is REFERENCE_ONLY, never proof of source-object access.
     */
    @Schema(name = "PersonalWorkResolvedSource")
    public record ResolvedSource(SourceReference reference, String title, String sourceRoute,
                                 String status, OffsetDateTime dueAt) { }

    /** availability: AVAILABLE, REFERENCE_ONLY, or UNAVAILABLE. */
    @Schema(name = "PersonalWorkSourceLink")
    public record SourceLink(String availability, SourceReference reference, String title,
                             String sourceRoute, String status, OffsetDateTime dueAt) { }

    @Schema(name = "PersonalWorkCreateTaskRequest")
    public record CreateTaskRequest(
            @NotBlank @Size(max = 500) String title,
            @Size(max = 10000) String description,
            @NotNull Priority priority,
            OffsetDateTime dueAt,
            @Valid SourceReference sourceReference) { }

    /** Full title/description/priority/dueAt replacement. Source changes are explicit. */
    @Schema(name = "PersonalWorkUpdateTaskRequest")
    public record UpdateTaskRequest(
            @NotBlank @Size(max = 500) String title,
            @Size(max = 10000) String description,
            @NotNull Priority priority,
            OffsetDateTime dueAt,
            @Valid SourceReference sourceReference,
            boolean clearSourceReference,
            @NotNull @Min(0) Long version) { }

    @Schema(name = "PersonalWorkStatusRequest")
    public record StatusRequest(@NotNull Status status, @NotNull @Min(0) Long version) { }
    @Schema(name = "PersonalWorkVersionRequest")
    public record VersionRequest(@NotNull @Min(0) Long version) { }

    @Schema(name = "PersonalWorkTask")
    public record Task(UUID taskId, String title, String description, Status status,
                       Priority priority, OffsetDateTime dueAt, SourceLink source,
                       long version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                       OffsetDateTime completedAt) { }

    @Schema(name = "PersonalWorkTaskPage")
    public record TaskPage(List<Task> items, int page, int size, long totalElements,
                           boolean hasMore) { }

    @Schema(name = "PersonalWorkTimelineEvent")
    public record TimelineEvent(UUID eventId, String action, Status status, long version,
                                OffsetDateTime occurredAt, UUID auditRecordId) { }

    @Schema(name = "PersonalWorkTimelinePage")
    public record TimelinePage(List<TimelineEvent> items, int page, int size,
                               long totalElements, boolean hasMore) { }

    /** Ordered selection is a personal plan, independent of task due dates and lifecycle. */
    @Schema(name = "PersonalWorkReplaceDayPlanRequest")
    public record ReplaceDayPlanRequest(
            @NotNull @Size(max = 100) List<@NotNull @Valid SourceReference> items,
            @NotNull @Min(0) Long version) { }

    @Schema(name = "PersonalWorkDayPlanItem")
    public record DayPlanItem(int position, SourceReference selectionReference, SourceLink source) { }
    @Schema(name = "PersonalWorkDayPlan")
    public record DayPlan(LocalDate date, long version, List<DayPlanItem> items,
                          OffsetDateTime updatedAt) { }
}
