package com.dwp.services.auth.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

public final class ProductSurfaceAuthorityDtos {

    private ProductSurfaceAuthorityDtos() {
    }

    public enum AccessMode {
        NORMAL,
        ELEVATED,
        PROVIDER_SUPPORT
    }

    public enum Decision {
        ALLOWED,
        APP_DENIED,
        SURFACE_DENIED,
        ROUTE_DENIED,
        SCOPE_SELECTION_REQUIRED,
        SCOPE_INVALID,
        EXPIRED,
        ACTIVATION_REQUIRED,
        STEP_UP_REQUIRED,
        OWNER_RECEIPT_AUTH_REQUIRED,
        SOD_CONFLICT,
        SUPPORT_SCOPE_DENIED,
        AUTHORITY_UNAVAILABLE
    }

    public enum AccessSource {
        ENTITLEMENT,
        RELATIONSHIP,
        MANAGEMENT,
        SUPPORT
    }

    public enum CapabilityAuthorityMode {
        PERMISSION,
        PERMISSION_AND_RELATIONSHIP,
        PERMISSION_OR_RELATIONSHIP
    }

    public enum PolicyAuthorityMode {
        ENTITLEMENT,
        RELATIONSHIP,
        ENTITLEMENT_AND_RELATIONSHIP,
        SUPPORT_SESSION
    }

    public enum ResponsibilityRequirement {
        REQUIRED,
        NOT_REQUIRED,
        LEGACY_OVERSIGHT
    }

    public enum ActivationState {
        ACTIVE,
        ELIGIBLE,
        EXPIRED,
        REVOKED
    }

    @Schema(name = "ProductSurfaceAuthorityEvaluateRequest")
    public record EvaluateRequest(
            @NotNull @Positive Long tenantId,
            @NotNull @Positive Long actorId,
            @NotBlank @Size(max = 100) String productKey,
            @NotBlank @Size(max = 160) String surfaceKey,
            @NotNull AccessMode activeAccessMode,
            @Size(max = 240) String routeContractKey,
            @Size(max = 500) String contextKey,
            @Size(max = 500) String contextScopeKey,
            @Size(max = 500) String supportSessionRef,
            @Size(max = 500) String supportRevision,
            List<@NotBlank @Size(max = 160) String> supportScopes,
            @Size(max = 200) String correlationId,
            @Size(max = 64) String idempotencyKeySha256,
            @Size(max = 8192) String opaqueStepUpChallengeRef,
            @Size(max = 16) String commandMethod,
            @Size(max = 500) String commandPath) {

        public EvaluateRequest {
            supportScopes = supportScopes == null ? List.of() : List.copyOf(supportScopes);
        }

        public boolean directRouteEvaluation() {
            return routeContractKey != null && !routeContractKey.isBlank();
        }

        public EvaluateRequest(
                Long tenantId, Long actorId, String productKey, String surfaceKey,
                AccessMode activeAccessMode, String routeContractKey, String contextKey,
                String contextScopeKey, String supportSessionRef, String supportRevision,
                List<String> supportScopes) {
            this(tenantId, actorId, productKey, surfaceKey, activeAccessMode,
                    routeContractKey, contextKey, contextScopeKey, supportSessionRef,
                    supportRevision, supportScopes, null, null, null, null, null);
        }

        public boolean ownerReceiptRequest() {
            return correlationId != null || idempotencyKeySha256 != null
                    || opaqueStepUpChallengeRef != null || commandMethod != null
                    || commandPath != null;
        }
    }

    @Schema(name = "ProductSurfaceAuthorityResult")
    public record AuthorityResult(
            @NotNull Decision decision,
            String reasonCode,
            String authRevision,
            String policyRevision,
            String tenantControlRevision,
            String contextKey,
            String productKey,
            String surfaceKey,
            String plane,
            @NotNull AccessMode accessMode,
            AccessSource accessSource,
            String appResourceKey,
            List<EffectiveGrant> effectiveGrants,
            List<EffectiveScope> scopes,
            String routeGrantRef,
            boolean effectiveReadOnly,
            boolean requiresProductEligibility,
            OffsetDateTime validUntil,
            OffsetDateTime expiredAt,
            String requiredAssurance,
            String requestPolicyRef,
            OffsetDateTime revalidateAt,
            String evidenceRef,
            String ownerReceiptAuthority) {

        public AuthorityResult {
            effectiveGrants = effectiveGrants == null ? List.of() : List.copyOf(effectiveGrants);
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }

        /**
         * Source-compatible boundary for older unit fixtures and adapters.
         *
         * <p>The missing tenant-control revision is intentionally not synthesized. Runtime
         * validation treats this compatibility path as untrusted and fails closed.
         */
        public AuthorityResult(
                Decision decision,
                String reasonCode,
                String authRevision,
                String policyRevision,
                String contextKey,
                String productKey,
                String surfaceKey,
                String plane,
                AccessMode accessMode,
                AccessSource accessSource,
                String appResourceKey,
                List<EffectiveGrant> effectiveGrants,
                List<EffectiveScope> scopes,
                String routeGrantRef,
                boolean effectiveReadOnly,
                boolean requiresProductEligibility,
                OffsetDateTime validUntil,
                OffsetDateTime expiredAt,
                String requiredAssurance,
                String requestPolicyRef,
                OffsetDateTime revalidateAt,
                String evidenceRef) {
            this(decision, reasonCode, authRevision, policyRevision, null, contextKey,
                    productKey, surfaceKey, plane, accessMode, accessSource, appResourceKey,
                    effectiveGrants, scopes, routeGrantRef, effectiveReadOnly,
                    requiresProductEligibility, validUntil, expiredAt, requiredAssurance,
                    requestPolicyRef, revalidateAt, evidenceRef, null);
        }

        public AuthorityResult(
                Decision decision, String reasonCode, String authRevision,
                String policyRevision, String tenantControlRevision, String contextKey,
                String productKey, String surfaceKey, String plane, AccessMode accessMode,
                AccessSource accessSource, String appResourceKey,
                List<EffectiveGrant> effectiveGrants, List<EffectiveScope> scopes,
                String routeGrantRef, boolean effectiveReadOnly,
                boolean requiresProductEligibility, OffsetDateTime validUntil,
                OffsetDateTime expiredAt, String requiredAssurance,
                String requestPolicyRef, OffsetDateTime revalidateAt, String evidenceRef) {
            this(decision, reasonCode, authRevision, policyRevision, tenantControlRevision,
                    contextKey, productKey, surfaceKey, plane, accessMode, accessSource,
                    appResourceKey, effectiveGrants, scopes, routeGrantRef, effectiveReadOnly,
                    requiresProductEligibility, validUntil, expiredAt, requiredAssurance,
                    requestPolicyRef, revalidateAt, evidenceRef, null);
        }

        public static AuthorityResult unavailable(EvaluateRequest request) {
            return new AuthorityResult(
                    Decision.AUTHORITY_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE",
                    null,
                    null,
                    null,
                    null,
                    request.productKey(),
                    request.surfaceKey(),
                    null,
                    request.activeAccessMode(),
                    null,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    true,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "grantKind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = CapabilityGrant.class, name = "CAPABILITY"),
            @JsonSubTypes.Type(value = PolicyGrant.class, name = "POLICY")
    })
    @Schema(name = "ProductSurfaceAuthorityEffectiveGrant")
    public sealed interface EffectiveGrant permits CapabilityGrant, PolicyGrant {
        List<String> scopeKeys();

        boolean readOnly();

        OffsetDateTime validUntil();
    }

    @Schema(name = "ProductSurfaceAuthorityCapabilityGrant")
    public record CapabilityGrant(
            String capabilityContractKey,
            String resolvedCapabilityCode,
            CapabilityAuthorityMode authorityMode,
            List<String> predicatePolicyKeys,
            ResponsibilityRequirement responsibilityRequirement,
            Responsibility responsibility,
            List<String> scopeKeys,
            boolean requiresProductEntitlement,
            boolean readOnly,
            ActivationState activationState,
            OffsetDateTime validUntil) implements EffectiveGrant {

        public CapabilityGrant {
            predicatePolicyKeys = predicatePolicyKeys == null
                    ? List.of()
                    : List.copyOf(predicatePolicyKeys);
            scopeKeys = scopeKeys == null ? List.of() : List.copyOf(scopeKeys);
        }
    }

    @Schema(name = "ProductSurfaceAuthorityPolicyGrant")
    public record PolicyGrant(
            String accessPolicyKey,
            String policyDecisionRef,
            PolicyAuthorityMode authorityMode,
            List<String> scopeKeys,
            boolean requiresProductEntitlement,
            boolean readOnly,
            OffsetDateTime validUntil) implements EffectiveGrant {

        public PolicyGrant {
            scopeKeys = scopeKeys == null ? List.of() : List.copyOf(scopeKeys);
        }
    }

    @Schema(name = "ProductSurfaceAuthorityResponsibility")
    public record Responsibility(String code, String resourceSetKey) {
    }

    @Schema(name = "ProductSurfaceAuthorityEffectiveScope")
    public record EffectiveScope(
            String key,
            String kind,
            String displayName,
            boolean isDefault,
            boolean readOnly,
            OffsetDateTime validUntil) {
    }
}
