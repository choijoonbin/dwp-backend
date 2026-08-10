package com.dwp.services.auth.identity;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class WorkforceIdentityDtos {

    private WorkforceIdentityDtos() {
    }

    public record WorkforceIdentityEvent(
            @NotNull UUID eventId,
            @NotNull UUID providerTenantId,
            @NotNull UUID personPublicId,
            @NotBlank @Size(max = 255) String externalId,
            @NotBlank @Size(max = 200) String displayName,
            @Size(max = 120) String givenName,
            @Size(max = 120) String familyName,
            @Email @Size(max = 255) String workEmail,
            @Size(max = 160) String jobTitle,
            @Size(max = 35) String preferredLocale,
            @NotBlank @Pattern(regexp = "ACTIVE|LEAVE|TERMINATED|PENDING") String workerStatus,
            @Size(max = 255) String sourceVersion) {
    }

    public record SyncResult(
            UUID eventId,
            Long tenantId,
            Long userId,
            String lifecycleState,
            boolean replayed) {
    }
}
