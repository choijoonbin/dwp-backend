package com.dwp.services.provider.rollout;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/** Wire contract for one disposable, run-bound Provider rollout fixture. */
public final class LocalSyntheticProductSurfaceBootstrapDtos {
    private LocalSyntheticProductSurfaceBootstrapDtos() {
    }

    public record BootstrapRequest(
            @NotBlank
            @Pattern(regexp = "w1-[0-9]{8}t[0-9]{6}z-[0-9a-f]{8}")
            String runId,
            @NotNull UUID providerTenantId,
            @NotNull @Positive Long authTenantId,
            @NotBlank
            @Pattern(regexp = "^[a-z][a-z0-9-]{1,79}$")
            String tenantKey,
            @NotBlank @Size(max = 240) String displayName,
            @NotBlank @Pattern(regexp = "111|000") String hcmState) {
    }

    public record BootstrapResponse(
            String runId,
            UUID providerTenantId,
            Long authTenantId,
            String tenantKey,
            String hcmState,
            Map<String, String> rolloutRevisions,
            String receiptSha256) {
    }
}
