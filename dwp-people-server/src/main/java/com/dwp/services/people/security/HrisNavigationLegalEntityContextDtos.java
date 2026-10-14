package com.dwp.services.people.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class HrisNavigationLegalEntityContextDtos {

    private HrisNavigationLegalEntityContextDtos() {
    }

    public enum ContextSurface { MANAGEMENT, OPERATIONS }

    public record VerifyRequest(
            @NotNull @Positive Long tenantId,
            @NotNull @Positive Long actorId,
            @NotNull ContextSurface contextSurface,
            @NotBlank @Size(max = 500) String contextScopeKey,
            @NotNull UUID legalEmployerPublicId) {
    }

    public record VerifyResult(
            boolean verified,
            long tenantId,
            long actorId,
            ContextSurface contextSurface,
            String contextScopeKey,
            UUID legalEmployerPublicId,
            String ownerEvidence) {
    }
}
