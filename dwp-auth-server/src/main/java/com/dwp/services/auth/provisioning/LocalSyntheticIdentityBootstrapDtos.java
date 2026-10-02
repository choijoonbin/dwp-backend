package com.dwp.services.auth.provisioning;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Wire contract for the opt-in, localhost-only synthetic identity boundary. */
public final class LocalSyntheticIdentityBootstrapDtos {

    private LocalSyntheticIdentityBootstrapDtos() {
    }

    public record ActivateRequest(
            @NotBlank
            @Pattern(regexp = "w1-[0-9]{8}t[0-9]{6}z-[0-9a-f]{8}")
            String runId,
            @NotNull UUID providerTenantId,
            @NotNull @Positive Long administratorUserId,
            @NotNull UUID personPublicId,
            @NotBlank @Email @Size(max = 255) String administratorEmail,
            @NotBlank @Size(min = 16, max = 128) String password,
            @NotNull @Size(max = 2) List<
                    @Pattern(regexp = "(?:HR_ADMIN|PAYROLL_ADMIN)") String> roleCodes) {
    }

    public record ActivateResponse(
            String runId,
            UUID providerTenantId,
            Long tenantId,
            Long administratorUserId,
            UUID personPublicId,
            String administratorEmail,
            String lifecycleState,
            List<String> roleCodes,
            String receiptSha256) {
    }
}
