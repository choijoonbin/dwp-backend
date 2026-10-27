package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.core.security.Hrm012OwnerReceiptAuthorityContract;
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
import java.util.Comparator;
import java.util.List;
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
    private static final String HRM012_ACTION_ROUTE =
            "route.hcm.operations.workforce-ingestion.action";
    private static final String HRM012_SERVICE_PATH =
            "/v1/hris/integrations/workforce-ingestions";

    private final ProductAuthorizationContractRepository repository;
    private final ProductAuthorizationIdentityEvidenceService evidenceService;
    private final ProductAuthorizationDecisionSupport decisionSupport;
    private final String requiredAcr;
    private final ProductAuthorizationPolicyEvaluator policies;
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
        this.policies = new ProductAuthorizationPolicyEvaluator(decisionSupport, requiredAcr);
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
        ProductSurfaceAuthorityDtos.AuthorityResult ownerReceipt = ownerReceiptPreflight(
                request, stored, pointerRevision, registry, identity);
        if (ownerReceipt != null) return ownerReceipt;
        Evaluation evaluation = request.directRouteEvaluation()
                ? evaluateRoute(request, registry, identity)
                : policies.evaluateEntry(request, registry, identity);
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
        if (materialized && HRM012_ACTION_ROUTE.equals(request.routeContractKey())
                && !exactHrm012FeatureGate(featureGate)) {
            evaluation = featureActivationPending(featureGate)
                    ? Evaluation.denied(
                            ProductSurfaceAuthorityDtos.Decision.ACTIVATION_REQUIRED,
                            featureGate.reasonCode())
                    : Evaluation.denied(
                            ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE,
                            "AUTHORITY_RESOLUTION_UNAVAILABLE");
        } else if (materialized && featureGate.state()
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

    private ProductSurfaceAuthorityDtos.AuthorityResult ownerReceiptPreflight(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            ProductAuthorizationContractRepository.StoredBundle stored,
            long pointerRevision,
            Registry registry,
            ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity) {
        if (!request.ownerReceiptRequest()) return null;
        String policyRevision = "policy-" + stored.version() + '-' + pointerRevision + '-'
                + stored.checksum();
        if (!"hcm".equals(request.productKey())
                || !"hcm.operations".equals(request.surfaceKey())
                || request.activeAccessMode() != ProductSurfaceAuthorityDtos.AccessMode.NORMAL
                || !HRM012_ACTION_ROUTE.equals(request.routeContractKey())
                || !"POST".equals(request.commandMethod())
                || !HRM012_SERVICE_PATH.equals(request.commandPath())
                || request.contextKey() != null || request.contextScopeKey() != null
                || request.supportSessionRef() != null || request.supportRevision() != null
                || !request.supportScopes().isEmpty()
                || request.correlationId() == null || request.correlationId().isBlank()
                || !request.correlationId().equals(request.correlationId().strip())
                || request.correlationId().indexOf(',') >= 0
                || request.correlationId().indexOf('\r') >= 0
                || request.correlationId().indexOf('\n') >= 0
                || request.idempotencyKeySha256() == null
                || !request.idempotencyKeySha256().matches("[a-f0-9]{64}")
                || request.opaqueStepUpChallengeRef() != null
                && (!request.opaqueStepUpChallengeRef().equals(
                        request.opaqueStepUpChallengeRef().strip())
                    || request.opaqueStepUpChallengeRef().isBlank()
                    || request.opaqueStepUpChallengeRef().indexOf(',') >= 0
                    || request.opaqueStepUpChallengeRef().indexOf('\r') >= 0
                    || request.opaqueStepUpChallengeRef().indexOf('\n') >= 0)
                || registry.routesByKey().get(HRM012_ACTION_ROUTE) == null) {
            return ProductSurfaceAuthorityDtos.AuthorityResult.unavailable(request);
        }
        String acceptedApp = identity.permissions().contains("APP.HCM:VIEW")
                ? "APP.HCM"
                : identity.permissions().contains("APP.HRIS:VIEW") ? "APP.HRIS" : null;
        if (acceptedApp == null) {
            return ownerReceiptResult(request, identity.revision(), policyRevision,
                    null, ProductSurfaceAuthorityDtos.Decision.APP_DENIED,
                    "APP_ENTITLEMENT_REQUIRED", null);
        }
        TenantFeatureActivationReader.GateResult feature = decisionSupport.featureGate(
                request, stored.version(), true);
        String tenantControlRevision = decisionSupport.tenantControlRevision(
                request.tenantId(), feature);
        if (featureActivationPending(feature)) {
            return ownerReceiptResult(request, identity.revision(), policyRevision,
                    tenantControlRevision,
                    ProductSurfaceAuthorityDtos.Decision.ACTIVATION_REQUIRED,
                    feature.reasonCode(), null);
        }
        if (!exactHrm012FeatureGate(feature)) {
            return ownerReceiptResult(request, identity.revision(), policyRevision,
                    tenantControlRevision,
                    ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        return ownerReceiptResult(request, identity.revision(), policyRevision,
                tenantControlRevision,
                ProductSurfaceAuthorityDtos.Decision.OWNER_RECEIPT_AUTH_REQUIRED,
                Hrm012OwnerReceiptAuthorityContract.STATE, acceptedApp);
    }

    private boolean exactHrm012FeatureGate(
            TenantFeatureActivationReader.GateResult feature) {
        return feature != null
                && feature.state() == TenantFeatureActivationReader.State.ACTIVE
                && Hrm012OwnerReceiptAuthorityContract.FEATURE.equals(
                        feature.featureKey());
    }

    private boolean featureActivationPending(
            TenantFeatureActivationReader.GateResult feature) {
        return feature != null
                && (feature.state()
                        == TenantFeatureActivationReader.State.FEATURE_NOT_ACTIVATED
                || feature.state()
                        == TenantFeatureActivationReader.State.TENANT_NOT_ENROLLED);
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult ownerReceiptResult(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            String authRevision,
            String policyRevision,
            String tenantControlRevision,
            ProductSurfaceAuthorityDtos.Decision decision,
            String reason,
            String appResourceKey) {
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                decision, reason, authRevision, policyRevision, tenantControlRevision,
                null, request.productKey(), request.surfaceKey(), "work",
                request.activeAccessMode(), appResourceKey == null ? null
                        : ProductSurfaceAuthorityDtos.AccessSource.ENTITLEMENT,
                appResourceKey, List.of(), List.of(), null, true, false,
                null, null, null, null, decisionSupport.now().plusSeconds(30), null, null);
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
                        || hrm012NormalChallengeCandidate(request, route, value)
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
                policies.singleSelfEntryPolicyScopes(request, registry, identity);
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

    private boolean hrm012NormalChallengeCandidate(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.AccessProfile profile) {
        return request.activeAccessMode() == ProductSurfaceAuthorityDtos.AccessMode.NORMAL
                && HRM012_ACTION_ROUTE.equals(route.routeContractKey())
                && "workforce-ingestion-executor".equals(profile.profileKey())
                && profile.activeAccessModes().equals(List.of("ELEVATED"));
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
            return policies.evaluateHcmAuditCapability(request, identity, capability,
                    profile.predicatePolicyKeys());
        }
        Evaluation result = switch (access.type()) {
            case "CAPABILITY" -> policies.evaluateCapability(
                    request,
                    registry,
                    identity,
                    registry.capabilitiesByKey().get(access.capabilityContractKey()),
                    profile.predicatePolicyKeys(),
                    profile.readOnly(),
                    directEntryScopes);
            case "POLICY" -> policies.evaluatePolicy(
                    request,
                    registry,
                    identity,
                    registry.policiesByKey().get(access.accessPolicyKey()),
                    profile.readOnly());
            case "CAPABILITY_EXPRESSION" -> ApprovalFormAdminSourceCapability.applies(request, profile)
                    ? ApprovalFormAdminSourceCapability.evaluate(request, registry, identity, profile,
                            policies.evaluateCapability(request, registry, identity,
                                    registry.capabilitiesByKey().get("approvals.design.read"),
                                    profile.predicatePolicyKeys(), true, directEntryScopes))
                    : policies.evaluateCapabilityExpression(
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

}
