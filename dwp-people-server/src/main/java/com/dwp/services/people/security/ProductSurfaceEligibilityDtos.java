package com.dwp.services.people.security;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

public final class ProductSurfaceEligibilityDtos {

    private ProductSurfaceEligibilityDtos() {
    }

    public enum AccessMode {
        NORMAL,
        ELEVATED,
        PROVIDER_SUPPORT
    }

    public enum Decision {
        ALLOWED,
        SURFACE_DENIED,
        SCOPE_INVALID,
        AUTHORITY_UNAVAILABLE
    }

    @Schema(name = "ProductSurfaceEligibilityEvaluateRequest")
    public record EvaluateRequest(
            @NotNull @Positive Long tenantId,
            @NotNull @Positive Long actorId,
            @NotBlank @Size(max = 100) String productKey,
            @NotBlank @Size(max = 160) String surfaceKey,
            @NotNull AccessMode activeAccessMode,
            @NotNull OffsetDateTime evaluatedAt,
            List<@Valid CandidateScope> candidateScopes,
            @Size(max = 500) String contextScopeKey) {

        public EvaluateRequest {
            candidateScopes = candidateScopes == null ? List.of() : List.copyOf(candidateScopes);
        }
    }

    @Schema(name = "ProductSurfaceEligibilityCandidateScope")
    public record CandidateScope(
            @NotBlank @Size(max = 500) String key,
            @NotBlank @Size(max = 80) String kind) {
    }

    @Schema(name = "ProductSurfaceEligibilityResult")
    public record EligibilityResult(
            @NotNull Decision decision,
            String reasonCode,
            String productRelationshipRevision,
            String targetPopulationRevision,
            List<EligibleScope> scopes,
            OffsetDateTime revalidateAt,
            String evidenceRef) {

        public EligibilityResult {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }

        public static EligibilityResult unavailable() {
            return new EligibilityResult(
                    Decision.AUTHORITY_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE",
                    null,
                    null,
                    List.of(),
                    null,
                    null);
        }
    }

    @Schema(name = "ProductSurfaceEligibleScope")
    public record EligibleScope(
            @NotBlank @Size(max = 500) String sourceScopeKey,
            String key,
            String kind,
            String displayName,
            boolean isDefault,
            boolean readOnly,
            OffsetDateTime validUntil) {
    }
}
