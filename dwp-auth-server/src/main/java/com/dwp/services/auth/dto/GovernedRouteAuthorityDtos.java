package com.dwp.services.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public final class GovernedRouteAuthorityDtos {

    private GovernedRouteAuthorityDtos() {
    }

    public enum Decision {
        ALLOWED,
        ROUTE_DENIED,
        EXPIRED,
        STEP_UP_REQUIRED,
        SOD_CONFLICT,
        AUTHORITY_UNAVAILABLE
    }

    @Schema(name = "GovernedRouteAuthorityEvaluateRequest")
    public record EvaluateRequest(
            @NotNull @Positive Long tenantId,
            @NotNull @Positive Long actorId,
            @NotBlank @Size(max = 160) String navigationContextId,
            @NotBlank @Size(max = 240) String routeContractKey,
            @NotNull ProductSurfaceAuthorityDtos.AccessMode activeAccessMode,
            @Size(max = 500) String opaqueTargetRef,
            @Size(max = 240) String expectedObjectVersion,
            @Size(max = 500) String contextKey) {
    }

    @Schema(name = "GovernedRouteAuthorityResult")
    public record AuthorityResult(
            @NotNull Decision decision,
            String reasonCode,
            String authRevision,
            String policyRevision,
            String contextKey,
            String navigationContextId,
            ProductSurfaceAuthorityDtos.AccessSource accessSource,
            ProductSurfaceAuthorityDtos.AccessMode accessMode,
            String routeGrantRef,
            boolean effectiveReadOnly,
            OffsetDateTime validUntil,
            OffsetDateTime expiredAt,
            String requiredAssurance,
            String requestPolicyRef,
            OffsetDateTime revalidateAt,
            String evidenceRef) {

        public static AuthorityResult unavailable(EvaluateRequest request) {
            return new AuthorityResult(
                    Decision.AUTHORITY_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE",
                    null,
                    null,
                    null,
                    request.navigationContextId(),
                    null,
                    request.activeAccessMode(),
                    null,
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }
}
