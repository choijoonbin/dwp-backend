package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwp.gateway.filter.SupportSessionContextFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ProductSurfaceContextHandler {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String TRACE_STATE_HEADER = "tracestate";

    private final ProductSurfaceContextAggregationService aggregationService;
    private final ObjectMapper objectMapper;

    public ProductSurfaceContextHandler(
            ProductSurfaceContextAggregationService aggregationService,
            ObjectMapper objectMapper) {
        this.aggregationService = aggregationService;
        this.objectMapper = objectMapper;
    }

    public Mono<ServerResponse> contexts(ServerRequest request) {
        if (!ProductSurfaceForwardingGuardFilter.permits(
                request, ProductSurfaceForwardingGuardFilter.Endpoint.CONTEXTS)) {
            return ServerResponse.notFound().build();
        }
        ProductSurfaceContextDtos.RequestContext requestContext;
        try {
            requestContext = requestContext(request);
        } catch (InvalidRequestException exception) {
            return error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", request);
        }
        return aggregationService.listContexts(requestContext)
                .flatMap(data -> ok(ProductSurfaceContextDtos.ApiResponse.success(data)))
                .onErrorResume(
                        ProductSurfaceContextAggregationService.AuthorityUnavailableException.class,
                        ignored -> error(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "AUTHORITY_RESOLUTION_UNAVAILABLE",
                                request));
    }

    public Mono<ServerResponse> evaluateProduct(ServerRequest request) {
        if (!ProductSurfaceForwardingGuardFilter.permits(
                request, ProductSurfaceForwardingGuardFilter.Endpoint.PRODUCT_EVALUATION)) {
            return ServerResponse.notFound().build();
        }
        ProductSurfaceContextDtos.RequestContext requestContext;
        try {
            requestContext = requestContext(request);
        } catch (InvalidRequestException exception) {
            return error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", request);
        }
        return strictBody(request, ProductSurfaceContextDtos.ProductEvaluationRequest.class, false)
                .flatMap(body -> {
                    validateProduct(body);
                    return aggregationService.evaluateProduct(requestContext, body);
                })
                .flatMap(data -> ok(ProductSurfaceContextDtos.ApiResponse.success(data)))
                .onErrorResume(
                        InvalidRequestException.class,
                        ignored -> error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", request))
                .onErrorResume(
                        ProductSurfaceContextAggregationService.AuthorityUnavailableException.class,
                        ignored -> error(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "AUTHORITY_RESOLUTION_UNAVAILABLE",
                                request))
                .onErrorResume(
                        org.springframework.core.codec.DecodingException.class,
                        ignored -> error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", request));
    }

    public Mono<ServerResponse> evaluateGoverned(ServerRequest request) {
        if (!ProductSurfaceForwardingGuardFilter.permits(
                request, ProductSurfaceForwardingGuardFilter.Endpoint.GOVERNED_EVALUATION)) {
            return ServerResponse.notFound().build();
        }
        ProductSurfaceContextDtos.RequestContext requestContext;
        try {
            requestContext = requestContext(request);
        } catch (InvalidRequestException exception) {
            return error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", request);
        }
        return strictBody(request, ProductSurfaceContextDtos.GovernedEvaluationRequest.class, true)
                .flatMap(body -> {
                    validateGoverned(body);
                    return aggregationService.evaluateGoverned(requestContext, body);
                })
                .flatMap(data -> ok(ProductSurfaceContextDtos.ApiResponse.success(data)))
                .onErrorResume(
                        InvalidRequestException.class,
                        ignored -> error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", request))
                .onErrorResume(
                        ProductSurfaceContextAggregationService.AuthorityUnavailableException.class,
                        ignored -> error(
                                HttpStatus.SERVICE_UNAVAILABLE,
                                "AUTHORITY_RESOLUTION_UNAVAILABLE",
                                request))
                .onErrorResume(
                        org.springframework.core.codec.DecodingException.class,
                        ignored -> error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", request));
    }

    private <T> Mono<T> strictBody(ServerRequest request, Class<T> type, boolean governed) {
        return request.bodyToMono(String.class)
                .switchIfEmpty(Mono.error(new InvalidRequestException()))
                .map(payload -> {
                    try {
                        JsonNode root = objectMapper.readerFor(JsonNode.class)
                                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readTree(payload);
                        validateFieldSet(root, governed);
                        return objectMapper.treeToValue(root, type);
                    } catch (java.io.IOException | IllegalArgumentException exception) {
                        throw new InvalidRequestException();
                    }
                });
    }

    private void validateFieldSet(JsonNode root, boolean governed) {
        Set<String> allowed = governed
                ? Set.of("subject", "navigationContextId", "routeContractKey", "target", "contextKey")
                : Set.of("subject", "routeContractKey", "contextKey", "contextScopeKey");
        if (root == null || !root.isObject() || !allowed.containsAll(fields(root))) {
            throw new InvalidRequestException();
        }
        JsonNode subject = root.get("subject");
        Set<String> subjectFields = governed
                ? Set.of("type")
                : Set.of("type", "productKey", "surfaceKey");
        if (subject == null || !subject.isObject() || !fields(subject).equals(subjectFields)) {
            throw new InvalidRequestException();
        }
        JsonNode target = root.get("target");
        if (target != null && (!governed || !target.isObject()
                || !Set.of("opaqueTargetRef", "expectedObjectVersion")
                        .containsAll(fields(target)))) {
            throw new InvalidRequestException();
        }
    }

    private Set<String> fields(JsonNode value) {
        return value.propertyStream()
                .map(entry -> entry.getKey())
                .collect(Collectors.toUnmodifiableSet());
    }

    private ProductSurfaceContextDtos.RequestContext requestContext(ServerRequest request) {
        long actorId = positiveLong(request.headers().firstHeader(VerifiedIdentityFilter.USER_HEADER));
        long tenantId = positiveLong(request.headers().firstHeader(VerifiedIdentityFilter.TENANT_HEADER));
        String supportSession = request.headers().firstHeader(
                SupportSessionContextFilter.SUPPORT_SESSION_HEADER);
        ProductSurfaceContextDtos.AccessMode accessMode = supportSession == null
                ? ProductSurfaceContextDtos.AccessMode.NORMAL
                : ProductSurfaceContextDtos.AccessMode.PROVIDER_SUPPORT;
        return new ProductSurfaceContextDtos.RequestContext(
                tenantId,
                actorId,
                accessMode,
                supportSession,
                request.headers().firstHeader(SupportSessionContextFilter.SUPPORT_REVISION_HEADER),
                commaSeparated(request.headers().firstHeader(
                        SupportSessionContextFilter.SUPPORT_SCOPES_HEADER)),
                request.headers().firstHeader(CORRELATION_HEADER),
                request.headers().firstHeader(TRACE_PARENT_HEADER),
                request.headers().firstHeader(TRACE_STATE_HEADER),
                request.headers().firstHeader(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER),
                commaSeparated(request.headers().firstHeader(VerifiedIdentityFilter.ROLES_HEADER)),
                commaSeparated(request.headers().firstHeader(
                        VerifiedIdentityFilter.PERMISSIONS_HEADER)));
    }

    private long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) return parsed;
        } catch (NumberFormatException | NullPointerException ignored) {
            // The edge must never trust an absent or malformed propagated identity.
        }
        throw new InvalidRequestException();
    }

    private List<String> commaSeparated(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(part -> !part.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private void validateProduct(ProductSurfaceContextDtos.ProductEvaluationRequest request) {
        ProductSurfaceContextDtos.Subject subject = request.subject();
        if (subject == null || !"PRODUCT".equals(subject.type())
                || blank(subject.productKey()) || blank(subject.surfaceKey())
                || blank(request.routeContractKey())) {
            throw new InvalidRequestException();
        }
    }

    private void validateGoverned(ProductSurfaceContextDtos.GovernedEvaluationRequest request) {
        ProductSurfaceContextDtos.Subject subject = request.subject();
        if (subject == null || !"GOVERNED_CONTEXT".equals(subject.type())
                || !blank(subject.productKey()) || !blank(subject.surfaceKey())
                || blank(request.navigationContextId()) || blank(request.routeContractKey())) {
            throw new InvalidRequestException();
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private Mono<ServerResponse> ok(Object body) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private Mono<ServerResponse> error(
            HttpStatus status,
            String code,
            ServerRequest request) {
        String message = status == HttpStatus.SERVICE_UNAVAILABLE
                ? "Authority resolution is temporarily unavailable."
                : status == HttpStatus.UNAUTHORIZED
                        ? "Authentication is required."
                        : "The request is invalid.";
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(ProductSurfaceContextDtos.ApiResponse.error(
                        code,
                        message,
                        request.headers().firstHeader(CORRELATION_HEADER)));
    }

    private static final class InvalidRequestException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
