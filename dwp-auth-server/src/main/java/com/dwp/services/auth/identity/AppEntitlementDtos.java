package com.dwp.services.auth.identity;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public final class AppEntitlementDtos {

    private AppEntitlementDtos() {
    }

    public record SyncRequest(
            @NotBlank @Pattern(regexp = "USER|GROUP") String principalType,
            @NotBlank @Size(max = 160) String principalRef,
            @NotBlank @Size(max = 255) String resourceKey,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_.-]{0,49}") String permissionCode,
            @NotBlank @Pattern(regexp = "GRANT|REVOKE") String action,
            @Future OffsetDateTime validTo,
            @NotNull @Positive Long actorId,
            @NotBlank @Size(min = 10, max = 1000) String justification) {
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
}
