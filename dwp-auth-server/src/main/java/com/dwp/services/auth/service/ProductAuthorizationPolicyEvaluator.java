package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.dwp.services.auth.service.ProductAuthorizationAuthoritySupport.*;

/** Evaluates entry policies and capability grants against current identity evidence. */
final class ProductAuthorizationPolicyEvaluator {
    private final ProductAuthorizationDecisionSupport decisionSupport;
    private final String requiredAcr;

    ProductAuthorizationPolicyEvaluator(
            ProductAuthorizationDecisionSupport decisionSupport, String requiredAcr) {
        this.decisionSupport = decisionSupport;
        this.requiredAcr = requiredAcr;
    }

    Evaluation evaluateEntry(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity) {
        if (!registry.hasSurface(request.productKey(), request.surfaceKey())) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                    registry.hasProduct(request.productKey())
                            ? SURFACE_NOT_REGISTERED : PRODUCT_NOT_REGISTERED);
        }
        List<ProductAuthorizationContractDtos.AccessPolicy> policies = entryPolicies(
                request, registry);
        if (request.activeAccessMode()
                == ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT) {
            return evaluateSupportEntry(request, registry, identity, policies);
        }
        EntryPolicyEvaluation policyEvaluation = evaluateEntryPolicies(
                request, registry, identity, policies);
        List<Evaluation> allowed = new ArrayList<>(policyEvaluation.allowed());
        Evaluation typedDeny = policyEvaluation.typedDeny();
        Map<String, ProductSurfaceAuthorityDtos.EffectiveScope> entryPolicyScopesByKey =
                new LinkedHashMap<>();
        allowed.stream().flatMap(result -> result.scopes().stream())
                .forEach(scope -> entryPolicyScopesByKey.putIfAbsent(scope.key(), scope));
        List<ProductSurfaceAuthorityDtos.EffectiveScope> entryPolicyScopes =
                List.copyOf(entryPolicyScopesByKey.values());

        List<ProductAuthorizationContractDtos.CapabilityContract> surfaceCapabilities =
                registry.capabilities().stream()
                        .filter(value -> request.productKey().equals(value.productKey()))
                        .filter(value -> request.surfaceKey().equals(value.surfaceKey()))
                        .toList();
        for (ProductAuthorizationContractDtos.CapabilityContract capability : surfaceCapabilities) {
            Evaluation result = evaluateCapability(
                    request, registry, identity, capability, List.of(),
                    readOnlyCapability(capability), entryPolicyScopes);
            if (result.allowed()) allowed.add(result);
            else if (typedDeny == null) typedDeny = result;
        }
        if (!allowed.isEmpty()) {
            Evaluation combined = combine(allowed);
            return combined.withProductEligibility(
                    combined.requiresProductEligibility()
                            || ProductAuthorizationPeopleEligibility.requiredForSurface(
                                    request, registry));
        }
        if (typedDeny != null && typedDeny.decision()
                != ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED) {
            return typedDeny;
        }
        return Evaluation.denied(
                isWork(request.surfaceKey())
                        ? ProductSurfaceAuthorityDtos.Decision.APP_DENIED
                        : ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                isWork(request.surfaceKey())
                        ? "APP_ENTITLEMENT_REQUIRED"
                        : "SURFACE_CAPABILITY_REQUIRED");
    }

    private List<ProductAuthorizationContractDtos.AccessPolicy> entryPolicies(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry) {
        return registry.policies().stream()
                .filter(value -> request.productKey().equals(value.productKey()))
                .filter(value -> request.surfaceKey().equals(value.surfaceKey()))
                .filter(value -> value.surfaceEntryKeys() != null
                        && value.surfaceEntryKeys().contains(request.surfaceKey()))
                .sorted(Comparator
                        .comparing((ProductAuthorizationContractDtos.AccessPolicy value) ->
                                value.routeContractKeys() != null
                                        && value.routeContractKeys().isEmpty() ? 0 : 1)
                        .thenComparing(value ->
                                value.accessPolicyKey().contains("entry") ? 0 : 1)
                        .thenComparing(
                                ProductAuthorizationContractDtos.AccessPolicy::accessPolicyKey))
                .toList();
    }

    private EntryPolicyEvaluation evaluateEntryPolicies(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            List<ProductAuthorizationContractDtos.AccessPolicy> policies) {
        List<Evaluation> allowed = new ArrayList<>();
        Evaluation typedDeny = null;
        for (ProductAuthorizationContractDtos.AccessPolicy policy : policies) {
            Evaluation result = evaluatePolicy(request, registry, identity, policy, true);
            if (result.allowed()) {
                allowed.add(result);
                if (policy.accessPolicyKey().contains("entry")) break;
            } else if (typedDeny == null) {
                typedDeny = result;
            }
        }
        return new EntryPolicyEvaluation(List.copyOf(allowed), typedDeny);
    }

    private Evaluation evaluateSupportEntry(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            List<ProductAuthorizationContractDtos.AccessPolicy> policies) {
        List<ProductAuthorizationContractDtos.AccessPolicy> entries = policies.stream()
                .filter(value -> value.routeContractKeys() == null
                        || value.routeContractKeys().isEmpty())
                .toList();
        if (entries.isEmpty()) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SUPPORT_SCOPE_DENIED,
                    "SUPPORT_SCOPE_REQUIRED");
        }
        if (entries.size() != 1) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE,
                    "SUPPORT_ENTRY_POLICY_INVALID");
        }
        Evaluation evaluation = evaluatePolicy(
                request, registry, identity, entries.getFirst(), true);
        if (!evaluation.allowed()) return evaluation;
        boolean exactSupportGrant = evaluation.accessSource()
                == ProductSurfaceAuthorityDtos.AccessSource.SUPPORT
                && evaluation.effectiveReadOnly()
                && !evaluation.scopes().isEmpty()
                && evaluation.scopes().stream()
                        .allMatch(ProductSurfaceAuthorityDtos.EffectiveScope::readOnly)
                && evaluation.grants().size() == 1
                && evaluation.grants().getFirst()
                        instanceof ProductSurfaceAuthorityDtos.PolicyGrant grant
                && grant.authorityMode()
                        == ProductSurfaceAuthorityDtos.PolicyAuthorityMode.SUPPORT_SESSION
                && grant.readOnly();
        return exactSupportGrant ? evaluation : Evaluation.denied(
                ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE,
                "SUPPORT_ENTRY_POLICY_INVALID");
    }

    List<ProductSurfaceAuthorityDtos.EffectiveScope> singleSelfEntryPolicyScopes(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity) {
        Map<String, ProductSurfaceAuthorityDtos.EffectiveScope> byKey = new LinkedHashMap<>();
        evaluateEntryPolicies(request, registry, identity, entryPolicies(request, registry))
                .allowed().stream()
                .flatMap(evaluation -> evaluation.scopes().stream())
                .forEach(scope -> byKey.putIfAbsent(scope.key(), scope));
        if (byKey.size() != 1) return List.of();
        ProductSurfaceAuthorityDtos.EffectiveScope scope =
                byKey.values().iterator().next();
        return "SELF".equals(scope.kind()) ? List.of(scope) : List.of();
    }

    Evaluation evaluatePolicy(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            ProductAuthorizationContractDtos.AccessPolicy policy,
            boolean routeReadOnly) {
        if (policy == null) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED,
                    "POLICY_NOT_REGISTERED");
        }
        if ("MODE_BRANCH".equals(policy.evaluationType())) {
            ProductAuthorizationContractDtos.ModeBranch branch = policy.modeBranches().stream()
                    .filter(value -> request.activeAccessMode().name()
                            .equals(value.activeAccessMode()))
                    .findFirst()
                    .orElse(null);
            if (branch == null) {
                return Evaluation.denied(
                        request.activeAccessMode()
                                == ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT
                                        ? ProductSurfaceAuthorityDtos.Decision.SUPPORT_SCOPE_DENIED
                                        : ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                        request.activeAccessMode()
                                == ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT
                                        ? "SUPPORT_SCOPE_REQUIRED"
                                        : "SURFACE_CAPABILITY_REQUIRED");
            }
            if ("SUPPORT_SESSION".equals(branch.authorityMode())) {
                return evaluateSupportBranch(request, registry, policy, branch);
            }
            return evaluateCapabilityExpression(
                    request, registry, identity, branch.capabilityMode(),
                    branch.capabilityContractKeys(), List.of(), routeReadOnly);
        }
        if (request.activeAccessMode()
                == ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SUPPORT_SCOPE_DENIED,
                    "SUPPORT_SCOPE_REQUIRED");
        }
        ProductAuthorizationContractDtos.EntitlementExpression expression =
                registry.expressionsByKey().get(policy.entitlementExpressionKey());
        if (expression == null || !evaluateEntitlement(expression.expression(), identity)) {
            return Evaluation.denied(
                    policy.requiresProductEntitlement()
                            ? ProductSurfaceAuthorityDtos.Decision.APP_DENIED
                            : ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                    policy.requiresProductEntitlement()
                            ? "APP_ENTITLEMENT_REQUIRED"
                            : "SURFACE_CAPABILITY_REQUIRED");
        }
        List<ProductSurfaceAuthorityDtos.EffectiveScope> scopes =
                policyScopes(request, policy.scopeResolver(), null, routeReadOnly);
        ProductSurfaceAuthorityDtos.PolicyGrant grant =
                new ProductSurfaceAuthorityDtos.PolicyGrant(
                        policy.accessPolicyKey(),
                        "policy-" + digest(policy.accessPolicyKey() + identity.revision())
                                .substring(0, 24),
                        policyAuthority(policy.authorityMode()),
                        scopes.stream().map(ProductSurfaceAuthorityDtos.EffectiveScope::key)
                                .toList(),
                        policy.requiresProductEntitlement(),
                        routeReadOnly,
                        null);
        ProductSurfaceAuthorityDtos.AccessSource source =
                policy.relationshipResolver() == null
                        ? ProductSurfaceAuthorityDtos.AccessSource.ENTITLEMENT
                        : ProductSurfaceAuthorityDtos.AccessSource.RELATIONSHIP;
        String appResourceKey = entitlementResource(expression.expression());
        if (appResourceKey == null) {
            appResourceKey = productResourceKey(request, registry);
        }
        return Evaluation.allowed(
                source,
                List.of(grant), scopes, routeReadOnly, null,
                policy.relationshipResolver() != null,
                appResourceKey);
    }

    private Evaluation evaluateSupportBranch(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationContractDtos.AccessPolicy policy,
            ProductAuthorizationContractDtos.ModeBranch branch) {
        if (blank(request.supportSessionRef()) || blank(request.supportRevision())) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SUPPORT_SCOPE_DENIED,
                    "SUPPORT_SCOPE_REQUIRED");
        }
        List<String> required = branch.supportScopes() == null
                ? List.of()
                : branch.supportScopes();
        if (!required.isEmpty() && request.supportScopes().stream().noneMatch(required::contains)) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SUPPORT_SCOPE_DENIED,
                    "SUPPORT_SCOPE_REQUIRED");
        }
        List<ProductSurfaceAuthorityDtos.EffectiveScope> scopes = policyScopes(
                request, "SUPPORT_SESSION", null, true);
        ProductSurfaceAuthorityDtos.PolicyGrant grant =
                new ProductSurfaceAuthorityDtos.PolicyGrant(
                        policy.accessPolicyKey(),
                        "support-" + digest(request.supportSessionRef()
                                + request.supportRevision()).substring(0, 24),
                        ProductSurfaceAuthorityDtos.PolicyAuthorityMode.SUPPORT_SESSION,
                        scopes.stream().map(ProductSurfaceAuthorityDtos.EffectiveScope::key)
                                .toList(),
                        false,
                        true,
                        null);
        return Evaluation.allowed(
                ProductSurfaceAuthorityDtos.AccessSource.SUPPORT,
                List.of(grant), scopes, true, null, false,
                productResourceKey(request, registry));
    }

    Evaluation evaluateCapabilityExpression(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            String mode,
            List<String> keys,
            List<String> predicates,
            boolean readOnly) {
        return evaluateCapabilityExpression(
                request, registry, identity, mode, keys, predicates, readOnly, List.of());
    }

    Evaluation evaluateCapabilityExpression(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            String mode,
            List<String> keys,
            List<String> predicates,
            boolean readOnly,
            List<ProductSurfaceAuthorityDtos.EffectiveScope> entryPolicyScopes) {
        if (keys == null || keys.isEmpty() || !Set.of("ANY", "ALL").contains(mode)
                || keys.stream().anyMatch(key -> key == null || key.isBlank()
                || !registry.capabilitiesByKey().containsKey(key))
                || new java.util.HashSet<>(keys).size() != keys.size()) {
            throw new IllegalStateException("Capability expression is empty or invalid");
        }
        List<String> canonicalKeys = keys.stream().sorted().toList();
        List<Evaluation> allowed = new ArrayList<>();
        Evaluation challenge = null;
        Evaluation hardDeny = null;
        for (String key : canonicalKeys) {
            Evaluation result = evaluateCapability(
                    request, registry, identity, registry.capabilitiesByKey().get(key),
                    predicates, readOnly, entryPolicyScopes);
            if (result.allowed()) allowed.add(result);
            else if (result.decision()
                    == ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED) {
                if (challenge == null) challenge = result;
            } else if (hardDeny == null) {
                hardDeny = result;
            }
        }
        if ("ANY".equals(mode)) {
            if (!allowed.isEmpty()) return combine(allowed);
            if (challenge != null) return challenge;
            return hardDeny == null
                    ? Evaluation.denied(
                            ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                            "SURFACE_CAPABILITY_REQUIRED")
                    : hardDeny;
        }
        if (hardDeny != null) return hardDeny;
        if (challenge != null) return challenge;
        return allowed.size() == canonicalKeys.size()
                ? combine(allowed)
                : Evaluation.denied(
                        ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                        "SURFACE_CAPABILITY_REQUIRED");
    }

    Evaluation evaluateCapability(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            ProductAuthorizationContractDtos.CapabilityContract capability,
            List<String> predicates,
            boolean readOnly,
            List<ProductSurfaceAuthorityDtos.EffectiveScope> entryPolicyScopes) {
        if (capability == null) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED,
                    "CAPABILITY_NOT_REGISTERED");
        }
        if (decisionSupport.capabilityDisabled(
                request.tenantId(), capability.contractKey())) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                    "TENANT_CAPABILITY_DISABLED");
        }
        if (capability.requiresProductEntitlement()
                && !hasProductEntitlement(request, registry, identity)) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.APP_DENIED,
                    "APP_ENTITLEMENT_REQUIRED");
        }
        boolean scoped = ScopedAdminDutyPolicy.requiresScopedDuty(capability);
        boolean responsibilityOptional =
                "NOT_REQUIRED".equals(capability.responsibilityRequirement());
        boolean inheritsSelfEntryScope = responsibilityOptional
                && entryPolicyScopes.size() == 1
                && "SELF".equals(entryPolicyScopes.getFirst().kind());
        String productResourceKey = productResourceKey(request, registry);
        List<ScopedAdminDutyEvidenceService.EffectiveDuty> duties = scoped
                ? ScopedAdminDutyPolicy.matchingDuties(
                        identity, capability, productResourceKey)
                : List.of();
        if (!identity.hasPermission(capability.resolvedCapabilityCode())
                || (scoped && duties.isEmpty())) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                    "SURFACE_CAPABILITY_REQUIRED");
        }
        List<AppGovernanceDtos.ResourceRole> roles = scoped
                ? ScopedAdminDutyPolicy.matchingResponsibilities(
                        identity, capability, duties, productResourceKey)
                : responsibilityOptional
                        ? List.of()
                        : matchingResponsibilities(identity, capability, productResourceKey);
        if ("REQUIRED".equals(capability.responsibilityRequirement()) && roles.isEmpty()) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                    "RESPONSIBILITY_REQUIRED");
        }
        boolean sodConflict = scoped
                ? ScopedAdminDutyPolicy.staticSodConflict(duties, identity.scopedDuties())
                : staticSodConflict(capability, identity, roles);
        if (sodConflict) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SOD_CONFLICT,
                    "SOD_CONFLICT");
        }
        boolean originalAuthorityReceipt = request.directRouteEvaluation() && readOnly
                && ProductAuthorizationRecovery11ProjectionSchema.isOriginalAuthorityReceiptRoute(request.routeContractKey())
                && predicates.equals(List.of(ProductAuthorizationRecovery11ProjectionSchema.RECEIPT_PREDICATE));
        boolean nonPublishingReviewRejection = request.directRouteEvaluation()
                && "route.approvals.admin.form-publish-review-reject.action".equals(request.routeContractKey());
        boolean activationEligible = !originalAuthorityReceipt
                && !nonPublishingReviewRejection
                && capability.activationPolicy() != null
                && request.activeAccessMode() != ProductSurfaceAuthorityDtos.AccessMode.ELEVATED;
        OffsetDateTime validUntil = scoped
                ? ScopedAdminDutyPolicy.validUntil(duties, roles)
                : roles.stream().map(AppGovernanceDtos.ResourceRole::validTo)
                        .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        List<ProductSurfaceAuthorityDtos.EffectiveScope> scopes;
        if (scoped) {
            scopes = ScopedAdminDutyPolicy.scopes(request, duties, roles, readOnly);
        } else if (inheritsSelfEntryScope) {
            // Entry-policy evaluation intentionally exposes its own read-only posture. A direct
            // route, however, must project the route profile's posture onto the inherited SELF
            // scope in both directions. Otherwise a writable DATA/ACTION grant can be returned
            // with a contradictory read-only scope and downstream proof verification fails.
            scopes = request.directRouteEvaluation()
                    ? entryPolicyScopes.stream().map(scope ->
                            new ProductSurfaceAuthorityDtos.EffectiveScope(
                                    scope.key(), scope.kind(), scope.displayName(), scope.isDefault(),
                                    readOnly, scope.validUntil())).toList()
                    : entryPolicyScopes;
        } else if (roles.isEmpty()
                || ProductAuthorizationAuthoritySupport
                .usesResponsibilityBoundPopulationResolver(capability)) {
            // Specialist duty assignment and owner population are independent evidence. The
            // duty remains sealed on the grant while the owner derives the concrete population.
            scopes = policyScopes(request, capability.scopeResolver(), validUntil, readOnly);
        } else {
            scopes = responsibilityScopes(request, roles, readOnly);
        }
        List<ProductSurfaceAuthorityDtos.EffectiveGrant> grants = capabilityGrants(
                request, capability, predicates, roles, scopes, readOnly, activationEligible);
        if (activationEligible && request.directRouteEvaluation()) {
            return Evaluation.challenge(
                    ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED,
                    "STEP_UP_REQUIRED",
                    capability.activationPolicy(), requiredAcr,
                    ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT,
                    grants, scopes, readOnly, validUntil, capability.resourceKey());
        }
        return Evaluation.allowed(
                ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT,
                grants, scopes, readOnly, validUntil, false,
                capability.resourceKey());
    }

    Evaluation evaluateHcmAuditCapability(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            ProductAuthorizationContractDtos.CapabilityContract capability,
            List<String> predicates) {
        if (decisionSupport.capabilityDisabled(request.tenantId(), capability.contractKey())) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                    "TENANT_CAPABILITY_DISABLED");
        }
        HcmAuditAuthorityPolicy.AuthorityEvidence evidence = hcmAuditAuthority(identity);
        if (evidence.outcome() != HcmAuditAuthorityPolicy.Outcome.ALLOWED) {
            return switch (evidence.outcome()) {
                case APP_ENTITLEMENT_REQUIRED -> Evaluation.denied(
                        ProductSurfaceAuthorityDtos.Decision.APP_DENIED,
                        "APP_ENTITLEMENT_REQUIRED");
                case CAPABILITY_REQUIRED -> Evaluation.denied(
                        ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                        "SURFACE_CAPABILITY_REQUIRED");
                case RESPONSIBILITY_REQUIRED -> Evaluation.denied(
                        ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                        "RESPONSIBILITY_REQUIRED");
                case SOD_CONFLICT -> Evaluation.denied(
                        ProductSurfaceAuthorityDtos.Decision.SOD_CONFLICT,
                        "SOD_CONFLICT");
                case ALLOWED -> throw new IllegalStateException("Unreachable audit authority state");
            };
        }
        AppGovernanceDtos.ResourceRole responsibility = evidence.responsibility();
        List<ProductSurfaceAuthorityDtos.EffectiveScope> scopes = responsibilityScopes(
                request, List.of(responsibility), true);
        ProductSurfaceAuthorityDtos.CapabilityGrant grant =
                new ProductSurfaceAuthorityDtos.CapabilityGrant(
                        capability.contractKey(), capability.resolvedCapabilityCode(),
                        capabilityAuthority(capability.authorityMode()), predicates,
                        ProductSurfaceAuthorityDtos.ResponsibilityRequirement.REQUIRED,
                        new ProductSurfaceAuthorityDtos.Responsibility(
                                HcmAuditAuthorityPolicy.RESPONSIBILITY,
                                HcmAuditAuthorityPolicy.RESOURCE_SET),
                        scopes.stream().map(ProductSurfaceAuthorityDtos.EffectiveScope::key)
                                .toList(),
                        true,
                        true,
                        request.activeAccessMode() == ProductSurfaceAuthorityDtos.AccessMode.NORMAL
                                ? ProductSurfaceAuthorityDtos.ActivationState.ELIGIBLE
                                : ProductSurfaceAuthorityDtos.ActivationState.ACTIVE,
                        responsibility.validTo());
        if (request.activeAccessMode() == ProductSurfaceAuthorityDtos.AccessMode.NORMAL) {
            return Evaluation.challenge(
                    ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED,
                    "STEP_UP_REQUIRED", capability.activationPolicy(), requiredAcr,
                    ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT,
                    List.of(grant), scopes, true, responsibility.validTo(),
                    HcmAuditAuthorityPolicy.PRODUCT_RESOURCE);
        }
        if (request.activeAccessMode() != ProductSurfaceAuthorityDtos.AccessMode.ELEVATED) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED,
                    "ROUTE_CAPABILITY_REQUIRED");
        }
        return Evaluation.allowed(
                ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT,
                List.of(grant), scopes, true, responsibility.validTo(), false,
                HcmAuditAuthorityPolicy.PRODUCT_RESOURCE);
    }

    private record EntryPolicyEvaluation(List<Evaluation> allowed, Evaluation typedDeny) {}
}
