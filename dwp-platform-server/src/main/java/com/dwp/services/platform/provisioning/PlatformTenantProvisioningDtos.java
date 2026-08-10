package com.dwp.services.platform.provisioning;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class PlatformTenantProvisioningDtos {

    private PlatformTenantProvisioningDtos() {
    }

    public record ProvisionTenantRequest(
            @NotNull UUID providerTenantId,
            @NotNull Long tenantId,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,79}") String tenantKey,
            @NotBlank @Size(max = 240) String displayName,
            @NotBlank @Pattern(regexp = "[a-z0-9-]{2,40}") String dataRegion,
            @NotBlank @Pattern(regexp = "POOL|BRIDGE|SILO") String isolationModel,
            @NotBlank @Pattern(regexp = "[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*") String defaultLocale,
            @NotNull @Size(min = 1, max = 100) List<@NotBlank String> entitlementKeys) {
    }

    public record ProvisionTenantResponse(
            UUID providerTenantId,
            Long tenantId,
            String lifecycleState,
            int schemaVersion,
            String externalReference) {
    }

    public record UpdateLifecycleRequest(
            @NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED|RETIRED") String lifecycleState) {
    }

    public record ReplaceEntitlementsRequest(
            @NotNull @Size(min = 1, max = 100) List<@NotBlank String> entitlementKeys) {
    }
}
