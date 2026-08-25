package com.dwp.services.people.workforce;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Strict, PII-minimized organization design candidate projection. */
public final class WorkforceCandidateDtos {

    private WorkforceCandidateDtos() {
    }

    @Schema(name = "OrganizationCandidateEligibility")
    public enum Eligibility {
        ELIGIBLE,
        INELIGIBLE
    }

    @Schema(name = "OrganizationCandidate")
    public record OrganizationCandidate(
            @NotNull UUID publicId,
            @NotBlank String displayName,
            @NotBlank String organization,
            @Schema(nullable = true) String position,
            @NotNull Eligibility eligibility) {
    }
}
