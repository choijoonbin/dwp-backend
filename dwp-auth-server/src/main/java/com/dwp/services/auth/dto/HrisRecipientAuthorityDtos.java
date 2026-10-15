package com.dwp.services.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Privacy-minimized DTOs for the one HRIS notification recipient lookup. */
public final class HrisRecipientAuthorityDtos {

    public static final String PURPOSE =
            "HCM.EMPLOYEE_CHANGE_APPLIED_NOTIFICATION";
    public static final String CANONICAL_UUID =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    public static final String LOWERCASE_SHA256 = "^[0-9a-f]{64}$";

    private HrisRecipientAuthorityDtos() {
    }

    @Schema(name = "HrisRecipientAuthorityResolveRequest")
    public record ResolveRequest(
            @NotBlank
            @Size(min = 36, max = 36)
            @Pattern(regexp = CANONICAL_UUID)
            @Schema(
                    format = "uuid",
                    pattern = CANONICAL_UUID,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String requestId,
            @NotNull @Positive
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Long tenantId,
            @NotBlank
            @Size(min = 36, max = 36)
            @Pattern(regexp = CANONICAL_UUID)
            @Schema(
                    format = "uuid",
                    pattern = CANONICAL_UUID,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String personPublicId,
            @NotBlank
            @Pattern(regexp = "^HCM\\.EMPLOYEE_CHANGE_APPLIED_NOTIFICATION$")
            @Schema(
                    allowableValues = PURPOSE,
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String purpose) {
    }

    @Schema(name = "HrisRecipientAuthorityResolveResponse")
    public record ResolveResponse(
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID requestId,
            @Positive @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long tenantId,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            UUID personPublicId,
            @Positive @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            long userId,
            @NotBlank @Pattern(regexp = LOWERCASE_SHA256)
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String evidenceRevision,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant capturedAt,
            @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            Instant validUntil) {
    }

    public static boolean valid(ResolveRequest request) {
        return request != null
                && request.tenantId() != null
                && request.tenantId() > 0
                && canonical(request.requestId())
                && canonical(request.personPublicId())
                && PURPOSE.equals(request.purpose());
    }

    private static boolean canonical(String value) {
        if (value == null || !value.matches(CANONICAL_UUID)) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
