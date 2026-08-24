package com.dwp.services.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProductAuthorizationContractDtos {

    private ProductAuthorizationContractDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BundleContract(
            int schemaVersion,
            String bundleKey,
            long version,
            String bundleStatus,
            String owner,
            String checksumAlgorithm,
            String checksum,
            List<CapabilityContract> capabilities,
            List<AccessPolicy> accessPolicies,
            List<EntitlementExpression> entitlementExpressions,
            List<PredicatePolicy> predicatePolicies,
            List<GovernedRoute> routes,
            List<AuthorityEndpoint> authorityEndpoints) {

        public BundleContract(
                int schemaVersion,
                String bundleKey,
                long version,
                String bundleStatus,
                String owner,
                String checksumAlgorithm,
                String checksum,
                List<CapabilityContract> capabilities,
                List<AccessPolicy> accessPolicies,
                List<EntitlementExpression> entitlementExpressions,
                List<PredicatePolicy> predicatePolicies,
                List<GovernedRoute> routes) {
            this(schemaVersion, bundleKey, version, bundleStatus, owner, checksumAlgorithm,
                    checksum, capabilities, accessPolicies, entitlementExpressions,
                    predicatePolicies, routes, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SeedIndex(
            int schemaVersion,
            String bundleKey,
            long latestVersion,
            String latestChecksum,
            String latestArtifact,
            String latestAuthSeedArtifact,
            List<SeedIndexEntry> versions,
            String indexChecksumAlgorithm,
            String indexChecksum) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SeedIndexEntry(
            long version,
            String bundleStatus,
            String checksum,
            String artifact,
            String authSeedArtifact,
            Map<String, Integer> counts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CapabilityContract(
            String contractKey,
            String resolvedCapabilityCode,
            int mappingVersion,
            String productKey,
            String surfaceKey,
            List<String> routeContractKeys,
            String resourceKey,
            String action,
            String authorityMode,
            String responsibilityRequirement,
            String requiredResponsibilityCode,
            String scopeResolver,
            String riskTier,
            String activationPolicy,
            String sodPolicyId,
            boolean requiresProductEntitlement,
            String owner,
            OffsetDateTime sunsetAt,
            String legacySource,
            int policyVersion,
            String lifecycleState) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AccessPolicy(
            String accessPolicyKey,
            String navigationContextId,
            String productKey,
            String surfaceKey,
            List<String> surfaceEntryKeys,
            String evaluationType,
            String authorityMode,
            String entitlementExpressionKey,
            boolean requiresProductEntitlement,
            String relationshipResolver,
            String scopeResolver,
            List<String> supportScopes,
            List<ModeBranch> modeBranches,
            List<String> routeContractKeys,
            String owner,
            int policyVersion,
            String lifecycleState) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ModeBranch(
            String activeAccessMode,
            String resultGrantKind,
            String capabilityMode,
            List<String> capabilityContractKeys,
            String responsibilityRequirement,
            String authorityMode,
            List<String> supportScopes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EntitlementExpression(
            String expressionKey,
            JsonNode expression,
            String owner,
            int policyVersion,
            String lifecycleState) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PredicatePolicy(
            String predicatePolicyKey,
            String ownerServiceKey,
            List<String> targetBindingKinds,
            String inputEvidenceSchemaKey,
            String parameterSchemaKey,
            JsonNode parameterSchema,
            List<String> routeContractKeys,
            String owner,
            int policyVersion,
            String lifecycleState) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record GovernedRoute(
            String routeContractKey,
            String navigationContextId,
            RouteSubject subject,
            String routeKind,
            Boolean sideEffectFree,
            String uiRouteId,
            String uiRoutePattern,
            List<AccessProfile> accessProfiles,
            List<GatewayBinding> gatewayApiBindings,
            List<ServicePepBinding> servicePepBindings,
            String authorizationEquivalenceKey,
            List<StepUpCommandBinding> stepUpCommandBindings,
            List<String> consumedApiRouteContractKeys,
            String owner,
            int policyVersion,
            String lifecycleState) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RouteSubject(String type, String productKey, String surfaceKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AccessProfile(
            String profileKey,
            int precedence,
            List<String> activeAccessModes,
            RequiredAccess requiredAccess,
            List<String> targetBindingKinds,
            List<String> predicatePolicyKeys,
            List<ResponseProjectionBinding> responseProjectionBindings,
            boolean readOnly) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RequiredAccess(
            String type,
            String capabilityContractKey,
            String accessPolicyKey,
            String mode,
            List<String> capabilityContractKeys) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record GatewayBinding(
            String bindingKey,
            String method,
            String path,
            Map<String, PathParameterConstraint> pathParameterConstraints,
            Map<String, QueryParameterConstraint> queryParameterConstraints) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ServicePepBinding(
            String bindingKey,
            String serviceKey,
            String method,
            String path,
            Map<String, PathParameterConstraint> pathParameterConstraints,
            Map<String, QueryParameterConstraint> queryParameterConstraints) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PathParameterConstraint(String kind, String value, List<String> values) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record QueryParameterConstraint(String kind, String value, List<String> values) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record StepUpCommandBinding(
            String bindingKey,
            String ownerServiceKey,
            String audience,
            String targetType,
            String targetIdPathParameter,
            List<String> targetIdBodyFields,
            String expectedObjectVersionSource,
            String expectedObjectVersionName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AuthorityEndpoint(
            String endpointKey,
            String method,
            String publicPath,
            String serviceKey,
            String servicePath,
            boolean requiresAuthentication,
            boolean requiresCsrf,
            String expectedDecisionRevisionHeader) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ResponseProjectionBinding(
            String apiBindingKey,
            String projectionPolicyKey,
            String responseSchemaKey,
            Integer schemaVersion,
            String openApiSchemaSha256,
            Boolean additionalProperties) {
    }

    public record BundleView(
            UUID bundleId,
            String bundleKey,
            long version,
            String bundleStatus,
            long activeRevision,
            String checksum,
            String owner,
            String approvedBy,
            OffsetDateTime approvedAt,
            OffsetDateTime activatedAt,
            BundleContract contract) {
    }

    public record ActivationResult(
            String bundleKey,
            long version,
            String operation,
            long revision,
            String checksum) {
    }
}
