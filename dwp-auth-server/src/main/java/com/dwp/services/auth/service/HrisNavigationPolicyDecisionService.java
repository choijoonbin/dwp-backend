package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.auth.dto.HrisNavigationPolicyDecisionDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.dwp.services.auth.repository.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import static com.dwp.services.auth.service.ProductAuthorizationAuthoritySupport.digest;

/**
 * Auth-owned R6-C decision carrier. The candidate paths are deliberately compiled into this
 * service; an HTTP caller can provide freshness expectations but cannot inject an alias.
 */
@Service
public class HrisNavigationPolicyDecisionService {

    public static final long REGISTRY_VERSION = 47;
    public static final String REGISTRY_CHECKSUM =
            "925e8bfc406bc7e4ee62aee08582fdd7c67c629ffde470051704eaec5404800e";
    private static final String BUNDLE_KEY = "product-surfaces";
    private static final String PRODUCT = "hcm";
    private static final String SURFACE = "hcm.personal";
    private static final String PROFILE = "full-work";
    private static final String TARGET = "SELF";
    private static final Duration MAX_FRESHNESS = Duration.ofSeconds(30);
    private static final Pattern SCOPE_KEY = Pattern.compile("^scope-[0-9a-f]{32}$");
    private static final Pattern AUTH_REVISION = Pattern.compile("^auth-[0-9a-f]{64}$");
    private static final Pattern DECISION_REVISION =
            Pattern.compile("^decision-[0-9a-f]{64}$");
    private static final Pattern ROUTE_GRANT_REF = Pattern.compile("^grant-[0-9a-f]{32}$");
    private static final Set<ProductSurfaceAuthorityDtos.AccessMode> ACCESS_MODES = Set.of(
            ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
            ProductSurfaceAuthorityDtos.AccessMode.ELEVATED);
    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate(
                    "BASE:my-hr-profile", "/hr/me",
                    "route.hcm.personal.me.page", "hcm.personal-core-access.v1"),
            new Candidate(
                    "BASE:my-time", "/hr/time",
                    "route.hcm.personal.time.page", "hcm.personal-core-access.v1"),
            new Candidate(
                    "BASE:my-leave", "/hr/absence",
                    "route.hcm.personal.absence.page", "hcm.personal-core-access.v1"),
            new Candidate(
                    "BASE:my-pay-tax", "/hr/pay",
                    "route.hcm.personal.pay.page", "hcm.personal-core-access.v1"),
            new Candidate(
                    "BASE:my-goals-performance", "/hr/talent",
                    "route.hcm.personal.talent.page", "hcm.personal-core-access.v1"),
            new Candidate(
                    "BASE:my-hr-requests", "/hr/services",
                    "route.hcm.personal.services.page", "hcm.personal-services-access.v1"));

    private final ObjectProvider<ProductSurfaceAuthorityPort> authorityPorts;
    private final ProductAuthorizationContractRepository contracts;
    private final UserRepository users;
    private final Clock clock;

    public HrisNavigationPolicyDecisionService(
            ObjectProvider<ProductSurfaceAuthorityPort> authorityPorts,
            ProductAuthorizationContractRepository contracts,
            UserRepository users) {
        this(authorityPorts, contracts, users, Clock.systemUTC());
    }

    HrisNavigationPolicyDecisionService(
            ObjectProvider<ProductSurfaceAuthorityPort> authorityPorts,
            ProductAuthorizationContractRepository contracts,
            UserRepository users,
            Clock clock) {
        this.authorityPorts = authorityPorts;
        this.contracts = contracts;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HrisNavigationPolicyDecisionDtos.BatchResult evaluate(
            HrisNavigationPolicyDecisionDtos.BatchRequest request) {
        try {
            return evaluateExact(request);
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The exact v47 HRIS navigation POLICY authority is unavailable.",
                    exception);
        }
    }

    private HrisNavigationPolicyDecisionDtos.BatchResult evaluateExact(
            HrisNavigationPolicyDecisionDtos.BatchRequest request) {
        validateRequest(request);
        ProductAuthorizationContractRepository.StoredBundle stored = contracts
                .findActive(BUNDLE_KEY)
                .orElseThrow(HrisNavigationPolicyDecisionService::unavailable);
        ProductAuthorizationContractRepository.ActivePointer pointer = contracts
                .findActivePointer(BUNDLE_KEY)
                .filter(value -> value.bundleId().equals(stored.bundleId()))
                .orElseThrow(HrisNavigationPolicyDecisionService::unavailable);
        if (!BUNDLE_KEY.equals(stored.bundleKey())
                || stored.version() != REGISTRY_VERSION
                || !"ACTIVE".equals(stored.bundleStatus())
                || !"SHA-256".equals(stored.checksumAlgorithm())
                || !REGISTRY_CHECKSUM.equals(stored.checksum())) {
            throw unavailable();
        }
        if (request.expectedRegistryVersion() != REGISTRY_VERSION
                || !REGISTRY_CHECKSUM.equals(request.expectedRegistryChecksum())) {
            throw revisionConflict("The expected HRIS navigation registry is stale.");
        }

        ProductAuthorizationContractDtos.BundleContract contract = contracts.loadContract(stored);
        Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes = exactRoutes(contract);
        Map<String, ProductAuthorizationContractDtos.AccessPolicy> policies = exactPolicies(contract);
        CANDIDATES.forEach(candidate -> validateCandidate(candidate, routes, policies));

        User actor = users.findTenantIdentityByUserIdAndTenantId(
                        request.actorId(), request.tenantId())
                .orElseThrow(HrisNavigationPolicyDecisionService::denied);
        IdentityBinding binding = identityBinding(request, actor);
        String scopeKey = ProductSurfaceScopeKey.key(
                request.tenantId(), request.actorId(), PRODUCT, SURFACE, TARGET, TARGET);
        if (!binding.personPublicId().equals(request.expectedPersonPublicId())
                || !scopeKey.equals(request.expectedScopeKey())) {
            throw denied();
        }
        String relationshipRevision = "relationship-" + digest(String.join("\n",
                String.valueOf(request.tenantId()), String.valueOf(request.actorId()),
                binding.principalPublicId().toString(), binding.personPublicId().toString(),
                String.valueOf(binding.rowVersion()), String.valueOf(binding.accessRevision()),
                TARGET, scopeKey));

        List<ProductSurfaceAuthorityPort> ports = authorityPorts.orderedStream().toList();
        if (ports.size() != 1) throw unavailable();
        ProductSurfaceAuthorityPort authority = ports.getFirst();
        String expectedPolicyRevision = "policy-" + stored.version() + '-'
                + pointer.revision() + '-' + stored.checksum();
        List<RawDecision> raw = new ArrayList<>(CANDIDATES.size());
        String authRevision = null;
        OffsetDateTime freshUntil = OffsetDateTime.now(clock).plus(MAX_FRESHNESS);
        for (Candidate candidate : CANDIDATES) {
            ProductSurfaceAuthorityDtos.AuthorityResult result = authority.evaluate(
                    new ProductSurfaceAuthorityDtos.EvaluateRequest(
                            request.tenantId(), request.actorId(), PRODUCT, SURFACE,
                            request.activeAccessMode(), candidate.routeContractKey(), null,
                            scopeKey, null, null, List.of()));
            validateCommonResult(request, result, expectedPolicyRevision);
            if (authRevision == null) authRevision = result.authRevision();
            if (!authRevision.equals(result.authRevision())) throw unavailable();
            if (result.revalidateAt() != null && result.revalidateAt().isBefore(freshUntil)) {
                freshUntil = result.revalidateAt();
            }
            raw.add(routeDecision(
                    candidate, result, binding.personPublicId(), scopeKey));
        }
        if (!freshUntil.isAfter(OffsetDateTime.now(clock))) throw unavailable();

        String snapshotRevision = "snapshot-" + digest(String.join("\n",
                stored.checksum(), String.valueOf(pointer.revision()), authRevision,
                expectedPolicyRevision, relationshipRevision, scopeKey));
        String decisionMaterial = raw.stream()
                .map(value -> String.join("|", value.candidate().nodeId(),
                        value.candidate().path(), value.candidate().routeContractKey(),
                        value.decision().name(), Objects.toString(value.routeGrantRef(), "")))
                .collect(Collectors.joining("\n"));
        String decisionRevision = "decision-" + digest(
                snapshotRevision + '\n' + decisionMaterial);
        if (request.expectedDecisionRevision() != null
                && !request.expectedDecisionRevision().equals(decisionRevision)) {
            throw revisionConflict("The HRIS navigation POLICY decision revision changed.");
        }

        OffsetDateTime sealedFreshUntil = freshUntil;
        String sealedAuthRevision = authRevision;
        List<HrisNavigationPolicyDecisionDtos.RouteDecision> decisions = raw.stream()
                .map(value -> new HrisNavigationPolicyDecisionDtos.RouteDecision(
                        value.candidate().nodeId(), value.candidate().path(),
                        value.candidate().routeContractKey(), PROFILE, TARGET,
                        value.scopeBinding(), value.decision(), value.reasonCode(),
                        value.routeGrantRef(), decisionRevision, sealedAuthRevision,
                        expectedPolicyRevision, relationshipRevision, sealedFreshUntil))
                .toList();
        return new HrisNavigationPolicyDecisionDtos.BatchResult(
                REGISTRY_VERSION, REGISTRY_CHECKSUM, snapshotRevision, decisionRevision,
                authRevision, expectedPolicyRevision, relationshipRevision,
                freshUntil, decisions);
    }

    private void validateRequest(HrisNavigationPolicyDecisionDtos.BatchRequest request) {
        if (request == null || request.tenantId() == null || request.tenantId() <= 0
                || request.actorId() == null || request.actorId() <= 0
                || request.expectedPersonPublicId() == null
                || request.expectedRegistryVersion() == null
                || !ACCESS_MODES.contains(request.activeAccessMode())
                || !SCOPE_KEY.matcher(
                        Objects.toString(request.expectedScopeKey(), "")).matches()
                || !Pattern.matches("^[0-9a-f]{64}$",
                        Objects.toString(request.expectedRegistryChecksum(), ""))
                || (request.expectedDecisionRevision() != null
                        && !DECISION_REVISION.matcher(
                                request.expectedDecisionRevision()).matches())
                || (request.expectedDecisionRevision() != null
                        && !canonical(request.expectedDecisionRevision()))) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The HRIS navigation POLICY decision request is not canonical.");
        }
    }

    private Map<String, ProductAuthorizationContractDtos.GovernedRoute> exactRoutes(
            ProductAuthorizationContractDtos.BundleContract contract) {
        if (contract == null || !BUNDLE_KEY.equals(contract.bundleKey())
                || contract.version() != REGISTRY_VERSION
                || !"ACTIVE".equals(contract.bundleStatus())
                || !"SHA-256".equals(contract.checksumAlgorithm())
                || !REGISTRY_CHECKSUM.equals(contract.checksum())) throw unavailable();
        try {
            return contract.routes().stream().collect(Collectors.toMap(
                    ProductAuthorizationContractDtos.GovernedRoute::routeContractKey,
                    value -> value, (left, right) -> {
                        throw new IllegalStateException("Ambiguous governed route");
                    }, LinkedHashMap::new));
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private Map<String, ProductAuthorizationContractDtos.AccessPolicy> exactPolicies(
            ProductAuthorizationContractDtos.BundleContract contract) {
        try {
            return contract.accessPolicies().stream().collect(Collectors.toMap(
                    ProductAuthorizationContractDtos.AccessPolicy::accessPolicyKey,
                    value -> value, (left, right) -> {
                        throw new IllegalStateException("Ambiguous access policy");
                    }, LinkedHashMap::new));
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private void validateCandidate(
            Candidate candidate,
            Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes,
            Map<String, ProductAuthorizationContractDtos.AccessPolicy> policies) {
        ProductAuthorizationContractDtos.GovernedRoute route = routes.get(
                candidate.routeContractKey());
        if (route == null || !"ACTIVE".equals(route.lifecycleState())
                || !"PAGE".equals(route.routeKind())
                || !candidate.path().equals(route.uiRoutePattern())
                || !"hcm.personal".equals(route.navigationContextId())
                || route.subject() == null || !"PRODUCT".equals(route.subject().type())
                || !PRODUCT.equals(route.subject().productKey())
                || !SURFACE.equals(route.subject().surfaceKey())
                || route.accessProfiles() == null || route.accessProfiles().size() != 1) {
            throw unavailable();
        }
        ProductAuthorizationContractDtos.AccessProfile profile = route.accessProfiles().getFirst();
        ProductAuthorizationContractDtos.RequiredAccess access = profile.requiredAccess();
        if (!PROFILE.equals(profile.profileKey())
                || !List.of(TARGET).equals(profile.targetBindingKinds())
                || !Set.copyOf(profile.activeAccessModes()).equals(Set.of("NORMAL", "ELEVATED"))
                || access == null || !"POLICY".equals(access.type())
                || !candidate.accessPolicyKey().equals(access.accessPolicyKey())
                || access.capabilityContractKey() != null || access.capabilityContractKeys() != null
                || access.mode() != null) {
            throw unavailable();
        }
        ProductAuthorizationContractDtos.AccessPolicy policy = policies.get(
                candidate.accessPolicyKey());
        if (policy == null || !"ACTIVE".equals(policy.lifecycleState())
                || !PRODUCT.equals(policy.productKey()) || !SURFACE.equals(policy.surfaceKey())
                || !"SINGLE".equals(policy.evaluationType())
                || !"ENTITLEMENT_AND_RELATIONSHIP".equals(policy.authorityMode())
                || !"SELF_PERSON_BINDING".equals(policy.relationshipResolver())
                || !TARGET.equals(policy.scopeResolver())
                || policy.routeContractKeys() == null
                || !policy.routeContractKeys().contains(candidate.routeContractKey())) {
            throw unavailable();
        }
    }

    private IdentityBinding identityBinding(
            HrisNavigationPolicyDecisionDtos.BatchRequest request,
            User actor) {
        if (!Objects.equals(request.actorId(), actor.getUserId())
                || !Objects.equals(request.tenantId(), actor.getTenantId())
                || actor.getPublicId() == null || actor.getPersonPublicId() == null
                || !"TENANT".equals(actor.getIdentityPlane())
                || !"ACTIVE".equals(actor.getStatus())
                || actor.getVersion() == null || actor.getVersion() < 0
                || actor.getAccessRevision() == null || actor.getAccessRevision() < 0) {
            throw denied();
        }
        return new IdentityBinding(
                actor.getPublicId(), actor.getPersonPublicId(), actor.getVersion(),
                actor.getAccessRevision());
    }

    private void validateCommonResult(
            HrisNavigationPolicyDecisionDtos.BatchRequest request,
            ProductSurfaceAuthorityDtos.AuthorityResult result,
            String expectedPolicyRevision) {
        if (result == null || result.decision() == null
                || result.accessMode() != request.activeAccessMode()
                || !PRODUCT.equals(result.productKey()) || !SURFACE.equals(result.surfaceKey())
                || !AUTH_REVISION.matcher(
                        Objects.toString(result.authRevision(), "")).matches()
                || !expectedPolicyRevision.equals(result.policyRevision())
                || result.decision() == ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE
                || result.decision() == ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED) {
            throw unavailable();
        }
    }

    private RawDecision routeDecision(
            Candidate candidate,
            ProductSurfaceAuthorityDtos.AuthorityResult result,
            UUID personPublicId,
            String scopeKey) {
        if (result.decision() != ProductSurfaceAuthorityDtos.Decision.ALLOWED) {
            if (!result.effectiveGrants().isEmpty() || !result.scopes().isEmpty()
                    || result.routeGrantRef() != null
                    || !canonical(result.reasonCode())
                    || "ALLOWED".equals(result.reasonCode())) throw unavailable();
            return new RawDecision(
                    candidate, HrisNavigationPolicyDecisionDtos.Decision.ROUTE_DENIED,
                    Objects.toString(result.reasonCode(), "ROUTE_DENIED"), null, null);
        }
        if (!"ALLOWED".equals(result.reasonCode())
                || result.accessSource() != ProductSurfaceAuthorityDtos.AccessSource.RELATIONSHIP
                || !ROUTE_GRANT_REF.matcher(
                        Objects.toString(result.routeGrantRef(), "")).matches()
                || result.scopes().size() != 1
                || result.effectiveGrants().size() != 1
                || !(result.effectiveGrants().getFirst()
                instanceof ProductSurfaceAuthorityDtos.PolicyGrant grant)
                || !candidate.accessPolicyKey().equals(grant.accessPolicyKey())
                || grant.authorityMode()
                != ProductSurfaceAuthorityDtos.PolicyAuthorityMode.ENTITLEMENT_AND_RELATIONSHIP
                || !List.of(scopeKey).equals(grant.scopeKeys())) {
            throw unavailable();
        }
        ProductSurfaceAuthorityDtos.EffectiveScope scope = result.scopes().getFirst();
        if (!scopeKey.equals(scope.key()) || !TARGET.equals(scope.kind())
                || !SCOPE_KEY.matcher(scope.key()).matches()
                || !scope.isDefault() || result.revalidateAt() == null
                || !result.revalidateAt().isAfter(OffsetDateTime.now(clock))) {
            throw unavailable();
        }
        return new RawDecision(
                candidate, HrisNavigationPolicyDecisionDtos.Decision.ALLOWED,
                "ALLOWED", result.routeGrantRef(),
                new HrisNavigationPolicyDecisionDtos.ScopeBinding(
                        TARGET, scopeKey, personPublicId));
    }

    private static boolean canonical(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip());
    }

    private static BaseException denied() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "The current SELF person or scope binding was rejected.");
    }

    private static BaseException revisionConflict(String message) {
        return new BaseException(ErrorCode.DECISION_REVISION_CONFLICT, message);
    }

    private static BaseException unavailable() {
        return new BaseException(
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "The exact v47 HRIS navigation POLICY authority is unavailable.");
    }

    private record Candidate(
            String nodeId,
            String path,
            String routeContractKey,
            String accessPolicyKey) {
    }

    private record IdentityBinding(
            UUID principalPublicId,
            UUID personPublicId,
            long rowVersion,
            long accessRevision) {
    }

    private record RawDecision(
            Candidate candidate,
            HrisNavigationPolicyDecisionDtos.Decision decision,
            String reasonCode,
            String routeGrantRef,
            HrisNavigationPolicyDecisionDtos.ScopeBinding scopeBinding) {
    }
}
