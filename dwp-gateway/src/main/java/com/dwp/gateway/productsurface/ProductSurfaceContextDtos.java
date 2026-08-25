package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.OffsetDateTime;
import java.util.List;

public final class ProductSurfaceContextDtos {

    private ProductSurfaceContextDtos() {
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
        SOD_CONFLICT,
        SUPPORT_SCOPE_DENIED,
        AUTHORITY_UNAVAILABLE
    }

    public enum GovernedDecision {
        ALLOWED,
        ROUTE_DENIED,
        EXPIRED,
        STEP_UP_REQUIRED,
        SOD_CONFLICT,
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

    public record RequestContext(
            long tenantId,
            long actorId,
            AccessMode activeAccessMode,
            String supportSessionRef,
            String supportRevision,
            List<String> supportScopes,
            String correlationId,
            String traceParent,
            String traceState,
            String personPublicId,
            List<String> roles,
            List<String> permissions) {

        public RequestContext {
            supportScopes = supportScopes == null ? List.of() : List.copyOf(supportScopes);
            roles = roles == null ? List.of() : List.copyOf(roles);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }

        RequestContext(
                long tenantId,
                long actorId,
                AccessMode activeAccessMode,
                String supportSessionRef,
                String supportRevision,
                List<String> supportScopes,
                String correlationId,
                String traceParent,
                String traceState) {
            this(tenantId, actorId, activeAccessMode, supportSessionRef, supportRevision,
                    supportScopes, correlationId, traceParent, traceState, null, List.of(),
                    List.of());
        }
    }

    public record ProductCandidate(String productKey, String surfaceKey) {
    }

    public record SourceRevisions(
            String auth,
            String policy,
            String productRelationship,
            String targetPopulation,
            String support) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiResponse<T>(
            boolean success,
            T data,
            String errorCode,
            String message,
            String correlationId) {

        public static <T> ApiResponse<T> success(T data) {
            return new ApiResponse<>(true, data, null, null, null);
        }

        public static <T> ApiResponse<T> error(
                String code, String message, String correlationId) {
            return new ApiResponse<>(false, null, code, message, correlationId);
        }
    }

    public record ContextListData(
            String contractVersion,
            String decisionRevision,
            SourceRevisions sourceRevisions,
            AccessMode activeAccessMode,
            OffsetDateTime generatedAt,
            List<EffectiveContext> contexts,
            List<ProductRollout> rollouts) {

        public ContextListData {
            contexts = contexts == null ? List.of() : List.copyOf(contexts);
            rollouts = rollouts == null ? List.of() : List.copyOf(rollouts);
        }
    }

    public record ProductRollout(
            String productKey,
            String state,
            RolloutFlags flags,
            String cohort,
            String opaqueRevision,
            AuthorityStatus authorityStatus) {
    }

    public enum AuthorityStatus {
        NOT_EVALUATED,
        AVAILABLE,
        UNAVAILABLE
    }

    public record RolloutFlags(
            boolean contextShadow,
            boolean capabilityEnforcement,
            boolean surfaceUi) {
    }

    public record EffectiveContext(
            String contextKey,
            String productKey,
            String surfaceKey,
            String plane,
            AccessMode accessMode,
            AccessSource accessSource,
            String appResourceKey,
            List<EffectiveGrant> effectiveGrants,
            List<EffectiveScope> scopes,
            OffsetDateTime revalidateAt) {

        public EffectiveContext {
            effectiveGrants = effectiveGrants == null ? List.of() : List.copyOf(effectiveGrants);
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "grantKind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = CapabilityGrant.class, name = "CAPABILITY"),
            @JsonSubTypes.Type(value = PolicyGrant.class, name = "POLICY")
    })
    public sealed interface EffectiveGrant permits CapabilityGrant, PolicyGrant {
        List<String> scopeKeys();

        boolean readOnly();

        OffsetDateTime validUntil();
    }

    public record CapabilityGrant(
            String capabilityContractKey,
            String resolvedCapabilityCode,
            CapabilityAuthorityMode authorityMode,
            List<String> predicatePolicyKeys,
            String responsibilityRequirement,
            Responsibility responsibility,
            List<String> scopeKeys,
            boolean requiresProductEntitlement,
            boolean readOnly,
            String activationState,
            OffsetDateTime validUntil) implements EffectiveGrant {

        public CapabilityGrant {
            predicatePolicyKeys = predicatePolicyKeys == null
                    ? List.of()
                    : List.copyOf(predicatePolicyKeys);
            scopeKeys = scopeKeys == null ? List.of() : List.copyOf(scopeKeys);
        }
    }

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

    public record Responsibility(String code, String resourceSetKey) {
    }

    public record EffectiveScope(
            String key,
            String kind,
            String displayName,
            boolean isDefault,
            boolean readOnly,
            OffsetDateTime validUntil) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subject(String type, String productKey, String surfaceKey) {
    }

    public record ProductEvaluationRequest(
            Subject subject,
            String routeContractKey,
            String contextKey,
            String contextScopeKey) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProductEvaluationData(
            Decision decision,
            String reasonCode,
            String decisionRevision,
            EffectiveContext context,
            String routeGrantRef,
            EffectiveScope scope,
            Boolean effectiveReadOnly,
            OffsetDateTime validUntil,
            OffsetDateTime expiredAt,
            String requiredAssurance,
            String requestPolicyRef,
            OffsetDateTime revalidateAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GovernedTarget(String opaqueTargetRef, String expectedObjectVersion) {
    }

    public record GovernedEvaluationRequest(
            Subject subject,
            String navigationContextId,
            String routeContractKey,
            GovernedTarget target,
            String contextKey) {
    }

    public record GovernedRouteAccessContext(
            String contextKey,
            String navigationContextId,
            AccessSource accessSource,
            AccessMode accessMode,
            String routeGrantRef,
            boolean effectiveReadOnly,
            String decisionRevision,
            OffsetDateTime revalidateAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GovernedEvaluationData(
            GovernedDecision decision,
            String reasonCode,
            String decisionRevision,
            GovernedRouteAccessContext context,
            OffsetDateTime validUntil,
            OffsetDateTime expiredAt,
            String requiredAssurance,
            String requestPolicyRef) {
    }

    record AuthorityEvaluateRequest(
            Long tenantId,
            Long actorId,
            String productKey,
            String surfaceKey,
            AccessMode activeAccessMode,
            String routeContractKey,
            String contextKey,
            String contextScopeKey,
            String supportSessionRef,
            String supportRevision,
            List<String> supportScopes) {
    }

    record AuthorityResult(
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

        AuthorityResult {
            effectiveGrants = effectiveGrants == null ? List.of() : List.copyOf(effectiveGrants);
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    record GovernedAuthorityRequest(
            Long tenantId,
            Long actorId,
            String navigationContextId,
            String routeContractKey,
            AccessMode activeAccessMode,
            String opaqueTargetRef,
            String expectedObjectVersion,
            String contextKey) {
    }

    record GovernedAuthorityResult(
            GovernedDecision decision,
            String reasonCode,
            String authRevision,
            String policyRevision,
            String contextKey,
            String navigationContextId,
            AccessSource accessSource,
            AccessMode accessMode,
            String routeGrantRef,
            boolean effectiveReadOnly,
            OffsetDateTime validUntil,
            OffsetDateTime expiredAt,
            String requiredAssurance,
            String requestPolicyRef,
            OffsetDateTime revalidateAt,
            String evidenceRef) {
    }

    record EligibilityEvaluateRequest(
            Long tenantId,
            Long actorId,
            String productKey,
            String surfaceKey,
            AccessMode activeAccessMode,
            OffsetDateTime evaluatedAt,
            List<CandidateScope> candidateScopes,
            String contextScopeKey) {
    }

    record CandidateScope(String key, String kind) {
    }

    record EligibilityResult(
            Decision decision,
            String reasonCode,
            String productRelationshipRevision,
            String targetPopulationRevision,
            List<EligibleScope> scopes,
            OffsetDateTime revalidateAt,
            String evidenceRef) {

        EligibilityResult {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    record EligibleScope(
            String sourceScopeKey,
            String key,
            String kind,
            String displayName,
            boolean isDefault,
            boolean readOnly,
            OffsetDateTime validUntil) {
    }
}
