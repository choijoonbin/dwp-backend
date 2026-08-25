package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class ProductAuthorizationAuthoritySupport {

    private static final Set<String> WORK_SURFACE_SUFFIXES = Set.of("work", "personal", "team");

    private ProductAuthorizationAuthoritySupport() {
    }

    static String contextKey(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            String authRevision,
            String policyRevision,
            List<ProductSurfaceAuthorityDtos.EffectiveScope> scopes) {
        String material = request.tenantId() + "\n" + request.actorId() + "\n"
                + request.productKey() + "\n" + request.surfaceKey() + "\n"
                + request.activeAccessMode() + "\n" + authRevision + "\n" + policyRevision
                + "\n" + scopes.stream().map(ProductSurfaceAuthorityDtos.EffectiveScope::key)
                        .sorted().collect(Collectors.joining("\n"));
        return "psc-" + digest(material);
    }

    static String scopeKey(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            String source,
            String kind) {
        return ProductSurfaceScopeKey.key(
                request.tenantId(), request.actorId(), request.productKey(), request.surfaceKey(),
                Objects.toString(source, ""), kind);
    }

    static ProductSurfaceAuthorityDtos.CapabilityAuthorityMode capabilityAuthority(String value) {
        return ProductSurfaceAuthorityDtos.CapabilityAuthorityMode.valueOf(value);
    }

    static ProductSurfaceAuthorityDtos.PolicyAuthorityMode policyAuthority(String value) {
        return ProductSurfaceAuthorityDtos.PolicyAuthorityMode.valueOf(
                Objects.toString(value, "ENTITLEMENT"));
    }

    static ProductSurfaceAuthorityDtos.ResponsibilityRequirement responsibilityRequirement(
            String value) {
        return ProductSurfaceAuthorityDtos.ResponsibilityRequirement.valueOf(value);
    }

    static String entitlementResource(JsonNode expression) {
        if (expression == null) return null;
        if ("LEAF".equals(expression.path("type").asText())) {
            String entitlement = expression.path("entitlement").asText();
            int separator = entitlement.indexOf(':');
            return separator > 0 ? entitlement.substring(0, separator) : entitlement;
        }
        return null;
    }

    static String resolverValue(String value, String prefix) {
        return value != null && value.startsWith(prefix) ? value.substring(prefix.length()) : null;
    }

    static boolean readOnlyCapability(
            ProductAuthorizationContractDtos.CapabilityContract capability) {
        return "VIEW".equals(capability.action()) || capability.contractKey().endsWith(".read");
    }

    static boolean hasActiveMutationGrant(
            List<ProductSurfaceAuthorityDtos.EffectiveGrant> grants,
            String scopeKey) {
        return grants.stream()
                .filter(grant -> grant.scopeKeys().contains(scopeKey))
                .filter(grant -> !grant.readOnly())
                .anyMatch(grant -> !(grant instanceof ProductSurfaceAuthorityDtos.CapabilityGrant capability)
                        || capability.activationState()
                        == ProductSurfaceAuthorityDtos.ActivationState.ACTIVE);
    }

    static boolean staticSodConflict(
            ProductAuthorizationContractDtos.CapabilityContract capability,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            List<AppGovernanceDtos.ResourceRole> matchingResponsibilities) {
        if (capability.sodPolicyId() == null) return false;
        boolean exactScope = hasExactResponsibility(capability, matchingResponsibilities);
        if (!exactScope) return false;
        return switch (capability.contractKey()) {
            case "approvals.design.publish" ->
                    identity.hasPermission("ADMIN.APPROVAL_DESIGN:CREATE")
                            || identity.hasPermission("ADMIN.APPROVAL_DESIGN:UPDATE");
            case "approvals.policy.publish" ->
                    identity.hasPermission("ADMIN.APPROVAL_POLICY:UPDATE");
            case "approvals.operations.execute" ->
                    identity.hasRole("AUDITOR");
            default -> false;
        };
    }

    private static boolean hasExactResponsibility(
            ProductAuthorizationContractDtos.CapabilityContract capability,
            List<AppGovernanceDtos.ResourceRole> responsibilities) {
        String resourceSetKey = resolverValue(
                capability.scopeResolver(), "APP_RESOURCE_SET:");
        String responsibilityCode = capability.requiredResponsibilityCode();
        if (resourceSetKey == null || responsibilityCode == null) return false;
        return responsibilities.stream().anyMatch(value ->
                responsibilityCode.equals(value.responsibilityCode())
                        && resourceSetKey.equals(value.resourceSetKey())
                        && capability.resourceKey().equals(value.resourceKey()));
    }

    static List<AppGovernanceDtos.ResourceRole> matchingResponsibilities(
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            ProductAuthorizationContractDtos.CapabilityContract capability,
            String productResourceKey) {
        String expectedSet = resolverValue(capability.scopeResolver(), "APP_RESOURCE_SET:");
        String expectedResponsibility = capability.requiredResponsibilityCode();
        if (capability.scopeResolver() != null && capability.scopeResolver().contains("@")) {
            String[] parts = capability.scopeResolver().split("@", 2);
            if (expectedResponsibility == null) expectedResponsibility = parts[0];
            expectedSet = parts[1];
        }
        if (expectedResponsibility == null
                && "REQUIRED".equals(capability.responsibilityRequirement())) {
            expectedResponsibility = "APP_CONFIG_ADMIN";
        }
        String requiredResponsibility = expectedResponsibility;
        String requiredSet = expectedSet;
        return identity.responsibilities().stream()
                .filter(value -> requiredSet == null
                        || requiredSet.equals(value.resourceSetKey()))
                .filter(value -> requiredResponsibility == null
                        || requiredResponsibility.equals(value.responsibilityCode()))
                .filter(value -> productResourceKey != null
                        && productResourceKey.equals(value.resourceKey()))
                .toList();
    }

    static String productResourceKey(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry) {
        List<Set<String>> resources = registry.policies().stream()
                .filter(policy -> request.productKey().equals(policy.productKey()))
                .map(ProductAuthorizationContractDtos.AccessPolicy::entitlementExpressionKey)
                .filter(Objects::nonNull)
                .map(registry.expressionsByKey()::get)
                .filter(Objects::nonNull)
                .map(expression -> entitlementResources(expression.expression()))
                .filter(values -> !values.isEmpty())
                .distinct()
                .toList();
        if (resources.isEmpty()) return null;
        Set<String> common = new LinkedHashSet<>(resources.getFirst());
        resources.subList(1, resources.size()).forEach(common::retainAll);
        return common.size() == 1 ? common.iterator().next() : null;
    }

    private static Set<String> entitlementResources(JsonNode expression) {
        if (expression == null || !expression.isObject()) return Set.of();
        if ("LEAF".equals(expression.path("type").asText())) {
            String resource = entitlementResource(expression);
            return resource != null && resource.startsWith("APP.")
                    ? Set.of(resource) : Set.of();
        }
        JsonNode children = expression.has("children")
                ? expression.get("children") : expression.get("operands");
        if (children == null || !children.isArray()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        children.forEach(child -> result.addAll(entitlementResources(child)));
        return Set.copyOf(result);
    }

    static List<ProductSurfaceAuthorityDtos.EffectiveScope> responsibilityScopes(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            List<AppGovernanceDtos.ResourceRole> roles,
            boolean readOnly) {
        Map<String, AppGovernanceDtos.ResourceRole> unique = new LinkedHashMap<>();
        for (AppGovernanceDtos.ResourceRole role : roles) {
            String key = scopeKey(request, role.resourceSetKey(), "RESOURCE_SET");
            unique.putIfAbsent(key, role);
        }
        boolean single = unique.size() == 1;
        return unique.entrySet().stream()
                .map(entry -> new ProductSurfaceAuthorityDtos.EffectiveScope(
                        entry.getKey(), "RESOURCE_SET", "Assigned scope", single,
                        readOnly, entry.getValue().validTo()))
                .toList();
    }

    static List<ProductSurfaceAuthorityDtos.EffectiveScope> policyScopes(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            String resolver,
            OffsetDateTime validUntil,
            boolean readOnly) {
        String kind = switch (Objects.toString(resolver, "SELF")) {
            case "SELF" -> "SELF";
            case "SUPPORT_SESSION" -> "SUPPORT_SESSION";
            default -> resolver != null && resolver.startsWith("APP_RESOURCE_SET:")
                    ? "RESOURCE_SET" : "TARGET_POPULATION";
        };
        String source = "SUPPORT_SESSION".equals(kind)
                ? request.supportSessionRef() : Objects.toString(resolver, "SELF");
        return List.of(new ProductSurfaceAuthorityDtos.EffectiveScope(
                scopeKey(request, source, kind), kind,
                "SELF".equals(kind) ? "Self" : "Assigned scope",
                true, readOnly, validUntil));
    }

    static String plane(String surfaceKey) {
        return isWork(surfaceKey) ? "work" : "management";
    }

    static boolean isWork(String surfaceKey) {
        int separator = surfaceKey.lastIndexOf('.');
        return separator > 0
                && WORK_SURFACE_SUFFIXES.contains(surfaceKey.substring(separator + 1));
    }

    static OffsetDateTime earliest(OffsetDateTime left, OffsetDateTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static String digest(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
