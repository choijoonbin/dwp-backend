package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.dwp.services.auth.tenantcapabilityoverride.TenantCapabilityOverrideReader;
import com.dwp.services.auth.tenantfeatureactivation.TenantFeatureActivationReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
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
    private static final Set<String> PERFORMANCE_REVIEW_DUAL_PROFILE_ROUTES = Set.of(
            "route.hcm.performance.assigned-review.data",
            "route.hcm.performance.assigned-review-draft-update.action",
            "route.hcm.performance.assigned-review-submit.action");
    private static final Set<String> HCM_EMPLOYEE_CHANGE_MULTI_PROFILE_ROUTES = Set.of(
            "route.hcm.operations.employee-change-request-create.action",
            "route.hcm.operations.employee-change-request-submit.action");
    private static final String HCM_CERTIFICATE_SHARED_ACTION_ROUTE =
            "route.hcm.operations.certificate-issue.action";
    private static final String HCM_CERTIFICATE_ROUTE_STEP_UP_POLICY =
            "STEPUP-MGMT-HIGH-V1";
    private static final Set<String> GENERIC_SESSION_PROFILE_ALTERNATIVE_ROUTES = Set.of(
            "route.hcm.configuration.configuration-version.data",
            "route.hcm.configuration.configurations.data",
            "route.hcm.operations.time-interpretation-run.action",
            "route.hcm.operations.time-interpretation-trace.data",
            "route.hcm.time.timecard-correction.action",
            "route.hcm.time.timecard.data");
    private static final String HCM_PRODUCT = "hcm";
    private static final String HCM_UTILITY_SURFACE = "hcm.utility";
    private static final String HCM_EXPLORER_RECEIPT_ROUTE =
            "route.hcm.utility.explorer-command-receipt.data";
    private static final String HCM_SHELL_CAPABILITY = "hcm.app.shell.view";

    private final ProductAuthorizationContractRepository repository;
    private final ProductAuthorizationIdentityEvidenceService evidenceService;
    private final ProductAuthorizationDecisionSupport decisionSupport;
    private final String requiredAcr;
    @Autowired
    public ProductAuthorizationAuthorityAdapter(
            ProductAuthorizationContractRepository repository,
            ProductAuthorizationIdentityEvidenceService evidenceService,
            ObjectProvider<TenantCapabilityOverrideReader> tenantCapabilityOverrides,
            ObjectProvider<TenantFeatureActivationReader> tenantFeatureActivations,
            @Value("${dwp.auth.step-up.required-acr:}") String requiredAcr) {
        this(repository, evidenceService,
                ProductAuthorizationDecisionSupport.from(
                        tenantCapabilityOverrides, tenantFeatureActivations), requiredAcr);
    }
    public ProductAuthorizationAuthorityAdapter(
            ProductAuthorizationContractRepository repository,
            ProductAuthorizationIdentityEvidenceService evidenceService,
            String requiredAcr) {
        this(repository, evidenceService,
                ProductAuthorizationDecisionSupport.withoutOverrides(Clock.systemUTC()),
                requiredAcr);
    }
    ProductAuthorizationAuthorityAdapter(
            ProductAuthorizationContractRepository repository,
            ProductAuthorizationIdentityEvidenceService evidenceService,
            Clock clock, String requiredAcr) {
        this(repository, evidenceService,
                ProductAuthorizationDecisionSupport.withoutOverrides(clock), requiredAcr);
    }
    ProductAuthorizationAuthorityAdapter(
            ProductAuthorizationContractRepository repository,
            ProductAuthorizationIdentityEvidenceService evidenceService,
            TenantCapabilityOverrideReader tenantCapabilityOverrides,
            Clock clock, String requiredAcr) {
        this(repository, evidenceService,
                new ProductAuthorizationDecisionSupport(tenantCapabilityOverrides, clock),
                requiredAcr);
    }
    ProductAuthorizationAuthorityAdapter(
            ProductAuthorizationContractRepository repository,
            ProductAuthorizationIdentityEvidenceService evidenceService,
            TenantCapabilityOverrideReader tenantCapabilityOverrides,
            TenantFeatureActivationReader tenantFeatureActivations,
            Clock clock,
            String requiredAcr) {
        this(repository, evidenceService,
                new ProductAuthorizationDecisionSupport(
                        tenantCapabilityOverrides, tenantFeatureActivations, clock),
                requiredAcr);
    }
    private ProductAuthorizationAuthorityAdapter(
            ProductAuthorizationContractRepository repository,
            ProductAuthorizationIdentityEvidenceService evidenceService,
            ProductAuthorizationDecisionSupport decisionSupport,
            String requiredAcr) {
        this.repository = repository;
        this.evidenceService = evidenceService;
        this.decisionSupport = decisionSupport;
        this.requiredAcr = requiredAcr;
    }
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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
        String authorityContextRevision = decisionSupport.contextRevision(
                request.tenantId(), policyRevision);
        OffsetDateTime now = decisionSupport.now();
        OffsetDateTime revalidateAt = earliest(evaluation.validUntil(), now.plusSeconds(60));
        String contextKey = contextKey(request, identity.revision(), authorityContextRevision,
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
        materialized = evaluation.allowed()
                || evaluation.decision()
                == ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED;
        TenantFeatureActivationReader.GateResult featureGate = decisionSupport.featureGate(
                request, stored.version(), materialized);
        String tenantControlRevision = decisionSupport.tenantControlRevision(
                request.tenantId(), featureGate);
        if (materialized && featureGate.state()
                == TenantFeatureActivationReader.State.AUTHORITY_UNAVAILABLE) {
            evaluation = Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE");
        } else if (materialized && !featureGate.preservesAuthority()) {
            evaluation = Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.ACTIVATION_REQUIRED,
                    featureGate.reasonCode());
        }
        return decisionSupport.result(
                request, identity.revision(), policyRevision, tenantControlRevision,
                contextKey, revalidateAt, evaluation);
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
        if (usesCurrentReceiptSelectorOnUnrelatedRoute(request)) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID,
                    "SCOPE_CONTEXT_EXPIRED");
        }
        List<ProductAuthorizationContractDtos.AccessProfile> profiles = route.accessProfiles().stream()
                .filter(value -> value.activeAccessModes()
                        .contains(request.activeAccessMode().name())
                        || HcmAuditAuthorityPolicy.isNormalChallengeCandidate(
                                request, route,
                                registry.capabilitiesByKey().get(
                                        HcmAuditAuthorityPolicy.CAPABILITY))
                        || PayrollV49ElevatedAuthorityPolicy
                        .isNormalChallengeCandidate(
                                request, route, registry.capabilitiesByKey()))
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
        Evaluation deferredGenericChallenge = null;
        boolean allowedProfileScopeMismatch = false;
        for (ProductAuthorizationContractDtos.AccessProfile profile : profiles) {
            Evaluation result = evaluateProfile(
                    request, registry, identity, route, profile, directEntryScopes);
            if (blockingProfileDenial(result)) return result.forRoute();
            if (result.allowed() || result.decision()
                    == ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED) {
                if ((requiresExactProfileSelection(request)
                        && result.scopes().stream().noneMatch(scope ->
                                request.contextScopeKey().equals(scope.key())))
                        || (requiresExactReceiptProfileSelection(request)
                        && !matchesExactReceiptProfile(request, result))) {
                    // Overlapping profiles must not let precedence swallow an explicitly
                    // selected source scope. Keep evaluating until the signed selector chooses
                    // its matching profile; object identifiers are never identity evidence.
                    allowedProfileScopeMismatch = true;
                    continue;
                }
                Evaluation routed = routed(request, identity, route,
                        rebindUtilityShellSelf(request, registry, result));
                if (result.allowed()
                        || !GENERIC_SESSION_PROFILE_ALTERNATIVE_ROUTES.contains(
                        route.routeContractKey())) {
                    return routed;
                }
                if (deferredGenericChallenge == null) {
                    deferredGenericChallenge = routed;
                }
                continue;
            }
            if (firstDenial == null) firstDenial = result;
        }
        if (deferredGenericChallenge != null) return deferredGenericChallenge;
        if (allowedProfileScopeMismatch) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID,
                    "SCOPE_CONTEXT_EXPIRED");
        }
        return Objects.requireNonNull(firstDenial).forRoute();
    }

    private boolean requiresExactProfileSelection(
            ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        if (request.contextScopeKey() == null || request.contextScopeKey().isBlank()) {
            return false;
        }
        if (PERFORMANCE_REVIEW_DUAL_PROFILE_ROUTES.contains(request.routeContractKey())) {
            return true;
        }
        if (HCM_CERTIFICATE_SHARED_ACTION_ROUTE.equals(request.routeContractKey())) {
            return true;
        }
        // Gateway traffic can carry a People-derived hcm-scope-* selection. The R2 source
        // selector is the pre-intersection scope-* value used by the dedicated origin bridge.
        return HCM_PRODUCT.equals(request.productKey())
                && "hcm.operations".equals(request.surfaceKey())
                && HCM_EMPLOYEE_CHANGE_MULTI_PROFILE_ROUTES.contains(
                        request.routeContractKey())
                && !request.contextScopeKey().matches("^hcm-scope-[a-f0-9]{40}$");
    }

    private boolean requiresExactReceiptProfileSelection(
            ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        return HCM_PRODUCT.equals(request.productKey())
                && HCM_UTILITY_SURFACE.equals(request.surfaceKey())
                && HCM_EXPLORER_RECEIPT_ROUTE.equals(request.routeContractKey())
                && request.contextScopeKey() != null
                && !request.contextScopeKey().matches("^hcm-scope-[a-f0-9]{40}$");
    }

    private boolean usesCurrentReceiptSelectorOnUnrelatedRoute(
            ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        return HCM_PRODUCT.equals(request.productKey())
                && HCM_UTILITY_SURFACE.equals(request.surfaceKey())
                && !HCM_EXPLORER_RECEIPT_ROUTE.equals(request.routeContractKey())
                && scopeKey(request, "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION")
                .equals(request.contextScopeKey());
    }

    private boolean matchesExactReceiptProfile(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Evaluation result) {
        String expected = scopeKey(
                request, "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION");
        return expected.equals(request.contextScopeKey())
                && result.scopes().size() == 1
                && expected.equals(result.scopes().getFirst().key());
    }

    private Evaluation rebindUtilityShellSelf(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            Evaluation result) {
        ProductAuthorizationContractDtos.CapabilityContract capability =
                registry.capabilitiesByKey().get(HCM_SHELL_CAPABILITY);
        if (!HCM_PRODUCT.equals(request.productKey())
                || !HCM_UTILITY_SURFACE.equals(request.surfaceKey())
                || capability == null
                || !"SELF".equals(capability.scopeResolver())
                || result.grants().isEmpty()
                || result.grants().stream().anyMatch(grant ->
                !(grant instanceof ProductSurfaceAuthorityDtos.CapabilityGrant value)
                        || !HCM_SHELL_CAPABILITY.equals(
                        value.capabilityContractKey()))) {
            return result;
        }
        String selfKey = scopeKey(request, "SELF", "SELF");
        List<ProductSurfaceAuthorityDtos.EffectiveScope> scopes = List.of(
                new ProductSurfaceAuthorityDtos.EffectiveScope(
                        selfKey, "SELF", "Self", true,
                        result.effectiveReadOnly(), result.validUntil()));
        List<ProductSurfaceAuthorityDtos.EffectiveGrant> grants = result.grants().stream()
                .map(grant -> {
                    ProductSurfaceAuthorityDtos.CapabilityGrant value =
                            (ProductSurfaceAuthorityDtos.CapabilityGrant) grant;
                    return (ProductSurfaceAuthorityDtos.EffectiveGrant)
                            new ProductSurfaceAuthorityDtos.CapabilityGrant(
                                    value.capabilityContractKey(),
                                    value.resolvedCapabilityCode(), value.authorityMode(),
                                    value.predicatePolicyKeys(),
                                    value.responsibilityRequirement(), value.responsibility(),
                                    List.of(selfKey), value.requiresProductEntitlement(),
                                    value.readOnly(), value.activationState(),
                                    value.validUntil());
                }).toList();
        return new Evaluation(
                result.decision(), result.reasonCode(), result.accessSource(),
                grants, scopes, result.routeGrantRef(), result.effectiveReadOnly(),
                result.requiresProductEligibility(), result.validUntil(),
                result.requiredAssurance(), result.requestPolicyRef(),
                result.appResourceKey());
    }

    private Evaluation evaluateProfile(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.AccessProfile profile,
            List<ProductSurfaceAuthorityDtos.EffectiveScope> directEntryScopes) {
        ProductAuthorizationContractDtos.RequiredAccess access = profile.requiredAccess();
        ProductAuthorizationContractDtos.CapabilityContract capability =
                "CAPABILITY".equals(access.type())
                        ? registry.capabilitiesByKey().get(access.capabilityContractKey())
                        : null;
        if (HcmAuditAuthorityPolicy.isExactReadRoute(route, capability)) {
            return evaluateHcmAuditCapability(request, identity, capability,
                    profile.predicatePolicyKeys());
        }
        Evaluation result = switch (access.type()) {
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
            case "CAPABILITY_EXPRESSION" -> ApprovalFormAdminSourceCapability.applies(request, profile)
                    ? ApprovalFormAdminSourceCapability.evaluate(request, registry, identity, profile,
                            evaluateCapability(request, registry, identity,
                                    registry.capabilitiesByKey().get("approvals.design.read"),
                                    profile.predicatePolicyKeys(), true, directEntryScopes))
                    : evaluateCapabilityExpression(
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
        return enforceCertificateRouteHighAssurance(request, route, result);
    }

    /**
     * The one HRM-009 command route intentionally has two atomic-duty profiles. The
     * self capability has a MEDIUM baseline, but the shared wire route is HIGH. A
     * normal-mode self grant must therefore be challenge-eligible just like the
     * operations grant; capability precedence must never downgrade route assurance.
     */
    private Evaluation enforceCertificateRouteHighAssurance(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            ProductAuthorizationContractDtos.GovernedRoute route,
            Evaluation result) {
        if (!HCM_CERTIFICATE_SHARED_ACTION_ROUTE.equals(route.routeContractKey())
                || !result.allowed()
                || request.activeAccessMode()
                != ProductSurfaceAuthorityDtos.AccessMode.NORMAL) {
            return result;
        }
        if (result.effectiveReadOnly() || result.grants().isEmpty()
                || result.grants().stream().anyMatch(grant ->
                !(grant instanceof ProductSurfaceAuthorityDtos.CapabilityGrant))) {
            return Evaluation.denied(
                    ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE,
                    "HRM009_SHARED_ROUTE_ASSURANCE_INVALID");
        }
        List<ProductSurfaceAuthorityDtos.EffectiveGrant> eligible = result.grants().stream()
                .map(grant -> {
                    ProductSurfaceAuthorityDtos.CapabilityGrant capability =
                            (ProductSurfaceAuthorityDtos.CapabilityGrant) grant;
                    return (ProductSurfaceAuthorityDtos.EffectiveGrant)
                            new ProductSurfaceAuthorityDtos.CapabilityGrant(
                                    capability.capabilityContractKey(),
                                    capability.resolvedCapabilityCode(),
                                    capability.authorityMode(),
                                    capability.predicatePolicyKeys(),
                                    capability.responsibilityRequirement(),
                                    capability.responsibility(),
                                    capability.scopeKeys(),
                                    capability.requiresProductEntitlement(),
                                    capability.readOnly(),
                                    ProductSurfaceAuthorityDtos.ActivationState.ELIGIBLE,
                                    capability.validUntil());
                })
                .toList();
        return Evaluation.challenge(
                ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED,
                "STEP_UP_REQUIRED",
                HCM_CERTIFICATE_ROUTE_STEP_UP_POLICY,
                requiredAcr,
                result.accessSource(),
                eligible,
                result.scopes(),
                false,
                result.validUntil(),
                result.appResourceKey());
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
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity,
            ProductAuthorizationContractDtos.GovernedRoute route,
            Evaluation result) {
        boolean peopleEligibility = ProductAuthorizationPeopleEligibility.requiredForRoute(
                request, route, result);
        if (!result.allowed()) {
            Evaluation denial = result.forRoute();
            if (denial.decision() == ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED
                    && request.activeAccessMode()
                    == ProductSurfaceAuthorityDtos.AccessMode.NORMAL
                    && HcmAuditAuthorityPolicy.ROUTES.contains(
                            request.routeContractKey())) {
                return denial.withProductEligibility(peopleEligibility);
            }
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
                    capability.activationPolicy(), requiredAcr,
                    ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT,
                    grants, scopes, readOnly, validUntil, capability.resourceKey());
        }
        return Evaluation.allowed(
                ProductSurfaceAuthorityDtos.AccessSource.MANAGEMENT,
                grants, scopes, readOnly, validUntil, false,
                capability.resourceKey());
    }

    private Evaluation evaluateHcmAuditCapability(
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
