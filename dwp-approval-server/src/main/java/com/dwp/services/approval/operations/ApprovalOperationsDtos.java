package com.dwp.services.approval.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ApprovalOperationsDtos {
    private ApprovalOperationsDtos() {
    }

    public record Reason(
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record DeliveryTarget(
            @NotNull UUID targetId,
            @Min(0) @Max(9_007_199_254_740_991L) long expectedVersion) {
    }

    public record DeliveryBatchCommand(
            @NotNull UUID operationId,
            @NotEmpty @Size(max = 50) List<@NotNull @Valid DeliveryTarget> items,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record TaskReassignment(
            @Min(1) long assigneeUserId,
            @NotNull UUID assigneePersonPublicId,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record TaskReassignmentTarget(
            @NotNull UUID targetId,
            @Min(0) @Max(9_007_199_254_740_991L) long expectedVersion,
            @Min(1) long assigneeUserId,
            @NotNull UUID assigneePersonPublicId) {
    }

    public record TaskReassignmentBatchCommand(
            @NotNull UUID operationId,
            @NotEmpty @Size(max = 50) List<@NotNull @Valid TaskReassignmentTarget> items,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record OperationReceipt(
            UUID operationId,
            String operation,
            String commandMode,
            long actorUserId,
            String managementResourceSetKey,
            int itemCount,
            Instant committedAt,
            List<ItemReceipt> items) {
    }

    public record ItemReceipt(
            UUID targetId,
            UUID requestId,
            long previousVersion,
            long committedVersion,
            String statusBefore,
            String statusAfter,
            Long assigneeUserId) {
    }
}
