package com.dwp.gateway.filter;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextAggregationService;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Product-agnostic generated route PEP that emits only server-owned decision evidence. */
@Component
public final class ProductSurfaceDecisionContextFilter implements GlobalFilter, Ordered {

    public static final String ROUTE_HEADER = "X-DWP-Route-Contract-Key";
    public static final String CURRENT_REVISION_HEADER = "X-DWP-Current-Decision-Revision";
    public static final String CURRENT_REVALIDATE_AT_HEADER = "X-DWP-Current-Revalidate-At";
    public static final String EXPECTED_REVISION_HEADER = "X-DWP-Expected-Decision-Revision";
    public static final String RESPONSE_REVISION_HEADER = "X-DWP-Decision-Revision";
    public static final String CONTEXT_HEADER = "X-DWP-Context-Key";
    public static final String SCOPE_HEADER = "X-DWP-Context-Scope-Key";
    public static final String ACTIVE_ACCESS_MODE_HEADER = "X-DWP-Active-Access-Mode";
    public static final String SCOPE_QUERY_PARAMETER = "contextScopeKey";
    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final List<String> INTERNAL_HEADERS = List.of(
            ROUTE_HEADER, CURRENT_REVISION_HEADER, CURRENT_REVALIDATE_AT_HEADER,
            RESPONSE_REVISION_HEADER, CONTEXT_HEADER, SCOPE_HEADER, ACTIVE_ACCESS_MODE_HEADER,
            "X-DWP-Current-Decision-Valid-Until");

    private final ProductSurfaceContextAggregationService aggregationService;
    private final GeneratedProductRouteCatalog routeCatalog;
    private final ObjectMapper objectMapper;
    private final GatewayDenialAuditSink denialAudit;

    @Autowired
    public ProductSurfaceDecisionContextFilter(
            ProductSurfaceContextAggregationService aggregationService,
            GeneratedProductRouteCatalog routeCatalog,
            ObjectMapper objectMapper,
            GatewayDenialAuditSink denialAudit) {
        this.aggregationService = aggregationService;
        this.routeCatalog = routeCatalog;
        this.objectMapper = objectMapper;
        this.denialAudit = denialAudit;
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
        List<String> expectedValues = exchange.getRequest().getHeaders()
                .getOrEmpty(EXPECTED_REVISION_HEADER);
        ServerHttpRequest sanitized = exchange.getRequest().mutate().headers(headers -> {
            INTERNAL_HEADERS.forEach(headers::remove);
            headers.remove(EXPECTED_REVISION_HEADER);
        }).build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitized).build();
        if (sanitized.getMethod() == HttpMethod.OPTIONS) return chain.filter(sanitizedExchange);

        ScopeSelection scopeSelection = scopeSelection(sanitized.getURI().getRawQuery());

        GeneratedProductRouteCatalog.AuthorityEndpoint authorityEndpoint =
                routeCatalog.authorityEndpoint(
                        sanitized.getMethod() == null ? null : sanitized.getMethod().name(),
                        sanitized.getURI().getPath());
        if (authorityEndpoint != null) {
            if (!scopeSelection.valid() || scopeSelection.present()) {
                return error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                        "INVALID_SCOPE_SELECTION", null);
            }
            if (!EXPECTED_REVISION_HEADER.equals(
                    authorityEndpoint.expectedDecisionRevisionHeader())) {
                return error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
            }
            String expected = expectedRevision(expectedValues);
            if (expected == null) {
                return error(sanitizedExchange, HttpStatus.CONFLICT,
                        "DECISION_REVISION_CONFLICT", null);
            }
            ServerHttpRequest canonical = sanitized.mutate().headers(
                    headers -> headers.set(EXPECTED_REVISION_HEADER, expected)).build();
            return chain.filter(sanitizedExchange.mutate().request(canonical).build());
        }

        GeneratedProductRouteCatalog.Match match = routeCatalog.match(
                sanitized.getMethod() == null ? null : sanitized.getMethod().name(),
                sanitized.getURI().getPath(), sanitized.getURI().getRawQuery());
        if (match.status() == GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED
                || match.status()
                    == GeneratedProductRouteCatalog.MatchStatus.LEGACY_EXEMPT) {
            if (!scopeSelection.valid() || scopeSelection.present()) {
                return error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                        "INVALID_SCOPE_SELECTION", null);
            }
            return chain.filter(sanitizedExchange);
        }
        if (match.status() != GeneratedProductRouteCatalog.MatchStatus.GOVERNED
                || match.uniqueRoute() == null) {
            return error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        if (!scopeSelection.valid()) {
            return error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                    "INVALID_SCOPE_SELECTION", null);
        }
        sanitized = withoutScopeSelection(sanitized, scopeSelection);
        sanitizedExchange = sanitizedExchange.mutate().request(sanitized).build();
        GeneratedProductRouteCatalog.Route route = match.uniqueRoute();
        String rollout = sanitized.getHeaders().getFirst(
                ProductSurfaceRolloutHeaderFilter.STATE_HEADER);
        if (!ROLLOUT_STATES.contains(rollout)) {
            return error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        if (rollout.charAt(1) == '0') return chain.filter(sanitizedExchange);

        Long actorId = positive(sanitized.getHeaders().getFirst(VerifiedIdentityFilter.USER_HEADER));
        Long tenantId = positive(sanitized.getHeaders().getFirst(VerifiedIdentityFilter.TENANT_HEADER));
        if (actorId == null || tenantId == null) {
            return error(sanitizedExchange, HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED", null);
        }
        String expected = expectedRevision(expectedValues);
        if (!expectedValues.isEmpty() && expected == null) {
            return error(sanitizedExchange, HttpStatus.CONFLICT,
                    "DECISION_REVISION_CONFLICT", null);
        }
        ServerHttpRequest canonical = sanitized.mutate().headers(headers -> {
            if (expected != null) headers.set(EXPECTED_REVISION_HEADER, expected);
        }).build();
        ServerWebExchange canonicalExchange = sanitizedExchange.mutate().request(canonical).build();
        ProductSurfaceContextDtos.RequestContext requestContext = requestContext(canonical,
                tenantId, actorId);
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
                        ProductSurfaceContextAggregationService.AuthorityUnavailableException.class,
                        ignored -> error(canonicalExchange, HttpStatus.SERVICE_UNAVAILABLE,
                                "AUTHORITY_RESOLUTION_UNAVAILABLE", null));
    }

    private ProductSurfaceContextDtos.RequestContext requestContext(
            ServerHttpRequest request,
            long tenantId,
            long actorId) {
        String supportSession = request.getHeaders().getFirst(
                SupportSessionContextFilter.SUPPORT_SESSION_HEADER);
        ProductSurfaceContextDtos.AccessMode mode = supportSession == null
                ? ProductSurfaceContextDtos.AccessMode.NORMAL
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

    private Mono<Void> forwardOrDeny(
            ServerWebExchange exchange,
            ServerHttpRequest request,
            GatewayFilterChain chain,
            GeneratedProductRouteCatalog.Route route,
            ProductSurfaceContextAggregationService.TrustedProductEvaluation trustedResult,
            String expected,
            ProductSurfaceContextDtos.AccessMode activeAccessMode) {
        ProductSurfaceContextDtos.ProductEvaluationData result = trustedResult.data();
        if (trustedResult.authRouteProductNotRegistered()
                && result.decision() == ProductSurfaceContextDtos.Decision.ROUTE_DENIED
                && "PRODUCT_NOT_REGISTERED".equals(result.reasonCode())) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if (route.stateChanging() && expected == null) {
            return error(exchange, HttpStatus.CONFLICT,
                    "DECISION_REVISION_CONFLICT", result.decisionRevision());
        }
        boolean allowed = result.decision() == ProductSurfaceContextDtos.Decision.ALLOWED;
        boolean eligibleStepUp = result.decision()
                == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED
                && route.highRiskStepUp();
        if (!allowed && !eligibleStepUp) {
            if (result.decision() == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED) {
                return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
            }
            HttpStatus status = result.decision()
                    == ProductSurfaceContextDtos.Decision.AUTHORITY_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
            if (status == HttpStatus.FORBIDDEN) {
                return deny(exchange, result.reasonCode(), result.decisionRevision(),
                        route.publicPath());
            }
            return error(exchange, status, result.reasonCode(), result.decisionRevision());
        }
        if (route.stateChanging() && !result.decisionRevision().equals(expected)) {
            return error(exchange, HttpStatus.CONFLICT,
                    "DECISION_REVISION_CONFLICT", result.decisionRevision());
        }
        String contextKey = trustedResult.contextKey();
        String scopeKey = trustedResult.scope() == null ? null : trustedResult.scope().key();
        if (blank(result.decisionRevision()) || blank(contextKey) || blank(scopeKey)) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        responseRevision(exchange, result.decisionRevision());
        ServerHttpRequest trusted = request.mutate().headers(headers -> {
            headers.set(ROUTE_HEADER, route.routeContractKey());
            headers.set(CURRENT_REVISION_HEADER, result.decisionRevision());
            headers.set(CONTEXT_HEADER, contextKey);
            headers.set(SCOPE_HEADER, scopeKey);
            headers.set(ACTIVE_ACCESS_MODE_HEADER, activeAccessMode.name());
            if (result.revalidateAt() != null) {
                headers.set(CURRENT_REVALIDATE_AT_HEADER, result.revalidateAt().toString());
            }
        }).build();
        return chain.filter(exchange.mutate().request(trusted).build());
    }

    private Mono<Void> deny(
            ServerWebExchange exchange,
            String code,
            String currentRevision,
            String routeTemplate) {
        return denialAudit.publish(exchange,
                        GatewayDenialAuditSink.Denial.productAuthority(
                                code, routeTemplate))
                .then(Mono.defer(() -> error(
                        exchange, HttpStatus.FORBIDDEN, code, currentRevision)))
                .onErrorResume(ignored -> error(
                        exchange,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "AUDIT_EVIDENCE_UNAVAILABLE",
                        currentRevision));
    }

    private String expectedRevision(List<String> values) {
        if (values.isEmpty()) return null;
        if (values.size() != 1) return null;
        String value = values.getFirst();
        if (value == null || value.isBlank() || value.length() > 200
                || value.indexOf(',') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) return null;
        return value.trim();
    }

    private ScopeSelection scopeSelection(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return new ScopeSelection(false, true, null, null);
        }
        List<String> retained = new ArrayList<>();
        String selected = null;
        int occurrences = 0;
        try {
            for (String pair : rawQuery.split("&", -1)) {
                if (pair.isEmpty()) return ScopeSelection.invalid();
                int separator = pair.indexOf('=');
                String key = URLDecoder.decode(
                        separator < 0 ? pair : pair.substring(0, separator),
                        StandardCharsets.UTF_8);
                String value = URLDecoder.decode(
                        separator < 0 ? "" : pair.substring(separator + 1),
                        StandardCharsets.UTF_8);
                if (!SCOPE_QUERY_PARAMETER.equals(key)) {
                    retained.add(pair);
                    continue;
                }
                occurrences++;
                selected = value;
            }
        } catch (IllegalArgumentException exception) {
            return ScopeSelection.invalid();
        }
        if (occurrences == 0) {
            return new ScopeSelection(false, true, null, rawQuery);
        }
        if (occurrences != 1 || selected == null || selected.isBlank()
                || selected.length() > 200 || !selected.equals(selected.trim())
                || !selected.matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,199}")) {
            return ScopeSelection.invalid();
        }
        return new ScopeSelection(
                true, true, selected, retained.isEmpty() ? null : String.join("&", retained));
    }

    private ServerHttpRequest withoutScopeSelection(
            ServerHttpRequest request,
            ScopeSelection selection) {
        if (!selection.present()) return request;
        URI uri = request.getURI();
        String raw = uri.toString();
        int fragment = raw.indexOf('#');
        String suffix = fragment < 0 ? "" : raw.substring(fragment);
        String withoutFragment = fragment < 0 ? raw : raw.substring(0, fragment);
        int query = withoutFragment.indexOf('?');
        String base = query < 0 ? withoutFragment : withoutFragment.substring(0, query);
        String rebuilt = base + (selection.retainedRawQuery() == null
                ? "" : "?" + selection.retainedRawQuery()) + suffix;
        return request.mutate().uri(URI.create(rebuilt)).build();
    }

    private List<String> splitScopes(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",", -1))
                .map(String::trim)
                .filter(scope -> !scope.isBlank())
                .distinct()
                .toList();
    }

    private Long positive(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void responseRevision(ServerWebExchange exchange, String revision) {
        if (blank(revision)) return;
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(RESPONSE_REVISION_HEADER, revision);
            return Mono.empty();
        });
    }

    private Mono<Void> error(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String currentRevision) {
        try {
            responseRevision(exchange, currentRevision);
            String correlation = exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");
            byte[] body = objectMapper.writeValueAsBytes(
                    ProductSurfaceContextDtos.ApiResponse.error(code, code, correlation));
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return exchange.getResponse().writeWith(Mono.just(
                    exchange.getResponse().bufferFactory().wrap(body)));
        } catch (Exception exception) {
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return exchange.getResponse().setComplete();
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public int getOrder() {
        return -92;
    }

    private record ScopeSelection(
            boolean present,
            boolean valid,
            String value,
            String retainedRawQuery) {

        static ScopeSelection invalid() {
            return new ScopeSelection(true, false, null, null);
        }
    }
}
