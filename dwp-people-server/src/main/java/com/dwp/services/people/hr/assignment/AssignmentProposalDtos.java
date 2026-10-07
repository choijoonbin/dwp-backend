package com.dwp.services.people.hr.assignment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AssignmentProposalDtos {

    private AssignmentProposalDtos() {
    }

    @Schema(name = "AssignmentProposalCreateRequest")
    public record CreateRequest(
            @NotNull UUID commandId,
            @NotNull UUID targetAssignmentId,
            @NotBlank @Pattern(regexp =
                    "TRANSFER|PROMOTION|DEMOTION|CHANGE_MANAGER|CHANGE_LOCATION|CORRECTION")
            String changeType,
            @NotNull LocalDate effectiveDate,
            @NotBlank @Size(max = 80) String reasonCode,
            @NotEmpty Map<@NotBlank @Size(max = 80) String, Object> proposedChanges,
            @NotNull @Min(0) Long expectedAssignmentVersion) {
    }

    @Schema(name = "AssignmentProposalVersionCommand")
    public record VersionCommand(
            @NotNull UUID commandId,
            @NotNull @Min(0) Long expectedVersion) {
    }

    @Schema(name = "AssignmentProposalCancelCommand")
    public record CancelCommand(
            @NotNull UUID commandId,
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(max = 1000) String reason) {
    }

    @Schema(name = "AssignmentProposalValidationFinding")
    public record ValidationFinding(
            String code,
            String field,
            String severity,
            String message) {
    }

    @Schema(name = "AssignmentProposal")
    public record Proposal(
            UUID proposalId,
            UUID targetAssignmentId,
            UUID targetWorkerId,
            UUID targetWorkRelationshipId,
            String assignmentKey,
            String workerNumber,
            String personDisplayName,
            String changeType,
            LocalDate effectiveDate,
            String reasonCode,
            Map<String, Object> proposedChanges,
            String lifecycleState,
            List<ValidationFinding> validationFindings,
            long targetWorkerVersion,
            long targetRelationshipVersion,
            long targetAssignmentVersion,
            long version,
            Instant validatedAt,
            Instant submittedAt,
            Instant cancelledAt,
            String cancellationReason,
            Instant createdAt,
            Instant updatedAt) {
    }

    @Schema(name = "AssignmentProposalCommandResult")
    public record CommandResult(
            UUID receiptId,
            boolean replayed,
            Proposal proposal) {
    }

    @Schema(name = "AssignmentProposalAssignmentDetail")
    public record AssignmentDetail(
            UUID assignmentId,
            UUID workerId,
            UUID workRelationshipId,
            String assignmentKey,
            String workerNumber,
            String personDisplayName,
            String assignmentStatus,
            boolean primaryAssignment,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            UUID organizationId,
            String organizationName,
            String jobProfileKey,
            String jobName,
            String locationKey,
            String locationName,
            UUID managerAssignmentId,
            String businessTitle,
            BigDecimal workerHours,
            BigDecimal fullTimeEquivalent,
            String changeReasonCode,
            long workerVersion,
            long relationshipVersion,
            long assignmentVersion) {
    }

    @Schema(name = "AssignmentProposalTimelineEntry")
    public record TimelineEntry(
            UUID assignmentId,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            int effectiveSequence,
            String assignmentStatus,
            String businessTitle,
            UUID organizationId,
            String jobProfileKey,
            String locationKey,
            UUID managerAssignmentId,
            String changeReasonCode,
            long version) {
    }
}
