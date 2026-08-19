package com.dwp.services.auth.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public final class SpaceEntitlementDtos {

    private SpaceEntitlementDtos() {
    }

    public record SyncRequest(
            @NotBlank @Pattern(regexp = "USER|GROUP") String principalType,
            @NotBlank @Size(max = 200) String principalRef,
            @NotBlank @Pattern(regexp = "SPACE\\.[A-Z0-9][A-Z0-9._:-]{1,249}")
            String resourceKey,
            @NotBlank @Size(max = 200) String resourceName,
            @NotBlank @Pattern(regexp = "VIEW|CREATE|UPDATE|APPROVE|MANAGE")
            String permissionCode,
            @NotBlank @Pattern(regexp = "GRANT|REVOKE") String action,
            OffsetDateTime validTo,
            @NotBlank @Size(min = 5, max = 1000) String justification,
            @NotNull Long actorId) {
    }

    public record SyncResult(
            String grantId,
            Long tenantId,
            String principalType,
            String principalRef,
            String resourceKey,
            String permissionCode,
            String sourceType,
            String sourceRef,
            String lifecycleState,
            OffsetDateTime validFrom,
            OffsetDateTime validTo,
            long version,
            boolean changed) {
    }

    public record PrincipalValidationRequest(
            @NotBlank @Pattern(regexp = "USER|GROUP") String principalType,
            @NotBlank @Size(max = 200) String principalRef,
            @NotNull Long actorId) {
    }

    public record PrincipalValidationResult(
            Long tenantId,
            String principalType,
            String suppliedRef,
            String canonicalRef,
            boolean active) {
    }
}
