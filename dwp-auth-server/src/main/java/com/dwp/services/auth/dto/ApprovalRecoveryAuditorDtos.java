package com.dwp.services.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class ApprovalRecoveryAuditorDtos {

    private ApprovalRecoveryAuditorDtos() {
    }

    @Schema(name = "ApprovalRecoveryAuditorResolveRequest")
    public record ResolveRequest(
            @NotNull @Positive Long tenantId,
            @NotBlank
            @Size(max = 160)
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]*$")
            String outboxId,
            @NotNull @Positive Long originatorUserId,
            @NotBlank
            @Size(max = 80)
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,79}$")
            String resourceSetKey) {
    }

    @Schema(name = "ApprovalRecoveryAuditorResolveResponse")
    public record ResolveResponse(
            @NotNull @Positive Long selectedUserId,
            @NotBlank String resourceSetKey,
            @NotBlank String assignmentRevision) {
    }
}
