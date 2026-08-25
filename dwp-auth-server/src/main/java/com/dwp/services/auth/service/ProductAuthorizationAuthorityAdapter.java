package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.dwp.services.auth.service.ProductAuthorizationAuthoritySupport.*;

@Component
public class ProductAuthorizationAuthorityAdapter implements ProductSurfaceAuthorityPort {

    private static final String BUNDLE_KEY = "product-surfaces";
    private static final String PRODUCT_NOT_REGISTERED = "PRODUCT_NOT_REGISTERED";
    private static final String SURFACE_NOT_REGISTERED = "SURFACE_NOT_REGISTERED";
    private static final String ROUTE_NOT_REGISTERED = "ROUTE_NOT_REGISTERED";

    private final ProductAuthorizationContractRepository repository;
    private final ProductAuthorizationIdentityEvidenceService evidenceService;
    private final Clock clock;

    @Autowired
    public ProductAuthorizationAuthorityAdapter(
            ProductAuthorizationContractRepository repository,
            ProductAuthorizationIdentityEvidenceService evidenceService) {
        this(repository, evidenceService, Clock.systemUTC());
    }

    ProductAuthorizationAuthorityAdapter(
            ProductAuthorizationContractRepository repository,
            ProductAuthorizationIdentityEvidenceService evidenceService,
            Clock clock) {
        this.repository = repository;
        this.evidenceService = evidenceService;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductSurfaceAuthorityDtos.AuthorityResult evaluate(
            ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        ProductAuthorizationContractRepository.StoredBundle stored = repository
                .findActive(BUNDLE_KEY)
                .orElseThrow(() -> new IllegalStateException(
                        "No active product authorization bundle"));
        ProductAuthorizationContractDtos.BundleContract contract =
                repository.loadContract(stored);
        ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity =
                evidenceService.load(request.tenantId(), request.actorId());
        long pointerRevision = repository.findActivePointer(BUNDLE_KEY)
                .filter(pointer -> pointer.bundleId().equals(stored.bundleId()))
                .map(ProductAuthorizationContractRepository.ActivePointer::revision)
                .orElseThrow(() -> new IllegalStateException(
                        "Active product authorization pointer changed"));
        Registry registry = new Registry(contract);
        Evaluation evaluation = request.directRouteEvaluation()
                ? evaluateRoute(request, registry, identity)
                : evaluateEntry(request, registry, identity);
        String policyRevision = "policy-" + stored.version() + '-' + pointerRevision + '-'
                + stored.checksum();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime revalidateAt = earliest(evaluation.validUntil(), now.plusSeconds(60));
        String contextKey = contextKey(request, identity.revision(), policyRevision,
                evaluation.scopes());

        boolean materialized = evaluation.allowed()
                || evaluation.decision()
                == ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED;
        if (materialized && request.contextKey() != null
                && !request.contextKey().equals(contextKey)) {
            evaluation = Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID,
                    "SCOPE_CONTEXT_EXPIRED");
        }
        if (materialized && !evaluation.requiresProductEligibility()
                && request.contextScopeKey() != null
                && evaluation.scopes().stream()
                        .noneMatch(scope -> request.contextScopeKey().equals(scope.key()))) {
            evaluation = Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID,
                    "SCOPE_CONTEXT_EXPIRED");
        }
        return result(request, identity.revision(), policyRevision, contextKey,
                revalidateAt, evaluation);
    }

    private Evaluation evaluateEntry(
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
                            || surfaceRequiresPeopleEligibility(request, registry));
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

    private Evaluation evaluateRoute(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity) {
        ProductAuthorizationContractDtos.GovernedRoute route = registry.routesByKey()
                .get(request.routeContractKey());
        if (route == null || !"PRODUCT".equals(route.subject().type())
                || !request.productKey().equals(route.subject().productKey())
                || !request.surfaceKey().equals(route.subject().surfaceKey())) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED,
                    registry.hasProduct(request.productKey())
                            ? ROUTE_NOT_REGISTERED : PRODUCT_NOT_REGISTERED);
        }
        List<ProductAuthorizationContractDtos.AccessProfile> profiles = route.accessProfiles().stream()
                .filter(value -> value.activeAccessModes()
                        .contains(request.activeAccessMode().name()))
                .sorted(Comparator.comparingInt(
                        ProductAuthorizationContractDtos.AccessProfile::precedence).reversed())
                .toList();
        if (profiles.isEmpty()) {
            return Evaluation.denied(
                    request.activeAccessMode()
                            == ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT
                                    ? ProductSurfaceAuthorityDtos.Decision.SUPPORT_SCOPE_DENIED
                                    : ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED,
                    request.activeAccessMode()
                            == ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT
                                    ? "SUPPORT_SCOPE_REQUIRED"
                                    : "ROUTE_CAPABILITY_REQUIRED");
        }
        List<ProductSurfaceAuthorityDtos.EffectiveScope> directEntryScopes =
                singleSelfEntryPolicyScopes(request, registry, identity);
        Evaluation firstDenial = null;
        for (ProductAuthorizationContractDtos.AccessProfile profile : profiles) {
            Evaluation result = evaluateProfile(
                    request, registry, identity, profile, directEntryScopes);
            if (blockingProfileDenial(result)) return result.forRoute();
            if (result.allowed() || result.decision()
                    == ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED) {
                return routed(request, registry, identity, route, profile, result);
            }
            if (firstDenial == null) firstDenial = result;
        }
        return Objects.requireNonNull(firstDenial).forRoute();
    }

    private Evaluation evaluateProfile(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            ProductAuthorizationContractDtos.AccessProfile profile,
            List<ProductSurfaceAuthorityDtos.EffectiveScope> directEntryScopes) {
        ProductAuthorizationContractDtos.RequiredAccess access = profile.requiredAccess();
        return switch (access.type()) {
            case "CAPABILITY" -> evaluateCapability(
                    request,
                    registry,
                    identity,
                    registry.capabilitiesByKey().get(access.capabilityContractKey()),
                    profile.predicatePolicyKeys(),
                    profile.readOnly(),
                    directEntryScopes);
            case "POLICY" -> evaluatePolicy(
                    request,
                    registry,
                    identity,
                    registry.policiesByKey().get(access.accessPolicyKey()),
                    profile.readOnly());
            case "CAPABILITY_EXPRESSION" -> evaluateCapabilityExpression(
                    request,
                    registry,
                    identity,
                    access.mode(),
                    access.capabilityContractKeys(),
                    profile.predicatePolicyKeys(),
                    profile.readOnly(),
                    directEntryScopes);
            default -> throw new IllegalStateException(
                    "Unknown route required access type: " + access.type());
        };
    }

    private List<ProductSurfaceAuthorityDtos.EffectiveScope> singleSelfEntryPolicyScopes(
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

    private Evaluation routed(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.AccessProfile profile,
            Evaluation result) {
        boolean peopleEligibility = requiresPeopleEligibility(request, registry, route, profile);
        if (!result.allowed()) {
            Evaluation denial = result.forRoute();
            return denial.decision() == ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED
                    ? denial.withRoute(
                            "grant-" + digest(request.routeContractKey() + '\n'
                                    + identity.revision()).substring(0, 32),
                            peopleEligibility)
                    : denial;
        }
        return result.withRoute(
                "grant-" + digest(request.routeContractKey() + '\n'
                        + identity.revision()).substring(0, 32),
                result.requiresProductEligibility() || peopleEligibility);
    }

    /**
     * People is the current owner-service eligibility evaluator. Every normal/elevated route
     * delegated to the People PEP must intersect Auth's source authority with People-owned scope
     * evidence. Profile predicates and target bindings are authorization details, not reliable
     * ownership markers: overview routes can intentionally have neither while still sharing the
     * same People owner boundary.
     */
    private boolean requiresPeopleEligibility(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.AccessProfile profile) {
        if (request.activeAccessMode()
                == ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT) {
            return false;
        }
        return route.servicePepBindings() != null
                && route.servicePepBindings().stream()
                        .anyMatch(value -> "people".equals(value.serviceKey()));
    }

    private boolean surfaceRequiresPeopleEligibility(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry) {
        if (request.activeAccessMode()
                == ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT) {
            return false;
        }
        return registry.routesByKey().values().stream()
                .filter(route -> "PRODUCT".equals(route.subject().type()))
                .filter(route -> request.productKey().equals(route.subject().productKey()))
                .filter(route -> request.surfaceKey().equals(route.subject().surfaceKey()))
                .anyMatch(route -> route.accessProfiles().stream()
                        .filter(profile -> profile.activeAccessModes().contains(
                                request.activeAccessMode().name()))
                        .anyMatch(profile -> requiresPeopleEligibility(
                                request, registry, route, profile)));
    }

    private Evaluation evaluatePolicy(
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
                policyScopes(request, policy.scopeResolver(), null, false);
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

    private Evaluation evaluateCapabilityExpression(
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

    private Evaluation evaluateCapabilityExpression(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            String mode,
            List<String> keys,
            List<String> predicates,
            boolean readOnly,
            List<ProductSurfaceAuthorityDtos.EffectiveScope> entryPolicyScopes) {
        if (keys == null || keys.isEmpty() || !Set.of("ANY", "ALL").contains(mode)) {
            throw new IllegalStateException("Capability expression is empty or invalid");
        }
        List<Evaluation> allowed = new ArrayList<>();
        Evaluation typedDeny = null;
        for (String key : keys) {
            Evaluation result = evaluateCapability(
                    request, registry, identity, registry.capabilitiesByKey().get(key),
                    predicates, readOnly, entryPolicyScopes);
            if (result.allowed()) allowed.add(result);
            else if (typedDeny == null) typedDeny = result;
        }
        if (("ANY".equals(mode) && !allowed.isEmpty())
                || ("ALL".equals(mode) && allowed.size() == keys.size())) {
            return combine(allowed);
        }
        return typedDeny == null
                ? Evaluation.denied(
                        ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                        "SURFACE_CAPABILITY_REQUIRED")
                : typedDeny;
    }

    private Evaluation evaluateCapability(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            ProductAuthorizationContractDtos.CapabilityContract capability,
            List<String> predicates,
            boolean readOnly) {
        return evaluateCapability(
                request, registry, identity, capability, predicates, readOnly, List.of());
    }

    private Evaluation evaluateCapability(
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
        boolean activationEligible = capability.activationPolicy() != null
                && request.activeAccessMode() != ProductSurfaceAuthorityDtos.AccessMode.ELEVATED;
        OffsetDateTime validUntil = scoped
                ? ScopedAdminDutyPolicy.validUntil(duties, roles)
                : roles.stream().map(AppGovernanceDtos.ResourceRole::validTo)
                        .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        List<ProductSurfaceAuthorityDtos.EffectiveScope> scopes;
        if (scoped) {
            scopes = ScopedAdminDutyPolicy.scopes(request, duties, roles, readOnly);
        } else if (inheritsSelfEntryScope) {
            scopes = entryPolicyScopes;
        } else if (roles.isEmpty()) {
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
                    capability.activationPolicy(),
                    ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT,
                    grants, scopes, validUntil, capability.resourceKey());
        }
        return Evaluation.allowed(
                ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT,
                grants, scopes, readOnly, validUntil, false,
                capability.resourceKey());
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult result(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            String authRevision,
            String policyRevision,
            String contextKey,
            OffsetDateTime revalidateAt,
            Evaluation evaluation) {
        boolean materialized = evaluation.allowed()
                || evaluation.decision()
                == ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED;
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                evaluation.decision(),
                evaluation.reasonCode(),
                authRevision,
                policyRevision,
                materialized ? contextKey : null,
                request.productKey(),
                request.surfaceKey(),
                materialized ? plane(request.surfaceKey()) : null,
                request.activeAccessMode(),
                evaluation.accessSource(),
                evaluation.appResourceKey(),
                evaluation.grants(),
                evaluation.scopes(),
                evaluation.routeGrantRef(),
                evaluation.effectiveReadOnly(),
                evaluation.requiresProductEligibility(),
                evaluation.validUntil(),
                null,
                evaluation.requiredAssurance(),
                evaluation.requestPolicyRef(),
                materialized ? revalidateAt : null,
                "evidence-" + digest(authRevision + policyRevision).substring(0, 24));
    }

    private record EntryPolicyEvaluation(
            List<Evaluation> allowed,
            Evaluation typedDeny) {
    }

}
