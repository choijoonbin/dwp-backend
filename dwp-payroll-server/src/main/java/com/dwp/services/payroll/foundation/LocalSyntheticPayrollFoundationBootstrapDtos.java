package com.dwp.services.payroll.foundation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/** Wire contract for one disposable, run-bound PAY foundation fixture. */
final class LocalSyntheticPayrollFoundationBootstrapDtos {
    private LocalSyntheticPayrollFoundationBootstrapDtos() {
    }

    record BootstrapRequest(
            @NotBlank
            @Pattern(regexp = "w1-[0-9]{8}t[0-9]{6}z-[0-9a-f]{8}") String runId,
            @Positive long tenantId,
            @Positive long authorActorId,
            @NotNull UUID legalEntityId) {
    }

    record BootstrapResponse(
            String runId,
            long tenantId,
            long authorActorId,
            UUID legalEntityId,
            UUID configurationId,
            long version,
            String lifecycleState,
            String dependencyFreshness,
            UUID createCommandId,
            UUID simulateCommandId,
            String receiptSha256) {
    }
}
