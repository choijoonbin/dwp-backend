package com.dwp.services.approval.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record ApprovalDelegationUpdateRequest(
        @NotNull Long delegateUserId,
        @NotBlank String scopeType,
        @Schema(description = "Immutable published workflow identity for WORKFLOW scope")
        UUID workflowId,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @NotBlank @Size(min = 10, max = 1000) String reason,
        @NotNull @Min(0) Long expectedVersion) {
}
