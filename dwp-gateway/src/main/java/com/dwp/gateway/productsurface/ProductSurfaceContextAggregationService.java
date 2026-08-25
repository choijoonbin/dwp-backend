package com.dwp.gateway.productsurface;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dwp.gateway.productsurface.ProductSurfaceContextAggregationSupport.*;

@Service
public class ProductSurfaceContextAggregationService {

    private static final String CONTRACT_VERSION = "1";
    private static final String PRODUCT_NOT_REGISTERED = "PRODUCT_NOT_REGISTERED";

    private final ProductSurfaceAuthorityClient authorityClient;
    private final GovernedRouteAuthorityClient governedClient;
    private final ProductSurfaceEligibilityClient eligibilityClient;
    private final FeatureRolloutEvaluationClient rolloutClient;
    private final ObjectProvider<ProductSurfaceCandidateCatalog> candidateCatalogs;
    private final Clock clock;

    @Autowired
    public ProductSurfaceContextAggregationService(
            ProductSurfaceAuthorityClient authorityClient,
            GovernedRouteAuthorityClient governedClient,
            ProductSurfaceEligibilityClient eligibilityClient,
            FeatureRolloutEvaluationClient rolloutClient,
            ObjectProvider<ProductSurfaceCandidateCatalog> candidateCatalogs) {
        this(authorityClient, governedClient, eligibilityClient, rolloutClient, candidateCatalogs,
                Clock.systemUTC());
    }

    ProductSurfaceContextAggregationService(
            ProductSurfaceAuthorityClient authorityClient,
            GovernedRouteAuthorityClient governedClient,
            ProductSurfaceEligibilityClient eligibilityClient,
            FeatureRolloutEvaluationClient rolloutClient,
            ObjectProvider<ProductSurfaceCandidateCatalog> candidateCatalogs,
            Clock clock) {
        this.authorityClient = authorityClient;
        this.governedClient = governedClient;
        this.eligibilityClient = eligibilityClient;
        this.rolloutClient = rolloutClient;
        this.candidateCatalogs = candidateCatalogs;
        this.clock = clock;
    }

    public Mono<ProductSurfaceContextDtos.ContextListData> listContexts(
            ProductSurfaceContextDtos.RequestContext requestContext) {
        return Mono.fromCallable(this::catalog)
                .flatMap(catalog -> {
                    List<String> products = catalog.rolloutProductKeys();
                    var metadata = new FeatureRolloutEvaluationClient.RequestMetadata(
                            requestContext.correlationId(),
                            requestContext.traceParent(),
                            requestContext.traceState());
                    return rolloutClient.evaluateProducts(
                                    requestContext.tenantId(), products, metadata)
                            .switchIfEmpty(Mono.error(new AuthorityUnavailableException()))
                            .map(values -> requireRollouts(products, values))
                            .flatMap(rollouts -> resolveListContexts(
                                    requestContext, catalog.candidates(), rollouts));
                })
                .onErrorMap(
                        FeatureRolloutEvaluationClient.InvalidRolloutStateException.class,
                        ignored -> new AuthorityUnavailableException())
                .onErrorMap(
                        FeatureRolloutEvaluationClient.RolloutAuthorityUnavailableException.class,
                        ignored -> new AuthorityUnavailableException());
    }

    private Mono<ProductSurfaceContextDtos.ContextListData> resolveListContexts(
            ProductSurfaceContextDtos.RequestContext requestContext,
            List<ProductSurfaceContextDtos.ProductCandidate> candidates,
            List<ProductSurfaceContextDtos.ProductRollout> rollouts) {
        Set<String> evaluatedProducts = rollouts.stream()
                .filter(value -> !"000".equals(value.state()))
                .map(ProductSurfaceContextDtos.ProductRollout::productKey)
                .collect(Collectors.toUnmodifiableSet());
        if (evaluatedProducts.isEmpty()) {
            return Mono.just(contextList(
                    requestContext, List.of(), rollouts, Set.of(), Set.of()));
        }
        Set<String> candidateProducts = candidates.stream()
                .map(ProductSurfaceContextDtos.ProductCandidate::productKey)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> missingCandidateProducts = evaluatedProducts.stream()
                .filter(product -> !candidateProducts.contains(product))
                .collect(Collectors.toUnmodifiableSet());
        Set<String> nonParticipatingProducts = rollouts.stream()
                .filter(value -> missingCandidateProducts.contains(value.productKey()))
                .filter(value -> !value.flags().capabilityEnforcement()
                        && !value.flags().surfaceUi())
                .map(ProductSurfaceContextDtos.ProductRollout::productKey)
                .collect(Collectors.toUnmodifiableSet());
        boolean missingEnforcedProductAuthority = rollouts.stream()
                .anyMatch(value -> missingCandidateProducts.contains(value.productKey())
                        && (value.flags().capabilityEnforcement()
                            || value.flags().surfaceUi()));
        if (missingEnforcedProductAuthority) throw new AuthorityUnavailableException();
        return Flux.fromIterable(candidates)
                .filter(candidate -> evaluatedProducts.contains(candidate.productKey()))
                .concatMap(candidate -> resolveCandidate(
                                requestContext, candidate, null, null, null)
                        .map(value -> CandidateResolution.available(candidate, value))
                        .onErrorResume(
                                AuthorityUnavailableException.class,
                                ignored -> Mono.just(CandidateResolution.unavailable(candidate))))
                .collectList()
                .map(results -> {
                    Set<String> activeBundleAbsentProducts = results.stream()
                            .collect(Collectors.groupingBy(
                                    value -> value.candidate().productKey()))
                            .entrySet().stream()
                            .filter(entry -> entry.getValue().stream().allMatch(value ->
                                    value.resolution() != null
                                            && value.resolution()
                                                    .authSurfaceProductNotRegistered()))
                            .map(Map.Entry::getKey)
                            .collect(Collectors.toUnmodifiableSet());
                    boolean missingActiveEnforcedProductAuthority = rollouts.stream()
                            .anyMatch(value -> activeBundleAbsentProducts.contains(
                                            value.productKey())
                                    && (value.flags().capabilityEnforcement()
                                        || value.flags().surfaceUi()));
                    if (missingActiveEnforcedProductAuthority) {
                        throw new AuthorityUnavailableException();
                    }
                    Set<String> resolutionUnavailableProducts = results.stream()
                            .filter(value -> value.resolution() == null)
                            .map(value -> value.candidate().productKey())
                            .collect(Collectors.toUnmodifiableSet());
                    Set<String> unavailableProducts = new LinkedHashSet<>(
                            resolutionUnavailableProducts);
                    boolean enforcedUnavailable = rollouts.stream()
                            .anyMatch(value -> value.flags().capabilityEnforcement()
                                    && unavailableProducts.contains(value.productKey()));
                    if (enforcedUnavailable) throw new AuthorityUnavailableException();
                    List<Resolution> resolutions = results.stream()
                            .filter(value -> value.resolution() != null)
                            .filter(value -> !unavailableProducts.contains(
                                    value.candidate().productKey()))
                            .map(CandidateResolution::resolution)
                            .toList();
                    Set<String> effectiveNonParticipatingProducts = new LinkedHashSet<>(
                            nonParticipatingProducts);
                    effectiveNonParticipatingProducts.addAll(activeBundleAbsentProducts);
                    return contextList(
                            requestContext, resolutions, rollouts,
                            Set.copyOf(unavailableProducts),
                            Set.copyOf(effectiveNonParticipatingProducts));
                });
    }

    private ProductSurfaceContextDtos.ContextListData contextList(
            ProductSurfaceContextDtos.RequestContext requestContext,
            List<Resolution> resolutions,
            List<ProductSurfaceContextDtos.ProductRollout> rollouts,
            Set<String> unavailableProducts,
            Set<String> nonParticipatingProducts) {
        ProductSurfaceContextDtos.SourceRevisions sourceRevisions =
                aggregateRevisions(requestContext, resolutions);
        List<ProductSurfaceContextDtos.EffectiveContext> contexts = resolutions.stream()
                .map(Resolution::context)
                .filter(Objects::nonNull)
                .peek(this::requireValidDefaultScopes)
                .sorted(Comparator.comparing(
                        ProductSurfaceContextDtos.EffectiveContext::productKey)
                        .thenComparing(ProductSurfaceContextDtos.EffectiveContext::surfaceKey))
                .toList();
        List<ProductSurfaceContextDtos.ProductRollout> evaluated = rollouts.stream()
                .map(value -> withAuthorityStatus(
                        value, unavailableProducts, nonParticipatingProducts))
                .toList();
        return new ProductSurfaceContextDtos.ContextListData(
                CONTRACT_VERSION,
                compositeRevision(requestContext, sourceRevisions, evaluated),
                sourceRevisions,
                requestContext.activeAccessMode(),
                OffsetDateTime.now(clock),
                contexts,
                evaluated);
    }

    private ProductSurfaceContextDtos.ProductRollout withAuthorityStatus(
            ProductSurfaceContextDtos.ProductRollout value,
            Set<String> unavailableProducts,
            Set<String> nonParticipatingProducts) {
        ProductSurfaceContextDtos.AuthorityStatus status = "000".equals(value.state())
                || nonParticipatingProducts.contains(value.productKey())
                ? ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED
                : unavailableProducts.contains(value.productKey())
                        ? ProductSurfaceContextDtos.AuthorityStatus.UNAVAILABLE
                        : ProductSurfaceContextDtos.AuthorityStatus.AVAILABLE;
        return new ProductSurfaceContextDtos.ProductRollout(
                value.productKey(), value.state(), value.flags(), value.cohort(),
                value.opaqueRevision(), status);
    }

    private List<ProductSurfaceContextDtos.ProductRollout> requireRollouts(
            List<String> products,
            List<ProductSurfaceContextDtos.ProductRollout> values) {
        if (values == null || values.size() != products.size()) {
            throw new AuthorityUnavailableException();
        }
        Map<String, ProductSurfaceContextDtos.ProductRollout> byProduct = values.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        ProductSurfaceContextDtos.ProductRollout::productKey,
                        Function.identity(),
                        (left, right) -> {
                            throw new AuthorityUnavailableException();
                        }));
        if (!byProduct.keySet().equals(Set.copyOf(products))) {
            throw new AuthorityUnavailableException();
        }
        List<ProductSurfaceContextDtos.ProductRollout> ordered = products.stream()
                .map(byProduct::get).peek(value -> {
                    if (value.flags() == null
                            || !Set.of("000", "100", "110", "111")
                                    .contains(value.state())) {
                        throw new AuthorityUnavailableException();
                    }
                    String state = (value.flags().contextShadow() ? "1" : "0")
                            + (value.flags().capabilityEnforcement() ? "1" : "0")
                            + (value.flags().surfaceUi() ? "1" : "0");
                    if (!state.equals(value.state())
                            || value.authorityStatus()
                                    != ProductSurfaceContextDtos.AuthorityStatus.NOT_EVALUATED
                            || blank(value.cohort()) || blank(value.opaqueRevision())) {
                        throw new AuthorityUnavailableException();
                    }
                }).toList();
        ProductSurfaceContextDtos.RolloutFlags shared = ordered.getFirst().flags();
        if (ordered.stream().map(ProductSurfaceContextDtos.ProductRollout::flags)
                .anyMatch(flags -> flags.contextShadow() != shared.contextShadow())) {
            throw new AuthorityUnavailableException();
        }
        return ordered;
    }

    public Mono<ProductSurfaceContextDtos.ProductEvaluationData> evaluateProduct(
            ProductSurfaceContextDtos.RequestContext requestContext,
            ProductSurfaceContextDtos.ProductEvaluationRequest request) {
        ProductSurfaceContextDtos.ProductCandidate candidate =
                new ProductSurfaceContextDtos.ProductCandidate(
                        request.subject().productKey(), request.subject().surfaceKey());
        return resolveCandidate(
                requestContext,
                candidate,
                request.routeContractKey(),
                request.contextKey(),
                request.contextScopeKey())
                .map(resolution -> productEvaluation(requestContext, request, resolution));
    }

    /** Internal-only direct evaluation including trusted context/scope for owner forwarding. */
    public Mono<TrustedProductEvaluation> evaluateProductTrusted(
            ProductSurfaceContextDtos.RequestContext requestContext,
            ProductSurfaceContextDtos.ProductEvaluationRequest request) {
        ProductSurfaceContextDtos.ProductCandidate candidate =
                new ProductSurfaceContextDtos.ProductCandidate(
                        request.subject().productKey(), request.subject().surfaceKey());
        return resolveCandidate(
                requestContext,
                candidate,
                request.routeContractKey(),
                request.contextKey(),
                request.contextScopeKey())
                .map(resolution -> {
                    ProductSurfaceContextDtos.ProductEvaluationData data =
                            productEvaluation(requestContext, request, resolution);
                    ProductSurfaceContextDtos.EffectiveScope scope = data.scope();
                    String contextKey = data.context() == null
                            ? null : data.context().contextKey();
                    if (data.decision() == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED) {
                        scope = selectScope(resolution.scopes(), request.contextScopeKey());
                        contextKey = resolution.authority().contextKey();
                    }
                    return new TrustedProductEvaluation(
                            data,
                            contextKey,
                            scope,
                            resolution.authRouteProductNotRegistered());
                });
    }

    public Mono<ProductSurfaceContextDtos.GovernedEvaluationData> evaluateGoverned(
            ProductSurfaceContextDtos.RequestContext requestContext,
            ProductSurfaceContextDtos.GovernedEvaluationRequest request) {
        return governedClient.evaluate(requestContext, request)
                .switchIfEmpty(Mono.error(new AuthorityUnavailableException()))
                .flatMap(result -> result.decision()
                        == ProductSurfaceContextDtos.GovernedDecision.AUTHORITY_UNAVAILABLE
                        ? Mono.error(new AuthorityUnavailableException())
                        : validGoverned(result, requestContext, request)
                                ? Mono.just(result)
                                : Mono.error(new AuthorityUnavailableException()))
                .map(result -> {
                    ProductSurfaceContextDtos.SourceRevisions revisions =
                            new ProductSurfaceContextDtos.SourceRevisions(
                                    result.authRevision(),
                                    result.policyRevision(),
                                    null,
                                    null,
                                    requestContext.supportRevision());
                    String composite = compositeRevision(requestContext, revisions);
                    ProductSurfaceContextDtos.GovernedRouteAccessContext allowedContext =
                            result.decision() == ProductSurfaceContextDtos.GovernedDecision.ALLOWED
                                    ? new ProductSurfaceContextDtos.GovernedRouteAccessContext(
                                            result.contextKey(),
                                            result.navigationContextId(),
                                            result.accessSource(),
                                            result.accessMode(),
                                            result.routeGrantRef(),
                                            result.effectiveReadOnly(),
                                            composite,
                                            result.revalidateAt())
                                    : null;
                    return new ProductSurfaceContextDtos.GovernedEvaluationData(
                            result.decision(),
                            result.reasonCode(),
                            composite,
                            allowedContext,
                            result.validUntil(),
                            result.expiredAt(),
                            result.requiredAssurance(),
                            result.requestPolicyRef());
                });
    }

    private Mono<Resolution> resolveCandidate(
            ProductSurfaceContextDtos.RequestContext requestContext,
            ProductSurfaceContextDtos.ProductCandidate candidate,
            String routeContractKey,
            String contextKey,
            String contextScopeKey) {
        return authorityClient.evaluate(
                        requestContext,
                        candidate.productKey(),
                        candidate.surfaceKey(),
                        routeContractKey,
                        contextKey,
                        contextScopeKey)
                .switchIfEmpty(Mono.error(new AuthorityUnavailableException()))
                .flatMap(authority -> {
                    if (authority.decision()
                            == ProductSurfaceContextDtos.Decision.AUTHORITY_UNAVAILABLE) {
                        return Mono.error(new AuthorityUnavailableException());
                    }
                    if (!validAuthority(authority, requestContext, candidate, routeContractKey)) {
                        return Mono.error(new AuthorityUnavailableException());
                    }
                    if (!authority.requiresProductEligibility()) {
                        return Mono.just(resolution(requestContext, authority, null));
                    }
                    return eligibilityClient.evaluate(
                                    requestContext,
                                    authority,
                                    contextScopeKey,
                                    OffsetDateTime.now(clock))
                            .switchIfEmpty(Mono.error(new AuthorityUnavailableException()))
                            .flatMap(eligibility -> {
                                if (eligibility.decision()
                                        == ProductSurfaceContextDtos.Decision.AUTHORITY_UNAVAILABLE
                                        || !validEligibility(eligibility)) {
                                    return Mono.error(new AuthorityUnavailableException());
                                }
                                return Mono.just(resolution(
                                        requestContext, authority, eligibility));
                            });
                });
    }

    private Resolution resolution(
            ProductSurfaceContextDtos.RequestContext requestContext,
            ProductSurfaceContextDtos.AuthorityResult authority,
            ProductSurfaceContextDtos.EligibilityResult eligibility) {
        ProductSurfaceContextDtos.Decision decision = authority.decision();
        String reasonCode = authority.reasonCode();
        if (eligibility != null && eligibility.decision()
                != ProductSurfaceContextDtos.Decision.ALLOWED) {
            decision = eligibility.decision();
            reasonCode = eligibility.reasonCode();
        }
        List<ProductSurfaceContextDtos.EffectiveGrant> grants = authority.effectiveGrants();
        List<ProductSurfaceContextDtos.EffectiveScope> scopes = authority.scopes();
        if (eligibility != null) {
            if (eligibility.decision() == ProductSurfaceContextDtos.Decision.ALLOWED) {
                ProductSurfaceScopeIntersection.Intersection intersection =
                        ProductSurfaceScopeIntersection.intersect(
                        grants, scopes, eligibility.scopes());
                scopes = intersection.scopes();
                grants = intersection.grants();
            } else {
                scopes = List.of();
                grants = List.of();
            }
        }
        scopes = ProductSurfaceScopeIntersection.normalizeReadOnly(scopes, grants);
        if (!ProductSurfaceScopeIntersection.closed(grants, scopes)) {
            throw new AuthorityUnavailableException();
        }
        OffsetDateTime revalidateAt = earliest(
                authority.revalidateAt(),
                eligibility == null ? null : eligibility.revalidateAt());
        ProductSurfaceContextDtos.EffectiveContext context =
                decision == ProductSurfaceContextDtos.Decision.ALLOWED
                        ? new ProductSurfaceContextDtos.EffectiveContext(
                                authority.contextKey(),
                                authority.productKey(),
                                authority.surfaceKey(),
                                authority.plane(),
                                authority.accessMode(),
                                authority.accessSource(),
                                authority.appResourceKey(),
                                grants,
                                scopes,
                                revalidateAt)
                        : null;
        ProductSurfaceContextDtos.SourceRevisions revisions =
                new ProductSurfaceContextDtos.SourceRevisions(
                        authority.authRevision(),
                        authority.policyRevision(),
                        eligibility == null ? null : eligibility.productRelationshipRevision(),
                        eligibility == null ? null : eligibility.targetPopulationRevision(),
                        requestContext.supportRevision());
        return new Resolution(
                decision,
                reasonCode,
                authority,
                context,
                revisions,
                scopes,
                revalidateAt);
    }

    private ProductSurfaceContextDtos.ProductEvaluationData productEvaluation(
            ProductSurfaceContextDtos.RequestContext requestContext,
            ProductSurfaceContextDtos.ProductEvaluationRequest request,
            Resolution resolution) {
        ProductSurfaceContextDtos.Decision decision = resolution.decision();
        String reasonCode = resolution.reasonCode();
        ProductSurfaceContextDtos.EffectiveScope selected = null;
        if (decision == ProductSurfaceContextDtos.Decision.ALLOWED
                || decision == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED) {
            selected = selectScope(resolution.scopes(), request.contextScopeKey());
            if (request.contextScopeKey() != null && selected == null) {
                decision = ProductSurfaceContextDtos.Decision.SCOPE_INVALID;
                reasonCode = "SCOPE_CONTEXT_EXPIRED";
            } else if (selected == null) {
                decision = ProductSurfaceContextDtos.Decision.SCOPE_SELECTION_REQUIRED;
                reasonCode = "SCOPE_SELECTION_REQUIRED";
            }
        }
        String revision = compositeRevision(requestContext, resolution.revisions());
        ProductSurfaceContextDtos.EffectiveContext context =
                decision == ProductSurfaceContextDtos.Decision.ALLOWED
                        ? resolution.context()
                        : null;
        ProductSurfaceContextDtos.AuthorityResult authority = resolution.authority();
        boolean stepUpRequired = decision == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED;
        return new ProductSurfaceContextDtos.ProductEvaluationData(
                decision,
                reasonCode,
                revision,
                context,
                context == null ? null : authority.routeGrantRef(),
                context == null ? null : selected,
                context == null ? null : context.scopes().stream()
                        .allMatch(ProductSurfaceContextDtos.EffectiveScope::readOnly),
                authority.validUntil(),
                authority.expiredAt(),
                stepUpRequired ? authority.requiredAssurance() : null,
                stepUpRequired ? authority.requestPolicyRef() : null,
                resolution.revalidateAt());
    }

    private ProductSurfaceContextDtos.EffectiveScope selectScope(
            List<ProductSurfaceContextDtos.EffectiveScope> scopes,
            String requestedKey) {
        if (requestedKey != null && !requestedKey.isBlank()) {
            return scopes.stream()
                    .filter(scope -> requestedKey.equals(scope.key()))
                    .findFirst()
                    .orElse(null);
        }
        if (scopes.size() == 1) return scopes.getFirst();
        List<ProductSurfaceContextDtos.EffectiveScope> defaults = scopes.stream()
                .filter(ProductSurfaceContextDtos.EffectiveScope::isDefault)
                .toList();
        return defaults.size() == 1 ? defaults.getFirst() : null;
    }

    private void requireValidDefaultScopes(
            ProductSurfaceContextDtos.EffectiveContext context) {
        long defaults = context.scopes().stream()
                .filter(ProductSurfaceContextDtos.EffectiveScope::isDefault)
                .count();
        if (defaults > 1) throw new AuthorityUnavailableException();
    }

    private CatalogProjection catalog() {
        List<ProductSurfaceCandidateCatalog> catalogs = candidateCatalogs.orderedStream().toList();
        if (catalogs.size() != 1) throw new AuthorityUnavailableException();
        ProductSurfaceCandidateCatalog catalog = catalogs.getFirst();
        List<ProductSurfaceContextDtos.ProductCandidate> values = catalog.activeCandidates();
        if (values == null || values.isEmpty()) throw new AuthorityUnavailableException();
        LinkedHashSet<ProductSurfaceContextDtos.ProductCandidate> unique = new LinkedHashSet<>();
        for (ProductSurfaceContextDtos.ProductCandidate value : values) {
            if (value == null || blank(value.productKey()) || blank(value.surfaceKey())
                    || !unique.add(value)) {
                throw new AuthorityUnavailableException();
            }
        }
        List<String> rolloutProducts = catalog.rolloutProductKeys();
        if (rolloutProducts == null || rolloutProducts.isEmpty()
                || rolloutProducts.stream().anyMatch(value -> blank(value))
                || rolloutProducts.size() != new LinkedHashSet<>(rolloutProducts).size()
                || unique.stream().map(ProductSurfaceContextDtos.ProductCandidate::productKey)
                        .anyMatch(product -> !rolloutProducts.contains(product))) {
            throw new AuthorityUnavailableException();
        }
        return new CatalogProjection(
                List.copyOf(unique), rolloutProducts.stream().sorted().toList());
    }

    private record CatalogProjection(
            List<ProductSurfaceContextDtos.ProductCandidate> candidates,
            List<String> rolloutProductKeys) {
    }

    private boolean validAuthority(
            ProductSurfaceContextDtos.AuthorityResult authority,
            ProductSurfaceContextDtos.RequestContext requestContext,
            ProductSurfaceContextDtos.ProductCandidate candidate,
            String routeContractKey) {
        if (authority == null || authority.decision() == null
                || authority.accessMode() != requestContext.activeAccessMode()
                || !candidate.productKey().equals(authority.productKey())
                || !candidate.surfaceKey().equals(authority.surfaceKey())) {
            return false;
        }
        if (blank(authority.authRevision()) || blank(authority.policyRevision())) return false;
        if (authority.decision() != ProductSurfaceContextDtos.Decision.ALLOWED) {
            if (authority.decision() == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED) {
                return authority.accessSource() != null
                        && !blank(authority.appResourceKey())
                        && !authority.effectiveGrants().isEmpty()
                        && !authority.scopes().isEmpty()
                        && ProductSurfaceScopeIntersection.closed(
                                authority.effectiveGrants(), authority.scopes())
                        && !blank(authority.requiredAssurance())
                        && !blank(authority.requestPolicyRef());
            }
            return authority.effectiveGrants().isEmpty() && authority.scopes().isEmpty();
        }
        if (!Set.of("work", "management").contains(authority.plane())) return false;
        long defaults = authority.scopes().stream()
                .filter(ProductSurfaceContextDtos.EffectiveScope::isDefault)
                .count();
        return (authority.scopes().size() != 1 || defaults == 1)
                && !blank(authority.contextKey())
                && authority.accessSource() != null
                && !blank(authority.appResourceKey())
                && !authority.effectiveGrants().isEmpty()
                && !authority.scopes().isEmpty()
                && ProductSurfaceScopeIntersection.closed(
                        authority.effectiveGrants(), authority.scopes())
                && authority.revalidateAt() != null
                && (blank(routeContractKey) || !blank(authority.routeGrantRef()));
    }

    private boolean validGoverned(
            ProductSurfaceContextDtos.GovernedAuthorityResult result,
            ProductSurfaceContextDtos.RequestContext requestContext,
            ProductSurfaceContextDtos.GovernedEvaluationRequest request) {
        if (result == null || result.decision() == null
                || result.accessMode() != requestContext.activeAccessMode()
                || !request.navigationContextId().equals(result.navigationContextId())) {
            return false;
        }
        if (blank(result.authRevision()) || blank(result.policyRevision())) return false;
        if (result.decision() != ProductSurfaceContextDtos.GovernedDecision.ALLOWED) return true;
        return !blank(result.contextKey())
                && result.accessSource() != null
                && !blank(result.routeGrantRef())
                && result.revalidateAt() != null;
    }

    private boolean validEligibility(ProductSurfaceContextDtos.EligibilityResult result) {
        if (result == null || result.decision() == null
                || !Set.of(
                                ProductSurfaceContextDtos.Decision.ALLOWED,
                                ProductSurfaceContextDtos.Decision.SURFACE_DENIED,
                                ProductSurfaceContextDtos.Decision.SCOPE_INVALID,
                                ProductSurfaceContextDtos.Decision.AUTHORITY_UNAVAILABLE)
                        .contains(result.decision())
                || blank(result.productRelationshipRevision())
                || blank(result.targetPopulationRevision())) {
            return false;
        }
        if (result.decision() != ProductSurfaceContextDtos.Decision.ALLOWED) {
            return result.scopes().isEmpty();
        }
        if (result.scopes().isEmpty() || result.revalidateAt() == null) return false;
        Set<String> keys = new java.util.HashSet<>();
        if (result.scopes().stream().anyMatch(scope -> scope == null
                || blank(scope.sourceScopeKey()) || blank(scope.key())
                || blank(scope.kind()) || blank(scope.displayName())
                || !keys.add(scope.key()))) return false;
        long defaults = result.scopes().stream()
                .filter(ProductSurfaceContextDtos.EligibleScope::isDefault)
                .count();
        return defaults <= 1 && (result.scopes().size() != 1 || defaults == 1);
    }

    record Resolution(
            ProductSurfaceContextDtos.Decision decision,
            String reasonCode,
            ProductSurfaceContextDtos.AuthorityResult authority,
            ProductSurfaceContextDtos.EffectiveContext context,
            ProductSurfaceContextDtos.SourceRevisions revisions,
            List<ProductSurfaceContextDtos.EffectiveScope> scopes,
            OffsetDateTime revalidateAt) {

        boolean authSurfaceProductNotRegistered() {
            return authority.decision() == ProductSurfaceContextDtos.Decision.SURFACE_DENIED
                    && PRODUCT_NOT_REGISTERED.equals(authority.reasonCode());
        }

        boolean authRouteProductNotRegistered() {
            return authority.decision() == ProductSurfaceContextDtos.Decision.ROUTE_DENIED
                    && PRODUCT_NOT_REGISTERED.equals(authority.reasonCode());
        }
    }

    private record CandidateResolution(
            ProductSurfaceContextDtos.ProductCandidate candidate,
            Resolution resolution) {

        static CandidateResolution available(
                ProductSurfaceContextDtos.ProductCandidate candidate,
                Resolution resolution) {
            return new CandidateResolution(candidate, resolution);
        }

        static CandidateResolution unavailable(
                ProductSurfaceContextDtos.ProductCandidate candidate) {
            return new CandidateResolution(candidate, null);
        }
    }

    public static final class AuthorityUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public record TrustedProductEvaluation(
            ProductSurfaceContextDtos.ProductEvaluationData data,
            String contextKey,
            ProductSurfaceContextDtos.EffectiveScope scope,
            boolean authRouteProductNotRegistered) {
    }
}
