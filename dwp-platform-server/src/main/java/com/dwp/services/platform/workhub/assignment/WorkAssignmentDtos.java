package com.dwp.services.platform.workhub.assignment;

import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Assignment is an independent Work aggregate; a personal task is never reassigned. */
public final class WorkAssignmentDtos {
    private WorkAssignmentDtos() { }

    public enum SourceSystem { MEETING_FOLLOWUP }
    public enum AssignmentState { PENDING, ACCEPTED, DECLINED }
    public enum WorkState { OPEN, IN_PROGRESS, WAITING, COMPLETED, CANCELLED }
    public enum Scope { ASSIGNED_TO_ME, ASSIGNED_BY_ME }
    public enum Action { ACCEPT, DECLINE, START, WAIT, COMPLETE, CANCEL, REASSIGN }
    public enum SourceAvailability { AVAILABLE, UNAVAILABLE, NOT_REQUESTED }

    @Schema(name = "WorkAssignmentSourceIdentity")
    public record SourceIdentity(@NotNull SourceSystem sourceSystem, @NotNull UUID meetingId,
                                 @NotNull UUID reportId, @NotNull UUID candidateId) { }

    /** Human-confirmed task terms are loaded from the owner; callers cannot submit AI text. */
    @Schema(name = "WorkAssignmentCreateRequest")
    public record CreateRequest(@NotNull @Valid SourceIdentity source,
                                @NotNull @Min(0) Long expectedSourceVersion) { }

    @Schema(name = "WorkAssignmentVersionCommand")
    public record VersionCommand(@NotNull @Min(0) Long version,
                                 @NotNull @Min(0) Long assignmentRevision,
                                 @Pattern(regexp = "[A-Z][A-Z0-9_]{2,47}") String reasonCode) { }

    @Schema(name = "WorkAssignmentReassignRequest")
    public record ReassignRequest(@NotNull @Positive Long assigneeUserId,
                                 @NotNull @Min(0) Long version,
                                 @NotNull @Min(0) Long assignmentRevision,
                                 @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,47}") String reasonCode) { }

    /** No Meeting title, transcript, citation text, or durable source-content URL is stored. */
    @Schema(name = "WorkAssignmentSourceView")
    public record SourceView(SourceAvailability availability, SourceIdentity reference,
                             Long sourceVersion, String sourceRoute) { }

    @Schema(name = "WorkAssignmentCapabilities")
    public record Capabilities(boolean canAccept, boolean canDecline, boolean canStart,
                               boolean canWait, boolean canComplete, boolean canReassign,
                               boolean canCancel) { }

    @Schema(name = "WorkAssignmentTask")
    public record Task(UUID assignmentId, long createdByUserId, long assignedByUserId,
                       long assigneeUserId, String title, String description, Priority priority,
                       OffsetDateTime dueAt, AssignmentState assignmentState, WorkState workState,
                       long assignmentRevision, long version, SourceView source,
                       Capabilities capabilities, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                       OffsetDateTime acceptedAt, OffsetDateTime completedAt) { }

    @Schema(name = "WorkAssignmentTaskPage")
    public record TaskPage(List<Task> items, int page, int size, long totalElements, boolean hasMore) { }

    /** The applied command version is stable; the assignment is always a currently authorized view. */
    @Schema(name = "WorkAssignmentCommandReceipt")
    public record CommandReceipt(UUID commandId, UUID assignmentId, String operation,
                                 long appliedVersion, long appliedAssignmentRevision,
                                 OffsetDateTime appliedAt, boolean replayed) { }

    @Schema(name = "WorkAssignmentMutationResult")
    public record MutationResult(Task assignment, CommandReceipt receipt) { }

    @Schema(name = "WorkAssignmentEvent")
    public record Event(UUID eventId, UUID assignmentId, String action, long actorUserId,
                        long assigneeUserId, AssignmentState assignmentState, WorkState workState,
                        long assignmentRevision, long version, String reasonCode,
                        OffsetDateTime occurredAt, UUID auditRecordId) { }

    @Schema(name = "WorkAssignmentEventPage")
    public record EventPage(List<Event> items, long nextAfterVersion, boolean hasMore) { }
}
