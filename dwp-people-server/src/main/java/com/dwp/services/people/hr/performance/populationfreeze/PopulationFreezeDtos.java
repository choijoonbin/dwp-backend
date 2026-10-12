package com.dwp.services.people.hr.performance.populationfreeze;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class PopulationFreezeDtos {

    private PopulationFreezeDtos() {
    }

    public record FreezePopulationRequest(
            @NotNull UUID commandId,
            @NotNull UUID populationPreviewId,
            @NotNull UUID populationRuleId,
            @NotNull UUID workforceSnapshotId,
            @Positive long workforceSnapshotRevision,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String previewContentHash,
            @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record PopulationFreeze(
            UUID populationFreezeId,
            UUID cycleId,
            UUID cycleVersionId,
            UUID populationPreviewId,
            UUID populationRuleVersionId,
            UUID workforceSnapshotId,
            long workforceSnapshotRevision,
            long sourceCycleAggregateVersion,
            String state,
            int participantCount,
            int reviewerAssignmentCount,
            String contentHash,
            long aggregateVersion,
            Instant frozenAt) {
    }

    public record CommandReceipt(
            UUID receiptId,
            String commandType,
            String originatingAction,
            UUID aggregateId,
            @Min(0) long expectedAggregateVersion,
            Long appliedAggregateVersion,
            String state,
            UUID resultRef,
            String errorCode,
            Instant acceptedAt,
            Instant completedAt) {
    }

    public record FreezeCommandResult(
            PopulationFreeze freeze,
            CommandReceipt receipt) {
    }
}
