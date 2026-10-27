package com.dwp.gateway.filter;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextAggregationService;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import com.dwp.gateway.productsurface.Per004GatewayRoutes;
import com.dwp.gateway.productsurface.Per004DecisionContextForwarder;
import com.dwp.gateway.productsurface.Sys006GatewayRoutes;
import com.dwp.gateway.productsurface.Sys008GatewayRoutes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import com.dwp.gateway.filter.ProductSurfaceScopeSelection.ScopeSelection;
import static com.dwp.gateway.filter.ProductSurfaceScopeSelection.scopeSelection;
import static com.dwp.gateway.filter.ProductSurfaceDecisionHeaders.*;
import static com.dwp.gateway.filter.ProductSurfaceRoutePolicies.*;
import static com.dwp.gateway.filter.ProductSurfaceDecisionContextSupport.*;
import static com.dwp.gateway.filter.ProductSurfaceScopeSelection.withoutScopeSelection;

/** Product-agnostic generated route PEP that emits only server-owned decision evidence. */
@Component
public final class ProductSurfaceDecisionContextFilter implements GlobalFilter, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ProductSurfaceDecisionContextFilter.class);

    public static final String ROUTE_HEADER = ProductSurfaceDecisionHeaders.ROUTE_HEADER;
    public static final String CURRENT_REVISION_HEADER = ProductSurfaceDecisionHeaders.CURRENT_REVISION_HEADER;
    public static final String CURRENT_REVALIDATE_AT_HEADER = ProductSurfaceDecisionHeaders.CURRENT_REVALIDATE_AT_HEADER;
    public static final String EXPECTED_REVISION_HEADER = ProductSurfaceDecisionHeaders.EXPECTED_REVISION_HEADER;
    public static final String RESPONSE_REVISION_HEADER = ProductSurfaceDecisionHeaders.RESPONSE_REVISION_HEADER;
    public static final String CONTEXT_HEADER = ProductSurfaceDecisionHeaders.CONTEXT_HEADER;
    public static final String SCOPE_HEADER = ProductSurfaceDecisionHeaders.SCOPE_HEADER;
    public static final String ACTIVE_ACCESS_MODE_HEADER = ProductSurfaceDecisionHeaders.ACTIVE_ACCESS_MODE_HEADER;
    public static final String SUBJECT_PRINCIPAL_HEADER = ProductSurfaceDecisionHeaders.SUBJECT_PRINCIPAL_HEADER;
    public static final String FIELD_POLICY_REVISION_HEADER = ProductSurfaceDecisionHeaders.FIELD_POLICY_REVISION_HEADER;
    public static final String FIELD_POLICY_KEY_HEADER = ProductSurfaceDecisionHeaders.FIELD_POLICY_KEY_HEADER;
    public static final String AUTHORIZATION_REVISION_HEADER = ProductSurfaceDecisionHeaders.AUTHORIZATION_REVISION_HEADER;
    public static final String POPULATION_KIND_HEADER = ProductSurfaceDecisionHeaders.POPULATION_KIND_HEADER;
    public static final String PURPOSE_HEADER = ProductSurfaceDecisionHeaders.PURPOSE_HEADER;
    public static final String EXPECTED_OBJECT_VERSION_HEADER = ProductSurfaceDecisionHeaders.EXPECTED_OBJECT_VERSION_HEADER;
    public static final String VERIFIED_LEGAL_ENTITY_HEADER = ProductSurfaceDecisionHeaders.VERIFIED_LEGAL_ENTITY_HEADER;
    public static final String LEGAL_ENTITY_OWNER_EVIDENCE_HEADER = ProductSurfaceDecisionHeaders.LEGAL_ENTITY_OWNER_EVIDENCE_HEADER;
    public static final String MASKED_FIELD_GROUPS_HEADER = ProductSurfaceDecisionHeaders.MASKED_FIELD_GROUPS_HEADER;
    public static final String OMITTED_FIELD_GROUPS_HEADER = ProductSurfaceDecisionHeaders.OMITTED_FIELD_GROUPS_HEADER;
    public static final String SCOPE_QUERY_PARAMETER = ProductSurfaceDecisionHeaders.SCOPE_QUERY_PARAMETER;
    private static final List<String> INTERNAL_HEADERS = List.of(
            ROUTE_HEADER, CURRENT_REVISION_HEADER, CURRENT_REVALIDATE_AT_HEADER,
            RESPONSE_REVISION_HEADER, CONTEXT_HEADER, SCOPE_HEADER, ACTIVE_ACCESS_MODE_HEADER,
            SUBJECT_PRINCIPAL_HEADER, FIELD_POLICY_REVISION_HEADER, FIELD_POLICY_KEY_HEADER,
            AUTHORIZATION_REVISION_HEADER, POPULATION_KIND_HEADER,
            PURPOSE_HEADER, VERIFIED_LEGAL_ENTITY_HEADER,
            LEGAL_ENTITY_OWNER_EVIDENCE_HEADER,
            VerifiedIdentityFilter.RESPONSIBILITIES_HEADER,
            VerifiedIdentityFilter.LEGAL_ENTITY_PUBLIC_ID_HEADER,
            PAY_GROUP_PUBLIC_ID_HEADER,
            VerifiedIdentityFilter.TIME_GROUP_PUBLIC_ID_HEADER,
            MASKED_FIELD_GROUPS_HEADER, OMITTED_FIELD_GROUPS_HEADER,
            HRM012_MARKER_HEADER, STEP_UP_CHALLENGE_HEADER,
            "X-DWP-Current-Decision-Valid-Until");

    private final ProductSurfaceContextAggregationService aggregationService;
    private final GeneratedProductRouteCatalog routeCatalog;
    private final ProductSurfaceDecisionResponseWriter responses;
    private final ProductSurfaceDecisionForwarder forwarding;
    private final Per004DecisionContextForwarder per004Forwarder;

    @Autowired
    public ProductSurfaceDecisionContextFilter(
            ProductSurfaceContextAggregationService aggregationService,
            GeneratedProductRouteCatalog routeCatalog,
            ObjectMapper objectMapper,
            GatewayDenialAuditSink denialAudit,
            Per004DecisionContextForwarder per004Forwarder) {
        this.aggregationService = aggregationService;
        this.routeCatalog = routeCatalog;
        this.responses = new ProductSurfaceDecisionResponseWriter(objectMapper, denialAudit);
        this.forwarding = new ProductSurfaceDecisionForwarder(
                responses, VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                Sys006InboundAuthorityFilter::validInbound,
                Sys008InboundAuthorityFilter::validInbound);
        this.per004Forwarder = per004Forwarder;
    }

    public ProductSurfaceDecisionContextFilter(
            ProductSurfaceContextAggregationService aggregationService,
            GeneratedProductRouteCatalog routeCatalog,
            ObjectMapper objectMapper,
            GatewayDenialAuditSink denialAudit) {
        this(aggregationService, routeCatalog, objectMapper, denialAudit,
                new Per004DecisionContextForwarder(objectMapper));
    }

    /** Test-only constructor retaining isolated PRODUCT authority fixtures. */
    public ProductSurfaceDecisionContextFilter(
            ProductSurfaceContextAggregationService aggregationService,
            GeneratedProductRouteCatalog routeCatalog,
            ObjectMapper objectMapper) {
        this(aggregationService, routeCatalog, objectMapper, GatewayDenialAuditSink.NOOP);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        boolean per004 = Per004GatewayRoutes.isPath(exchange.getRequest().getURI().getPath())
                && exchange.getRequest().getMethod() != HttpMethod.OPTIONS;
        boolean sys006 = Sys006GatewayRoutes.claimsPath(
                exchange.getRequest().getURI().getPath())
                && exchange.getRequest().getMethod() != HttpMethod.OPTIONS;
        boolean sys008 = Sys008GatewayRoutes.claimsPath(
                exchange.getRequest().getURI().getPath())
                && exchange.getRequest().getMethod() != HttpMethod.OPTIONS;
        if (per004 && !per004Forwarder.validInbound(exchange)) {
            return responses.error(exchange, HttpStatus.BAD_REQUEST, "INVALID_PER004_AUTHORITY_INPUT", null);
        }
        if (sys006 && !Sys006InboundAuthorityFilter.validInbound(exchange)) {
            return responses.error(exchange, HttpStatus.BAD_REQUEST, "INVALID_SYS006_AUTHORITY_INPUT", null);
        }
        if (sys008 && !Sys008InboundAuthorityFilter.validInbound(exchange)) {
            return responses.error(exchange, HttpStatus.BAD_REQUEST, "INVALID_SYS008_AUTHORITY_INPUT", null);
        }
        List<String> expectedValues = exchange.getRequest().getHeaders()
                .getOrEmpty(EXPECTED_REVISION_HEADER);
        List<String> callerPurposeValues = exchange.getRequest().getHeaders()
                .getOrEmpty(PURPOSE_HEADER);
        List<String> ifMatchValues = exchange.getRequest().getHeaders()
                .getOrEmpty(IF_MATCH_HEADER);
        List<String> idempotencyKeyValues = exchange.getRequest().getHeaders()
                .getOrEmpty(IDEMPOTENCY_KEY_HEADER);
        List<String> stepUpChallengeValues = exchange.getRequest().getHeaders()
                .getOrEmpty(STEP_UP_CHALLENGE_HEADER);
        List<String> ownerReceiptMarkerValues = exchange.getRequest().getHeaders()
                .getOrEmpty(HRM012_MARKER_HEADER);
        List<String> expectedObjectVersionValues = exchange.getRequest().getHeaders()
                .getOrEmpty(EXPECTED_OBJECT_VERSION_HEADER);
        ServerHttpRequest sanitized = exchange.getRequest().mutate().headers(headers -> {
            INTERNAL_HEADERS.forEach(headers::remove);
            headers.remove(EXPECTED_REVISION_HEADER);
        }).build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitized).build();
        if (DeviceIdentityPlaneFilter.verified(exchange)) {
            return chain.filter(sanitizedExchange);
        }
        if (sanitized.getMethod() == HttpMethod.OPTIONS) return chain.filter(sanitizedExchange);

        ScopeSelection scopeSelection = scopeSelection(sanitized.getURI().getRawQuery());

        GeneratedProductRouteCatalog.AuthorityEndpoint authorityEndpoint =
                routeCatalog.authorityEndpoint(
                        sanitized.getMethod() == null ? null : sanitized.getMethod().name(),
                        sanitized.getURI().getPath());
        if (authorityEndpoint != null) {
            if (!scopeSelection.valid() || scopeSelection.present()) {
                return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                        "INVALID_SCOPE_SELECTION", null);
            }
            if (!EXPECTED_REVISION_HEADER.equals(
                    authorityEndpoint.expectedDecisionRevisionHeader())) {
                return responses.error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
            }
            String expected = expectedRevision(expectedValues);
            if (expected == null) {
                return responses.error(sanitizedExchange, HttpStatus.CONFLICT,
                        "DECISION_REVISION_CONFLICT", null);
            }
            ServerHttpRequest canonical = sanitized.mutate().headers(
                    headers -> headers.set(EXPECTED_REVISION_HEADER, expected)).build();
            return chain.filter(sanitizedExchange.mutate().request(canonical).build());
        }

        GeneratedProductRouteCatalog.Match match = routeCatalog.match(
                sanitized.getMethod() == null ? null : sanitized.getMethod().name(),
                sanitized.getURI().getPath(), sanitized.getURI().getRawQuery());
        if (per004 && (match.uniqueRoute() == null
                || !Per004GatewayRoutes.ROUTE_KEYS.contains(match.uniqueRoute().routeContractKey())
                || !sanitized.getURI().getPath().equals(
                        Per004GatewayRoutes.policy(match.uniqueRoute().routeContractKey()).path()))) {
            return responses.error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        if (sys006 && !Sys006GatewayRoutes.matches(
                match.uniqueRoute(),
                sanitized.getMethod() == null ? null : sanitized.getMethod().name(),
                sanitized.getURI().getPath())) {
            return responses.error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        if (sys008 && !Sys008GatewayRoutes.matches(
                match.uniqueRoute(),
                sanitized.getMethod() == null ? null : sanitized.getMethod().name(),
                sanitized.getURI().getPath())) {
            return responses.error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        if (match.status() == GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED
                || match.status()
                    == GeneratedProductRouteCatalog.MatchStatus.LEGACY_EXEMPT) {
            if (!scopeSelection.valid() || scopeSelection.present()) {
                return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                        "INVALID_SCOPE_SELECTION", null);
            }
            return chain.filter(sanitizedExchange);
        }
        if (match.status() != GeneratedProductRouteCatalog.MatchStatus.GOVERNED
                || match.uniqueRoute() == null) {
            LOGGER.warn(
                    "Product surface route resolution failed before authority evaluation: status={}",
                    match.status());
            return responses.error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        if (!scopeSelection.valid()) {
            return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                    "INVALID_SCOPE_SELECTION", null);
        }
        sanitized = withoutScopeSelection(sanitized, scopeSelection);
        sanitizedExchange = sanitizedExchange.mutate().request(sanitized).build();
        GeneratedProductRouteCatalog.Route route = match.uniqueRoute();
        if (!HRM012_ACTION_ROUTE.equals(route.routeContractKey())
                && (!ownerReceiptMarkerValues.isEmpty()
                    || !stepUpChallengeValues.isEmpty())) {
            return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                    "INVALID_OWNER_RECEIPT_AUTHORITY_INPUT", null);
        }
        String hrm012IdempotencyKey = null;
        String hrm012Challenge = null;
        String hrm012Correlation = null;
        if (HRM012_ACTION_ROUTE.equals(route.routeContractKey())) {
            hrm012IdempotencyKey = exactOpaqueHeader(idempotencyKeyValues, 200, false);
            hrm012Challenge = stepUpChallengeValues.isEmpty()
                    ? null : exactOpaqueHeader(stepUpChallengeValues, 8192, false);
            hrm012Correlation = exactOpaqueHeader(
                    exchange.getRequest().getHeaders().getOrEmpty("X-Correlation-ID"),
                    200, false);
            boolean valid = route.stateChanging() && route.highRiskStepUp()
                    && route.commandBoundStepUp() && !scopeSelection.present()
                    && hrm012IdempotencyKey != null && hrm012Correlation != null
                    && strongIfMatchVersion(ifMatchValues) != null
                    && expectedValues.isEmpty() && expectedObjectVersionValues.isEmpty()
                    && validOptionalCanonicalPurpose(callerPurposeValues, "HRIS_INTEGRATION")
                    && exchange.getRequest().getHeaders()
                            .getOrEmpty(org.springframework.http.HttpHeaders.AUTHORIZATION)
                            .size() == 1
                    && (stepUpChallengeValues.isEmpty() || hrm012Challenge != null);
            if (!valid) {
                return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                        "INVALID_HRM012_COMMAND_HEADERS", null);
            }
        }
        if (PAY003_CONFIGURATION_ROUTE_KEYS.contains(route.routeContractKey())
                || PAY003_RESULT_ROUTE_KEYS.contains(route.routeContractKey())
                || PAY004_RESULT_ROUTE_KEYS.contains(route.routeContractKey())
                || PAY003_ACTION_ROUTE_KEY.equals(route.routeContractKey())) {
            sanitized = sanitized.mutate().headers(
                    headers -> headers.remove(EXPECTED_OBJECT_VERSION_HEADER)).build();
            sanitizedExchange = sanitizedExchange.mutate().request(sanitized).build();
        }
        if ((HRM009_ACTION_ROUTE_KEY.equals(route.routeContractKey())
                || HRM011_ACTION_ROUTE_KEY.equals(route.routeContractKey())
                || PAY003_ACTION_ROUTE_KEY.equals(route.routeContractKey()))
                && (!route.stateChanging() || !route.highRiskStepUp()
                || !route.commandBoundStepUp())) {
            return responses.error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        if (HRM011_ACTION_ROUTE_KEY.equals(route.routeContractKey())
                && !validOptionalCanonicalPurpose(callerPurposeValues, "HR_LIFECYCLE")) {
            return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                    "INVALID_PURPOSE", null);
        }
        if (PAY004_RESULT_ROUTE_KEYS.contains(route.routeContractKey())
                && !validOptionalCanonicalPurpose(callerPurposeValues, "PAYROLL_OPERATIONS")) {
            return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                    "INVALID_PURPOSE", null);
        }
        if (PER008_ROUTE_KEYS.contains(route.routeContractKey())
                && !validOptionalCanonicalPurpose(
                        callerPurposeValues, ownerPurpose(route.routeContractKey()))) {
            return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                    "INVALID_PURPOSE", null);
        }
        if (Sys006GatewayRoutes.ROUTE_KEYS.contains(route.routeContractKey())
                && !validSys006Purpose(callerPurposeValues, route.stateChanging())) {
            return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                    "INVALID_PURPOSE", null);
        }
        if (Sys006GatewayRoutes.ROUTE_KEYS.contains(route.routeContractKey())) {
            if (!route.stateChanging()) {
                if (!idempotencyKeyValues.isEmpty() || !ifMatchValues.isEmpty()) {
                    return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                            "INVALID_SYS006_COMMAND_HEADERS", null);
                }
            } else {
                String idempotencyKey = safeSys006IdempotencyKey(idempotencyKeyValues);
                boolean requiresObjectRevision =
                        Sys006GatewayRoutes.requiresExpectedObjectRevision(
                                route.routeContractKey());
                String expectedObjectVersion = requiresObjectRevision
                        ? strongSys006IfMatchVersion(ifMatchValues) : null;
                if (idempotencyKey == null
                        || (requiresObjectRevision && expectedObjectVersion == null)
                        || (!requiresObjectRevision && !ifMatchValues.isEmpty())) {
                    return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                            "INVALID_SYS006_COMMAND_HEADERS", null);
                }
                sanitized = sanitized.mutate().headers(headers -> {
                    headers.set(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
                    if (expectedObjectVersion == null) {
                        headers.remove(IF_MATCH_HEADER);
                        headers.remove(EXPECTED_OBJECT_VERSION_HEADER);
                    } else {
                        headers.set(IF_MATCH_HEADER, '"' + expectedObjectVersion + '"');
                        headers.set(EXPECTED_OBJECT_VERSION_HEADER, expectedObjectVersion);
                    }
                }).build();
                sanitizedExchange = sanitizedExchange.mutate().request(sanitized).build();
            }
        }
        if (Sys008GatewayRoutes.ROUTE_KEYS.contains(route.routeContractKey())) {
            if (!callerPurposeValues.equals(List.of(Sys008GatewayRoutes.PURPOSE))) {
                return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                        "INVALID_PURPOSE", null);
            }
            if (!route.stateChanging()) {
                if (!idempotencyKeyValues.isEmpty() || !ifMatchValues.isEmpty()) {
                    return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                            "INVALID_SYS008_COMMAND_HEADERS", null);
                }
            } else {
                String idempotencyKey = safeSys008IdempotencyKey(idempotencyKeyValues);
                boolean requiresObjectRevision =
                        Sys008GatewayRoutes.requiresExpectedObjectRevision(
                                route.routeContractKey());
                String ifMatch = requiresObjectRevision
                        ? canonicalSys008IfMatch(ifMatchValues) : null;
                if (idempotencyKey == null
                        || (requiresObjectRevision && ifMatch == null)
                        || (!requiresObjectRevision && !ifMatchValues.isEmpty())) {
                    return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                            "INVALID_SYS008_COMMAND_HEADERS", null);
                }
                sanitized = sanitized.mutate().headers(headers -> {
                    headers.set(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
                    if (ifMatch == null) {
                        headers.remove(IF_MATCH_HEADER);
                        headers.remove(EXPECTED_OBJECT_VERSION_HEADER);
                    } else {
                        headers.set(IF_MATCH_HEADER, ifMatch);
                        headers.set(EXPECTED_OBJECT_VERSION_HEADER,
                                ifMatch.replaceFirst("^W/", "")
                                        .substring(1, ifMatch.replaceFirst("^W/", "").length() - 1));
                    }
                }).build();
                sanitizedExchange = sanitizedExchange.mutate().request(sanitized).build();
            }
        }
        if (PAY003_ACTION_ROUTE_KEY.equals(route.routeContractKey())) {
            String expectedObjectVersion = strongIfMatchVersion(ifMatchValues);
            if (!validOptionalCanonicalPurpose(
                    callerPurposeValues, "STATUTORY_CONFIGURATION")) {
                return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                        "INVALID_PURPOSE", null);
            }
            if (scopeSelection.present()
                    || !canonicalCountryPackActivationPath(sanitized.getURI().getPath())) {
                return responses.error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                        "INVALID_COUNTRY_PACK_PRECONDITION", null);
            }
            if (expectedObjectVersion == null) {
                return responses.error(sanitizedExchange, HttpStatus.CONFLICT,
                        "OBJECT_VERSION_CONFLICT", null);
            }
            String canonicalIfMatch = '"' + expectedObjectVersion + '"';
            sanitized = sanitized.mutate().headers(headers -> {
                headers.set(IF_MATCH_HEADER, canonicalIfMatch);
                headers.set(EXPECTED_OBJECT_VERSION_HEADER, expectedObjectVersion);
            }).build();
            sanitizedExchange = sanitizedExchange.mutate().request(sanitized).build();
        }
        if (ownerPurpose(route.routeContractKey()) != null) {
            sanitized = sanitized.mutate().headers(headers -> headers.remove(PURPOSE_HEADER))
                    .build();
            sanitizedExchange = sanitizedExchange.mutate().request(sanitized).build();
        }
        String rollout = sanitized.getHeaders().getFirst(
                ProductSurfaceRolloutHeaderFilter.STATE_HEADER);
        if (rollout == null || !ROLLOUT_STATES.contains(rollout)) {
            LOGGER.warn(
                    "Trusted product surface rollout evidence is missing before authority evaluation for route {}",
                    route.routeContractKey());
            return responses.error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        // States 000 and 100 normally bypass the product PEP. Home Runtime shadow reads and the
        // exact tenant-feature closed set are deliberate exceptions: both require direct Auth
        // evidence before an owner can be reached.
        if (rollout.charAt(1) == '0'
                && !requiresHomeShadowAuthority(rollout, route)
                && !TENANT_FEATURE_GOVERNED_ROUTE_KEYS.contains(route.routeContractKey())
                && !HRM007_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())) {
            return chain.filter(sanitizedExchange);
        }

        Long actorId = positive(sanitized.getHeaders().getFirst(VerifiedIdentityFilter.USER_HEADER));
        Long tenantId = positive(sanitized.getHeaders().getFirst(VerifiedIdentityFilter.TENANT_HEADER));
        if (actorId == null || tenantId == null) {
            return responses.error(sanitizedExchange, HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED", null);
        }
        String expected = expectedRevision(expectedValues);
        if (!expectedValues.isEmpty() && expected == null) {
            return responses.error(sanitizedExchange, HttpStatus.CONFLICT,
                    "DECISION_REVISION_CONFLICT", null);
        }
        ServerHttpRequest canonical = sanitized.mutate().headers(headers -> {
            if (expected != null) headers.set(EXPECTED_REVISION_HEADER, expected);
        }).build();
        ServerWebExchange canonicalExchange = sanitizedExchange.mutate().request(canonical).build();
        ProductSurfaceContextDtos.AccessMode verifiedMode = verifiedTenantAccessMode(
                canonicalExchange);
        if (verifiedMode == null) {
            return responses.error(canonicalExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        ProductSurfaceContextDtos.RequestContext requestContext = requestContext(canonical,
                tenantId, actorId, verifiedMode);
        if (HRM012_ACTION_ROUTE.equals(route.routeContractKey())) {
            if (verifiedMode != ProductSurfaceContextDtos.AccessMode.NORMAL) {
                return responses.error(canonicalExchange, HttpStatus.FORBIDDEN,
                        "OWNER_RECEIPT_AUTH_REQUIRED", null);
            }
            String idempotencyDigest = sha256(hrm012IdempotencyKey);
            String challenge = hrm012Challenge;
            String correlation = hrm012Correlation;
            return aggregationService.evaluateHrm012OwnerReceiptAuthority(
                            requestContext, correlation, idempotencyDigest, challenge)
                    .flatMap(marker -> {
                        ServerHttpRequest ownerRequest = canonical.mutate().headers(headers -> {
                            headers.remove(STEP_UP_CHALLENGE_HEADER);
                            headers.remove(HRM012_MARKER_HEADER);
                            headers.remove(EXPECTED_REVISION_HEADER);
                            headers.remove(EXPECTED_OBJECT_VERSION_HEADER);
                            headers.set(ROUTE_HEADER, HRM012_ACTION_ROUTE);
                            headers.set(HRM012_MARKER_HEADER, marker);
                            if (callerPurposeValues.isEmpty()) {
                                headers.remove(PURPOSE_HEADER);
                            } else {
                                headers.set(PURPOSE_HEADER, callerPurposeValues.getFirst());
                            }
                        }).build();
                        return chain.filter(canonicalExchange.mutate()
                                .request(ownerRequest).build());
                    })
                    .onErrorResume(
                            com.dwp.gateway.productsurface
                                    .ProductSurfaceAuthorityUnavailableException.class,
                            failure -> responses.error(canonicalExchange,
                                    HttpStatus.SERVICE_UNAVAILABLE,
                                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null));
        }
        ProductSurfaceContextDtos.ProductEvaluationRequest request =
                new ProductSurfaceContextDtos.ProductEvaluationRequest(
                        new ProductSurfaceContextDtos.Subject(
                                "PRODUCT", route.productKey(), route.surfaceKey()),
                        route.routeContractKey(), null, scopeSelection.value());
        return aggregationService.evaluateProductTrusted(requestContext, request)
                .flatMap(result -> forwardOrDeny(
                        canonicalExchange, canonical, chain, route, result, expected,
                        requestContext.activeAccessMode()))
                .onErrorResume(
                        com.dwp.gateway.productsurface.ProductSurfaceAuthorityUnavailableException.class,
                        failure -> {
                            LOGGER.warn(
                                    "Product surface authority resolution failed for route {}",
                                    route.routeContractKey(),
                                    failure);
                            return responses.error(canonicalExchange, HttpStatus.SERVICE_UNAVAILABLE,
                                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
                        });
    }

    private ProductSurfaceContextDtos.RequestContext requestContext(
            ServerHttpRequest request,
            long tenantId,
            long actorId,
            ProductSurfaceContextDtos.AccessMode verifiedTenantMode) {
        String supportSession = request.getHeaders().getFirst(
                SupportSessionContextFilter.SUPPORT_SESSION_HEADER);
        ProductSurfaceContextDtos.AccessMode mode = supportSession == null
                ? verifiedTenantMode
                : ProductSurfaceContextDtos.AccessMode.PROVIDER_SUPPORT;
        return new ProductSurfaceContextDtos.RequestContext(
                tenantId, actorId, mode, supportSession,
                request.getHeaders().getFirst(SupportSessionContextFilter.SUPPORT_REVISION_HEADER),
                splitScopes(request.getHeaders().getFirst(
                        SupportSessionContextFilter.SUPPORT_SCOPES_HEADER)),
                request.getHeaders().getFirst("X-Correlation-ID"),
                request.getHeaders().getFirst("traceparent"),
                request.getHeaders().getFirst("tracestate"),
                request.getHeaders().getFirst(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER),
                splitScopes(request.getHeaders().getFirst(VerifiedIdentityFilter.ROLES_HEADER)),
                splitScopes(request.getHeaders().getFirst(
                        VerifiedIdentityFilter.PERMISSIONS_HEADER)));
    }

    private ProductSurfaceContextDtos.AccessMode verifiedTenantAccessMode(
            ServerWebExchange exchange) {
        Object value = exchange.getAttribute(
                VerifiedIdentityFilter.VERIFIED_ACTIVE_ACCESS_MODE_ATTRIBUTE);
        if (value == null) return ProductSurfaceContextDtos.AccessMode.NORMAL;
        if ("NORMAL".equals(value)) return ProductSurfaceContextDtos.AccessMode.NORMAL;
        if ("ELEVATED".equals(value)) return ProductSurfaceContextDtos.AccessMode.ELEVATED;
        return null;
    }

    private Mono<Void> forwardOrDeny(
            ServerWebExchange exchange,
            ServerHttpRequest request,
            GatewayFilterChain chain,
            GeneratedProductRouteCatalog.Route route,
            ProductSurfaceContextAggregationService.TrustedProductEvaluation trustedResult,
            String expected,
            ProductSurfaceContextDtos.AccessMode activeAccessMode) {
        return forwarding.forwardOrDeny(
                exchange, request, route, trustedResult.data(), trustedResult.revisions(),
                trustedResult.contextKey(), trustedResult.scope(),
                trustedResult.authRouteProductNotRegistered(), expected, activeAccessMode,
                trustedExchange -> {
                    if (Per004GatewayRoutes.ROUTE_KEYS.contains(route.routeContractKey())) {
                        return per004Forwarder.forward(trustedExchange, chain, route, trustedResult);
                    }
                    return chain.filter(trustedExchange);
                });
    }

    @Override
    public int getOrder() {
        return -92;
    }

}
