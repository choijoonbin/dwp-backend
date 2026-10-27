package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.security.HcmEligibilityScopeKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthorityRequest;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthoritySnapshot;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerReceiptAuthoritySource.CurrentExplorerReceiptAuthority;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisPlatformReceiptAuthoritySource.CurrentReceiptAuthority;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisPlatformReceiptAuthoritySource.ForwardedReceiptAuthority;

import static com.dwp.services.platform.hrisconfiguration.explorer.AuthHrisExplorerAuthorityRules.*;

/** Reproduces current Gateway Auth/People authority for Explorer and shared receipts. */
@Component
@ConditionalOnExpression(
        "'${dwp.hris.explorer.enabled:false}' == 'true' || "
                + "'${dwp.hris.controlled-transfer.enabled:false}' == 'true' || "
                + "'${dwp.hris.automation-control-plane.enabled:false}' == 'true' || "
                + "'${dwp.hris.integration-control-plane.enabled:false}' == 'true'")
final class AuthHrisExplorerAuthorityEvidenceQuery
        implements HrisExplorerAuthorityEvidenceQuery,
        HrisExplorerReceiptAuthoritySource,
        HrisPlatformReceiptAuthoritySource {

    static final String EXPLORER_PAGE_ROUTE =
            AuthHrisExplorerAuthorityRules.EXPLORER_PAGE_ROUTE;
    static final String RECEIPT_ROUTE =
            AuthHrisExplorerAuthorityRules.RECEIPT_ROUTE;
    static final String EXPLORER_PURPOSE =
            AuthHrisExplorerAuthorityRules.EXPLORER_PURPOSE;
    static final String RECEIPT_PURPOSE =
            AuthHrisExplorerAuthorityRules.RECEIPT_PURPOSE;
    static final String PLATFORM_ORIGIN_PURPOSE =
            AuthHrisExplorerAuthorityRules.PLATFORM_ORIGIN_PURPOSE;
    static final String CONTROLLED_TRANSFER_ORIGIN_PURPOSE =
            AuthHrisExplorerAuthorityRules.CONTROLLED_TRANSFER_ORIGIN_PURPOSE;
    static final String INTEGRATION_CONTROL_PLANE_ORIGIN_PURPOSE =
            AuthHrisExplorerAuthorityRules.INTEGRATION_CONTROL_PLANE_ORIGIN_PURPOSE;
    static final String CONTROLLED_TRANSFER_ACTION =
            AuthHrisExplorerAuthorityRules.CONTROLLED_TRANSFER_ACTION;
    static final Map<String, String> EXPLORER_ORIGIN_ROUTES_BY_ACTION =
            AuthHrisExplorerAuthorityRules.EXPLORER_ORIGIN_ROUTES_BY_ACTION;
    static final Map<String, String> AUTOMATION_ORIGIN_ROUTES_BY_ACTION =
            AuthHrisExplorerAuthorityRules.AUTOMATION_ORIGIN_ROUTES_BY_ACTION;
    static final Map<String, String> INTEGRATION_ORIGIN_ROUTES_BY_ACTION =
            AuthHrisExplorerAuthorityRules.INTEGRATION_ORIGIN_ROUTES_BY_ACTION;
    static final Map<String, String> PLATFORM_ORIGIN_ROUTES_BY_ACTION =
            AuthHrisExplorerAuthorityRules.PLATFORM_ORIGIN_ROUTES_BY_ACTION;

    private final AuthHrisExplorerAuthorityClient authorityClient;
    private final AuthHrisExplorerAuthorityRules rules;

    @Autowired
    AuthHrisExplorerAuthorityEvidenceQuery(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${dwp.hris.explorer.auth-url:http://localhost:8001}") String authUrl,
            @Value("${dwp.hris.explorer.people-url:http://localhost:8003}") String peopleUrl,
            @Value("${dwp.hris.explorer.product-surface-token:}")
            String productSurfaceToken,
            @Value("${dwp.hris.explorer.identity-sync-token:}")
            String identitySyncToken,
            @Value("${dwp.hris.explorer.people-service-token:}")
            String peopleServiceToken,
            @Value("${dwp.hris.explorer.authority-timeout:PT2S}") Duration timeout) {
        this(AuthHrisExplorerAuthorityRules.client(builder, authUrl, timeout), AuthHrisExplorerAuthorityRules.client(builder, peopleUrl, timeout),
                productSurfaceToken, identitySyncToken, peopleServiceToken,
                Clock.systemUTC(), objectMapper);
    }

    AuthHrisExplorerAuthorityEvidenceQuery(
            RestClient auth,
            RestClient people,
            String productSurfaceToken,
            String identitySyncToken,
            String peopleServiceToken,
            Clock clock,
            ObjectMapper objectMapper) {
        this.authorityClient = new AuthHrisExplorerAuthorityClient(auth, people,
                productSurfaceToken, identitySyncToken, peopleServiceToken, clock, objectMapper);
        this.rules = new AuthHrisExplorerAuthorityRules(clock);
    }

    @Override
    public Optional<AuthoritySnapshot> resolve(AuthorityRequest request) {
        if (!rules.validRequest(request)
                || !(EXPLORER_PURPOSE.equals(request.requestPurposeCode())
                || (RECEIPT_PURPOSE.equals(request.requestPurposeCode())
                && RECEIPT_ROUTE.equals(request.trustedRouteContractKey())))) {
            return Optional.empty();
        }
        if (!identityMatches(request)) return Optional.empty();

        RouteProof current = resolveRoute(
                request, UTILITY_SURFACE, request.trustedRouteContractKey(),
                request.contextKey(), request.contextScopeKey(), request.revalidateAt());
        if (!current.allowed() || !current.peopleIntersected()
                || !rules.hasCapability(current, SHELL_CAPABILITY)
                || !request.currentDecisionRevision().equals(current.decisionRevision())) {
            return Optional.empty();
        }
        RouteProof page = EXPLORER_PAGE_ROUTE.equals(request.trustedRouteContractKey())
                ? current : resolveRoute(request, UTILITY_SURFACE, EXPLORER_PAGE_ROUTE,
                request.contextKey(), request.contextScopeKey(), request.revalidateAt());
        if (!page.allowed() || !page.peopleIntersected()
                || !rules.hasCapability(page, SHELL_CAPABILITY)
                || !rules.sameAuthRevisions(current, page)) {
            return Optional.empty();
        }

        Map<String, RouteProof> firstPass = new LinkedHashMap<>();
        for (String routeKey : AuthHrisExplorerAuthorityRules.deployedRouteKeys()) {
            HrisExplorerV43RouteAdapter.RouteSpec spec =
                    HrisExplorerV43RouteAdapter.routeSpec(routeKey);
            RouteProof proof = resolveRoute(
                    request, spec.surfaceKey(),
                    spec.authorization().routeContractKey(), null, null, null);
            rules.requireStableTerminal(page, proof);
            if (proof.allowed() && !rules.hasCapability(
                    proof, spec.authorization().capabilityKeys().getFirst())) {
                throw AuthHrisExplorerAuthorityRules.unavailable("Explorer descendant authority evidence is incomplete");
            }
            firstPass.put(routeKey, proof);
        }

        // Recheck every terminal outcome so a mid-scan Auth or People change
        // cannot leave an early stale allow in the projection.
        for (String routeKey : AuthHrisExplorerAuthorityRules.deployedRouteKeys()) {
            HrisExplorerV43RouteAdapter.RouteSpec spec =
                    HrisExplorerV43RouteAdapter.routeSpec(routeKey);
            RouteProof repeated = resolveRoute(
                    request, spec.surfaceKey(),
                    spec.authorization().routeContractKey(), null, null, null);
            rules.requireStableTerminal(page, repeated);
            if (!firstPass.get(routeKey).stabilitySeal().equals(
                    repeated.stabilitySeal())) {
                throw AuthHrisExplorerAuthorityRules.unavailable("Explorer descendant authority changed during evaluation");
            }
        }

        RouteProof finalCurrent = resolveRoute(
                request, UTILITY_SURFACE, request.trustedRouteContractKey(),
                request.contextKey(), request.contextScopeKey(), request.revalidateAt());
        RouteProof finalPage = EXPLORER_PAGE_ROUTE.equals(request.trustedRouteContractKey())
                ? finalCurrent : resolveRoute(request, UTILITY_SURFACE,
                EXPLORER_PAGE_ROUTE, request.contextKey(), request.contextScopeKey(),
                request.revalidateAt());
        if (!current.stabilitySeal().equals(finalCurrent.stabilitySeal())
                || !page.stabilitySeal().equals(finalPage.stabilitySeal())) {
            throw AuthHrisExplorerAuthorityRules.unavailable("Explorer root authority changed during route evaluation");
        }

        Map<String, HrisExplorerDtos.ExplorerAuthorization> allowed =
                new LinkedHashMap<>();
        firstPass.forEach((routeKey, proof) -> {
            if (proof.allowed()) {
                allowed.put(routeKey, HrisExplorerV43RouteAdapter.authorization(routeKey));
            }
        });
        String populationDigest = HrisExplorerAuthoritySeals.populationScopeDigest(
                current.selectedScopeKey());
        OffsetDateTime freshUntil = firstPass.values().stream()
                .filter(RouteProof::allowed)
                .map(RouteProof::freshUntil)
                .reduce(rules.earliest(current.freshUntil(), page.freshUntil()), rules::earliest);
        return Optional.of(new AuthoritySnapshot(
                request.tenantId(), request.subjectPrincipalPublicId(),
                current.decisionRevision(), current.contextKey(),
                current.selectedScopeKey(), EXPLORER_PURPOSE, populationDigest,
                authorityRevision("field-policy", current.policyRevision()),
                authorityRevision("authorization", current.authRevision()),
                numericRevision("entitlement", current.authRevision()),
                projectionRevision(), freshUntil, Map.copyOf(allowed)));
    }

    @Override
    public Optional<CurrentExplorerReceiptAuthority> resolveExplorerOrigin(
            AuthorityRequest request,
            UUID receiptId,
            HrisExplorerRepository.ReceiptAuthority stored) {
        if (!rules.validRequest(request) || receiptId == null || stored == null
                || !RECEIPT_PURPOSE.equals(request.requestPurposeCode())
                || !RECEIPT_ROUTE.equals(request.trustedRouteContractKey())
                || !request.subjectPrincipalPublicId().equals(stored.subjectPrincipalPublicId())
                || !rules.explorerOrigin(stored) || !identityMatches(request)) {
            return Optional.empty();
        }
        RouteProof origin = resolveRoute(
                request, UTILITY_SURFACE, stored.originRouteContractKey(),
                stored.originContextKey(), stored.originContextScopeKey(), null);
        if (!origin.allowed() || !origin.peopleIntersected()
                || !rules.hasCapability(origin, SHELL_CAPABILITY)
                || !stored.originDecisionRevision().equals(origin.decisionRevision())
                || authorityRevision("field-policy", origin.policyRevision())
                != stored.authorityFieldPolicyRevision()
                || authorityRevision("authorization", origin.authRevision())
                != stored.authorityAuthorizationRevision()) {
            return Optional.empty();
        }
        String authorityDigest = HrisExplorerAuthoritySeals.populationScopeDigest(
                origin.selectedScopeKey());
        if (!authorityDigest.equals(stored.authorityPopulationScopeDigest())) {
            return Optional.empty();
        }
        return Optional.of(new CurrentExplorerReceiptAuthority(
                request.tenantId(), request.subjectPrincipalPublicId(),
                stored.originatingAction(), authorityDigest,
                stored.authorityFieldPolicyRevision(), stored.purposeCode(),
                stored.authorityAuthorizationRevision(), stored.originRouteContractKey(),
                stored.originContextKey(), stored.originContextScopeKey(),
                origin.decisionRevision(), origin.freshUntil()));
    }

    @Override
    public Optional<ForwardedReceiptAuthority> resolveForwardedReceipt(
            AuthorityRequest request) {
        if (!rules.validRequest(request)
                || !RECEIPT_PURPOSE.equals(request.requestPurposeCode())
                || !RECEIPT_ROUTE.equals(request.trustedRouteContractKey())
                || !rules.platformReceiptScope(request).equals(request.contextScopeKey())
                || !identityMatches(request)) {
            return Optional.empty();
        }
        RouteProof receipt = resolveRoute(
                request, UTILITY_SURFACE, RECEIPT_ROUTE,
                request.contextKey(), request.contextScopeKey(), request.revalidateAt());
        if (!receipt.allowed() || receipt.peopleIntersected()
                || !rules.hasExactPlatformReceiptGrant(receipt)
                || !request.currentDecisionRevision().equals(receipt.decisionRevision())) {
            return Optional.empty();
        }
        return Optional.of(new ForwardedReceiptAuthority(
                request.tenantId(), request.subjectPrincipalPublicId(),
                receipt.contextKey(), receipt.selectedScopeKey(),
                receipt.decisionRevision(), receipt.freshUntil()));
    }

    @Override
    public Optional<CurrentReceiptAuthority> resolve(
            AuthorityRequest request,
            UUID receiptId,
            HrisExplorerRepository.ReceiptAuthority stored) {
        Optional<ForwardedReceiptAuthority> forwarded = resolveForwardedReceipt(request);
        if (forwarded.isEmpty() || receiptId == null || stored == null
                || !request.subjectPrincipalPublicId().equals(stored.subjectPrincipalPublicId())
                || !rules.platformOrigin(stored)) {
            return Optional.empty();
        }
        return resolvePlatformOrigin(request, stored, forwarded.get());
    }

    @Override
    public Optional<CurrentReceiptAuthority> resolveAfterExplorerForwarded(
            AuthorityRequest request,
            AuthoritySnapshot explorerAuthority,
            UUID receiptId,
            HrisExplorerRepository.ReceiptAuthority stored) {
        if (!rules.validRequest(request) || explorerAuthority == null
                || receiptId == null || stored == null
                || !RECEIPT_PURPOSE.equals(request.requestPurposeCode())
                || !RECEIPT_ROUTE.equals(request.trustedRouteContractKey())
                || !request.subjectPrincipalPublicId().equals(stored.subjectPrincipalPublicId())
                || !rules.platformOrigin(stored)
                || explorerAuthority.tenantId() != request.tenantId()
                || !request.subjectPrincipalPublicId().equals(
                explorerAuthority.subjectPrincipalPublicId())
                || !request.contextKey().equals(explorerAuthority.contextKey())
                || !request.contextScopeKey().equals(
                explorerAuthority.contextScopeKey())
                || !request.currentDecisionRevision().equals(
                explorerAuthority.currentDecisionRevision())
                || !request.contextScopeKey().matches("^hcm-scope-[a-f0-9]{40}$")
                || explorerAuthority.freshUntil() == null
                || !explorerAuthority.freshUntil().isAfter(rules.now())
                || !identityMatches(request)) {
            return Optional.empty();
        }
        String selector = rules.platformReceiptScope(request);
        RouteProof receipt = resolveRoute(
                request, UTILITY_SURFACE, RECEIPT_ROUTE,
                null, selector, null);
        if (!receipt.allowed() || receipt.peopleIntersected()
                || !rules.hasExactPlatformReceiptGrant(receipt)
                || !selector.equals(receipt.selectedScopeKey())) {
            return Optional.empty();
        }
        ForwardedReceiptAuthority internal = new ForwardedReceiptAuthority(
                request.tenantId(), request.subjectPrincipalPublicId(),
                receipt.contextKey(), receipt.selectedScopeKey(),
                receipt.decisionRevision(), receipt.freshUntil());
        return resolvePlatformOrigin(request, stored, internal);
    }

    private Optional<CurrentReceiptAuthority> resolvePlatformOrigin(
            AuthorityRequest request,
            HrisExplorerRepository.ReceiptAuthority stored,
            ForwardedReceiptAuthority platformReceipt) {
        RouteProof origin = resolveRoute(
                request, rules.originSurface(stored.originRouteContractKey()),
                stored.originRouteContractKey(), stored.originContextKey(),
                stored.originContextScopeKey(), null);
        if (!origin.allowed() || !origin.peopleIntersected()
                || (CONTROLLED_TRANSFER_ACTION.equals(stored.originatingAction())
                && !rules.hasCapability(origin, CONTROLLED_TRANSFER_CREATE_CAPABILITY))
                || !stored.originDecisionRevision().equals(origin.decisionRevision())
                || authorityRevision("field-policy", origin.policyRevision())
                != stored.authorityFieldPolicyRevision()
                || authorityRevision("authorization", origin.authRevision())
                != stored.authorityAuthorizationRevision()) {
            return Optional.empty();
        }
        String authorityDigest = HrisExplorerAuthoritySeals.populationScopeDigest(
                origin.selectedScopeKey());
        if (!authorityDigest.equals(stored.authorityPopulationScopeDigest())) {
            return Optional.empty();
        }
        return Optional.of(new CurrentReceiptAuthority(
                request.tenantId(), request.subjectPrincipalPublicId(),
                stored.originatingAction(), authorityDigest,
                stored.authorityFieldPolicyRevision(), stored.purposeCode(),
                stored.authorityAuthorizationRevision(), stored.originRouteContractKey(),
                stored.originContextKey(), stored.originContextScopeKey(),
                origin.decisionRevision(), platformReceipt.contextKey(),
                platformReceipt.contextScopeKey(), platformReceipt.decisionRevision(),
                rules.earliest(platformReceipt.freshUntil(), origin.freshUntil())));
    }

    private RouteProof resolveRoute(
            AuthorityRequest request,
            String surfaceKey,
            String routeContractKey,
            String expectedContextKey,
            String expectedScopeKey,
            OffsetDateTime forwardedFreshUntil) {
        AuthorityResult result = evaluate(
                request, surfaceKey, routeContractKey, expectedContextKey, expectedScopeKey);
        if (!rules.validTerminal(result)) {
            throw AuthHrisExplorerAuthorityRules.unavailable("Auth returned malformed Explorer authority");
        }
        if (!"ALLOWED".equals(result.decision())) {
            return RouteProof.denied(
                    result.decision(), result.authRevision(), result.policyRevision());
        }
        if (!rules.validAllowed(result, request, surfaceKey, expectedContextKey)) {
            throw AuthHrisExplorerAuthorityRules.unavailable("Auth returned incomplete Explorer authority");
        }
        if (Boolean.TRUE.equals(result.requiresProductEligibility())) {
            EligibilityResult eligibility = evaluatePeople(request, result, expectedScopeKey);
            if (!rules.validEligibility(eligibility, request, result)) {
                throw AuthHrisExplorerAuthorityRules.unavailable("People returned malformed Explorer authority");
            }
            if ("AUTHORITY_UNAVAILABLE".equals(eligibility.decision())) {
                throw AuthHrisExplorerAuthorityRules.unavailable("People Explorer authority is unavailable");
            }
            if (!"ALLOWED".equals(eligibility.decision())) {
                return RouteProof.denied(
                        "PEOPLE_" + eligibility.decision(), result.authRevision(),
                        result.policyRevision(), eligibility.productRelationshipRevision(),
                        eligibility.targetPopulationRevision());
            }
            Intersection intersection = intersect(result, eligibility, expectedScopeKey);
            OffsetDateTime freshUntil = rules.authorityFreshness(
                    forwardedFreshUntil, result, eligibility, intersection.selectedScope());
            String revision = decisionRevision(
                    request.tenantId(), request.actorId(), request.activeAccessMode(),
                    result.authRevision(), result.policyRevision(),
                    eligibility.productRelationshipRevision(),
                    eligibility.targetPopulationRevision(), surfaceKey,
                    routeContractKey, result.routeGrantRef());
            return RouteProof.allowed(
                    result, intersection.grants(), intersection.scopes(),
                    intersection.selectedScope().key(),
                    eligibility.productRelationshipRevision(),
                    eligibility.targetPopulationRevision(), revision, freshUntil, true);
        }
        EffectiveScope selected = rules.selectRawScope(result.scopes(), expectedScopeKey);
        if (selected == null) throw AuthHrisExplorerAuthorityRules.unavailable("Auth scope selection is unavailable");
        OffsetDateTime freshUntil = rules.authorityFreshness(
                forwardedFreshUntil, result, null, selected);
        String revision = decisionRevision(
                request.tenantId(), request.actorId(), request.activeAccessMode(),
                result.authRevision(), result.policyRevision(), null, null,
                surfaceKey, routeContractKey, result.routeGrantRef());
        return RouteProof.allowed(
                result, result.effectiveGrants(), result.scopes(), selected.key(),
                null, null, revision, freshUntil, false);
    }

    Intersection intersect(
            AuthorityResult authority,
            EligibilityResult eligibility,
            String expectedScopeKey) {
        Map<String, EffectiveScope> rawByKey;
        Map<String, EligibleScope> eligibleBySource;
        try {
            rawByKey = authority.scopes().stream().collect(Collectors.toUnmodifiableMap(
                    EffectiveScope::key, Function.identity()));
            eligibleBySource = eligibility.scopes().stream().collect(
                    Collectors.toUnmodifiableMap(EligibleScope::sourceScopeKey,
                            Function.identity()));
        } catch (RuntimeException exception) {
            throw AuthHrisExplorerAuthorityRules.unavailable("Explorer scope evidence is ambiguous", exception);
        }
        List<EffectiveGrant> grants = new ArrayList<>();
        for (EffectiveGrant grant : authority.effectiveGrants()) {
            List<String> rebound = grant.scopeKeys().stream()
                    .map(eligibleBySource::get).filter(Objects::nonNull)
                    .map(EligibleScope::key).distinct().toList();
            if (!rebound.isEmpty()) grants.add(grant.withScopeKeys(rebound));
        }
        if (grants.isEmpty()) throw AuthHrisExplorerAuthorityRules.unavailable("People removed every Explorer grant");
        Set<String> used = grants.stream().flatMap(grant -> grant.scopeKeys().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, DerivedAlias> derived = new LinkedHashMap<>();
        for (EligibleScope eligible : eligibility.scopes()) {
            EffectiveScope raw = rawByKey.get(eligible.sourceScopeKey());
            if (raw == null || !raw.kind().equals(eligible.kind())
                    || !HcmEligibilityScopeKey.isCanonical(eligible.key())) {
                throw AuthHrisExplorerAuthorityRules.unavailable("People scope is not current canonical evidence");
            }
            if (!used.contains(eligible.key())) continue;
            EffectiveScope value = new EffectiveScope(
                    eligible.key(), eligible.kind(), eligible.displayName(),
                    eligible.isDefault(), Boolean.TRUE.equals(raw.readOnly())
                    || Boolean.TRUE.equals(eligible.readOnly()),
                    rules.earliestNullable(raw.validUntil(), eligible.validUntil()));
            DerivedAlias alias = new DerivedAlias(
                    value, Boolean.TRUE.equals(eligible.readOnly()));
            DerivedAlias previous = derived.putIfAbsent(value.key(), alias);
            if (previous != null) {
                derived.put(value.key(), mergeAlias(previous, alias));
            }
        }
        List<EffectiveScope> scopes = normalizeReadOnly(
                derived.values().stream().map(DerivedAlias::scope).toList(), grants);
        if (derived.isEmpty() || grants.stream().anyMatch(grant ->
                grant.scopeKeys().isEmpty()
                        || !scopes.stream().map(EffectiveScope::key).toList()
                        .containsAll(grant.scopeKeys()))) {
            throw AuthHrisExplorerAuthorityRules.unavailable("Explorer grant and scope intersection is not closed");
        }
        EffectiveScope selected = rules.selectDerivedScope(
                scopes, expectedScopeKey);
        if (selected == null) {
            throw AuthHrisExplorerAuthorityRules.unavailable("Current People scope selection is unavailable");
        }
        return new Intersection(List.copyOf(grants), scopes, selected);
    }

    private DerivedAlias mergeAlias(DerivedAlias left, DerivedAlias right) {
        EffectiveScope leftScope = left.scope();
        EffectiveScope rightScope = right.scope();
        if (!leftScope.key().equals(rightScope.key())
                || !leftScope.kind().equals(rightScope.kind())
                || !Objects.equals(leftScope.displayName(), rightScope.displayName())
                || !leftScope.isDefault().equals(rightScope.isDefault())
                || left.ownerReadOnly() != right.ownerReadOnly()) {
            throw AuthHrisExplorerAuthorityRules.unavailable("People scope aliases disagree");
        }
        return new DerivedAlias(new EffectiveScope(
                leftScope.key(), leftScope.kind(), leftScope.displayName(),
                leftScope.isDefault(),
                Boolean.TRUE.equals(leftScope.readOnly())
                        && Boolean.TRUE.equals(rightScope.readOnly()),
                rules.earliestNullable(leftScope.validUntil(), rightScope.validUntil())),
                left.ownerReadOnly());
    }

    private List<EffectiveScope> normalizeReadOnly(
            List<EffectiveScope> scopes, List<EffectiveGrant> grants) {
        return scopes.stream().map(scope -> new EffectiveScope(
                scope.key(), scope.kind(), scope.displayName(), scope.isDefault(),
                Boolean.TRUE.equals(scope.readOnly())
                        || !rules.hasActiveMutationGrant(grants, scope.key()),
                scope.validUntil())).toList();
    }

    private EligibilityResult evaluatePeople(
            AuthorityRequest request, AuthorityResult authority, String contextScopeKey) {
        return authorityClient.evaluatePeople(request, () -> {
            List<CandidateScope> candidates = authority.scopes().stream()
                    .map(scope -> new CandidateScope(scope.key(), scope.kind())).toList();
            return new EligibilityRequest(request.tenantId(), request.actorId(), PRODUCT,
                    authority.surfaceKey(), request.activeAccessMode(), rules.now(),
                    candidates, contextScopeKey);
        }, EligibilityResult.class);
    }

    private AuthorityResult evaluate(AuthorityRequest request, String surfaceKey,
            String routeContractKey, String contextKey, String contextScopeKey) {
        return authorityClient.evaluate(() -> new EvaluateRequest(request.tenantId(), request.actorId(),
                PRODUCT, surfaceKey, request.activeAccessMode(), routeContractKey,
                contextKey, contextScopeKey, null, null, List.of()), AuthorityResult.class);
    }

    private boolean identityMatches(AuthorityRequest request) {
        return authorityClient.identityMatches(request, IdentitySubject.class, subject ->
                subject.tenantId() != null && subject.tenantId() == request.tenantId()
                        && subject.userId() != null && subject.userId() == request.actorId()
                        && request.subjectPrincipalPublicId().equals(subject.personPublicId())
                        && "ACTIVE".equals(subject.status())
                        && "TENANT".equals(subject.identityPlane()));
    }

    static String decisionRevision(long tenantId, long actorId, String activeAccessMode,
            String authRevision, String policyRevision, String relationshipRevision,
            String populationRevision, String surfaceKey, String routeContractKey,
            String routeGrantRef) {
        return AuthHrisExplorerAuthorityRules.decisionRevision(tenantId, actorId, activeAccessMode,
                authRevision, policyRevision, relationshipRevision, populationRevision,
                surfaceKey, routeContractKey, routeGrantRef);
    }

    static long numericRevision(String domain, String sourceRevision) {
        return AuthHrisExplorerAuthorityRules.numericRevision(domain, sourceRevision);
    }

    static long authorityRevision(String domain, String sourceRevision) {
        return AuthHrisExplorerAuthorityRules.authorityRevision(domain, sourceRevision);
    }

    static long projectionRevision() {
        return AuthHrisExplorerAuthorityRules.projectionRevision();
    }

    static boolean exactPlatformOriginPurpose(String action, String purposeCode) {
        return AuthHrisExplorerAuthorityRules.exactPlatformOriginPurpose(action, purposeCode);
    }

    static boolean exactPlatformOrigin(String action, String routeContractKey) {
        return AuthHrisExplorerAuthorityRules.exactPlatformOrigin(action, routeContractKey);
    }

    static boolean exactExplorerOrigin(String action, String routeContractKey) {
        return AuthHrisExplorerAuthorityRules.exactExplorerOrigin(action, routeContractKey);
    }

    private record EvaluateRequest(
            long tenantId, long actorId, String productKey, String surfaceKey,
            String activeAccessMode, String routeContractKey, String contextKey,
            String contextScopeKey, String supportSessionRef, String supportRevision,
            List<String> supportScopes) {
    }

    private record EligibilityRequest(
            long tenantId, long actorId, String productKey, String surfaceKey,
            String activeAccessMode, OffsetDateTime evaluatedAt,
            List<CandidateScope> candidateScopes, String contextScopeKey) {
    }

    private record CandidateScope(String key, String kind) {
    }

    record AuthorityResult(
            String decision, String reasonCode, String authRevision, String policyRevision,
            String contextKey, String productKey, String surfaceKey, String plane,
            String accessMode, String accessSource, String appResourceKey,
            List<EffectiveGrant> effectiveGrants, List<EffectiveScope> scopes,
            String routeGrantRef, Boolean effectiveReadOnly,
            Boolean requiresProductEligibility, OffsetDateTime validUntil,
            OffsetDateTime expiredAt, String requiredAssurance, String requestPolicyRef,
            OffsetDateTime revalidateAt, String evidenceRef) implements AuthHrisExplorerAuthorityRules.AuthorityView {
    }

    record EffectiveGrant(
            String grantKind, String capabilityContractKey, String resolvedCapabilityCode,
            String accessPolicyKey, String policyDecisionRef, String authorityMode,
            List<String> predicatePolicyKeys, String responsibilityRequirement,
            Responsibility responsibility, List<String> scopeKeys,
            Boolean requiresProductEntitlement, Boolean readOnly,
            String activationState, OffsetDateTime validUntil) implements AuthHrisExplorerAuthorityRules.GrantView {

        EffectiveGrant withScopeKeys(List<String> values) {
            return new EffectiveGrant(
                    grantKind, capabilityContractKey, resolvedCapabilityCode,
                    accessPolicyKey, policyDecisionRef, authorityMode,
                    predicatePolicyKeys, responsibilityRequirement, responsibility,
                    List.copyOf(values), requiresProductEntitlement, readOnly,
                    activationState, validUntil);
        }
    }

    record Responsibility(String code, String resourceSetKey) {
    }

    record EffectiveScope(
            String key, String kind, String displayName, Boolean isDefault,
            Boolean readOnly, OffsetDateTime validUntil) implements AuthHrisExplorerAuthorityRules.ScopeView {
    }

    record EligibilityResult(
            String decision, String reasonCode, String productRelationshipRevision,
            String targetPopulationRevision, List<EligibleScope> scopes,
            OffsetDateTime revalidateAt, String evidenceRef) implements AuthHrisExplorerAuthorityRules.EligibilityView {
    }

    record EligibleScope(
            String sourceScopeKey, String key, String kind, String displayName,
            Boolean isDefault, Boolean readOnly, OffsetDateTime validUntil) implements AuthHrisExplorerAuthorityRules.EligibleView {
    }

    private record IdentitySubject(
            Long tenantId, Long userId, UUID publicId, UUID personPublicId,
            String displayName, String email, String jobTitle, String department,
            String status, String identityPlane, List<String> roles,
            List<UUID> groupRefs, List<String> permissionKeys) {
    }

    record Intersection(
            List<EffectiveGrant> grants, List<EffectiveScope> scopes,
            EffectiveScope selectedScope) {
    }

    private record DerivedAlias(EffectiveScope scope, boolean ownerReadOnly) {
    }

    private record RouteProof(
            boolean allowed, String decision, String authRevision, String policyRevision,
            String contextKey, List<EffectiveGrant> grants, List<EffectiveScope> scopes,
            String selectedScopeKey, String relationshipRevision,
            String populationRevision, String decisionRevision,
            OffsetDateTime freshUntil, boolean peopleIntersected) implements AuthHrisExplorerAuthorityRules.ProofView {

        private static RouteProof denied(
                String decision, String authRevision, String policyRevision) {
            return denied(decision, authRevision, policyRevision, null, null);
        }

        private static RouteProof denied(
                String decision, String authRevision, String policyRevision,
                String relationshipRevision, String populationRevision) {
            return new RouteProof(false, decision, authRevision, policyRevision,
                    null, List.of(), List.of(), null, relationshipRevision,
                    populationRevision, null, null,
                    relationshipRevision != null || populationRevision != null);
        }

        private static RouteProof allowed(
                AuthorityResult authority, List<EffectiveGrant> grants,
                List<EffectiveScope> scopes, String selectedScopeKey,
                String relationshipRevision, String populationRevision,
                String decisionRevision, OffsetDateTime freshUntil,
                boolean peopleIntersected) {
            return new RouteProof(true, authority.decision(), authority.authRevision(),
                    authority.policyRevision(), authority.contextKey(), List.copyOf(grants),
                    List.copyOf(scopes), selectedScopeKey, relationshipRevision,
                    populationRevision, decisionRevision, freshUntil, peopleIntersected);
        }

        private String stabilitySeal() {
            return HrisExplorerAuthoritySeals.sha256Hex(String.join("\n",
                    Objects.toString(decision, ""), Objects.toString(authRevision, ""),
                    Objects.toString(policyRevision, ""),
                    Objects.toString(relationshipRevision, ""),
                    Objects.toString(populationRevision, ""),
                    Objects.toString(selectedScopeKey, ""),
                    Objects.toString(decisionRevision, "")));
        }
    }
}
