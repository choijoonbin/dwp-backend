package com.dwp.gateway.filter;

import com.dwp.gateway.productsurface.FeatureRolloutEvaluationClient;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;

@Component
public class ProductSurfaceRolloutHeaderFilter implements GlobalFilter, Ordered {

    public static final String COHORT_HEADER = "X-DWP-Rollout-Cohort";
    public static final String REVISION_HEADER = "X-DWP-Rollout-Revision";
    public static final String STATE_HEADER = "X-DWP-Rollout-State";
    public static final String HOME_RUNTIME_STATE_HEADER = "X-DWP-Home-Runtime-State";
    public static final String HOME_ROLLOUT_RING_HEADER = "X-DWP-Home-Rollout-Ring";
    public static final String HOME_ROLLOUT_REVISION_HEADER = "X-DWP-Home-Rollout-Revision";

    private static final String TELEMETRY_PATH =
            "/api/platform/v1/observability/product-surface-events";
    private static final String WEB_VITALS_PATH =
            "/api/platform/v1/observability/web-vitals";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String TRACE_STATE_HEADER = "tracestate";
    private static final int MAX_TELEMETRY_BYTES = 32 * 1024;
    private static final List<String> UNTRUSTED_ROLLOUT_HEADERS = List.of(
            COHORT_HEADER,
            REVISION_HEADER,
            STATE_HEADER,
            HOME_RUNTIME_STATE_HEADER,
            HOME_ROLLOUT_RING_HEADER,
            HOME_ROLLOUT_REVISION_HEADER,
            "X-DWP-Rollout-Flags",
            "X-DWP-Rollout-Flag");

    private final FeatureRolloutEvaluationClient rolloutClient;
    private final GeneratedProductRouteCatalog routeCatalog;
    private final ObjectMapper objectMapper;

    public ProductSurfaceRolloutHeaderFilter(
            FeatureRolloutEvaluationClient rolloutClient,
            GeneratedProductRouteCatalog routeCatalog,
            ObjectMapper objectMapper) {
        this.rolloutClient = rolloutClient;
        this.routeCatalog = routeCatalog;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest sanitized = exchange.getRequest().mutate()
                .headers(headers -> UNTRUSTED_ROLLOUT_HEADERS.forEach(headers::remove))
                .build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitized).build();
        GeneratedProductRouteCatalog.Match routeMatch = routeCatalog.match(
                sanitized.getMethod() == null ? null : sanitized.getMethod().name(),
                sanitized.getURI().getPath(), sanitized.getURI().getRawQuery());
        if (sanitized.getMethod() != HttpMethod.OPTIONS
                && routeMatch.status() != GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED
                && routeMatch.status()
                    != GeneratedProductRouteCatalog.MatchStatus.LEGACY_EXEMPT) {
            if (routeMatch.status() != GeneratedProductRouteCatalog.MatchStatus.GOVERNED
                    || routeMatch.productKey() == null) {
                return complete(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE);
            }
            return evaluateProductAndForward(
                    sanitizedExchange, sanitized, chain, routeMatch.productKey())
                    .onErrorResume(
                            FeatureRolloutEvaluationClient.InvalidRolloutStateException.class,
                            ignored -> complete(exchange, HttpStatus.SERVICE_UNAVAILABLE))
                    .onErrorResume(
                            FeatureRolloutEvaluationClient.RolloutAuthorityUnavailableException.class,
                            ignored -> complete(exchange, HttpStatus.SERVICE_UNAVAILABLE));
        }
        if (sanitized.getMethod() != HttpMethod.POST
                || (!TELEMETRY_PATH.equals(sanitized.getURI().getPath())
                && !WEB_VITALS_PATH.equals(sanitized.getURI().getPath()))) {
            return chain.filter(sanitizedExchange);
        }

        Long tenantId = positiveLong(sanitized.getHeaders().getFirst(
                VerifiedIdentityFilter.TENANT_HEADER));
        if (tenantId == null) return complete(exchange, HttpStatus.UNAUTHORIZED);

        return DataBufferUtils.join(sanitized.getBody(), MAX_TELEMETRY_BYTES)
                .switchIfEmpty(Mono.error(new InvalidTelemetryProductException()))
                .flatMap(buffer -> WEB_VITALS_PATH.equals(sanitized.getURI().getPath())
                        ? evaluateWebVitalAndForward(
                                sanitizedExchange, sanitized, buffer, tenantId, chain)
                        : evaluateAndForward(
                                sanitizedExchange, sanitized, buffer, tenantId, chain))
                .onErrorResume(DataBufferLimitException.class,
                        ignored -> complete(exchange, HttpStatus.PAYLOAD_TOO_LARGE))
                .onErrorResume(InvalidTelemetryProductException.class,
                        ignored -> complete(exchange, HttpStatus.UNPROCESSABLE_ENTITY))
                .onErrorResume(
                        FeatureRolloutEvaluationClient.InvalidRolloutStateException.class,
                        ignored -> complete(exchange, HttpStatus.SERVICE_UNAVAILABLE))
                .onErrorResume(
                        FeatureRolloutEvaluationClient.RolloutAuthorityUnavailableException.class,
                        ignored -> complete(exchange, HttpStatus.SERVICE_UNAVAILABLE));
    }

    private Mono<Void> evaluateProductAndForward(
            ServerWebExchange exchange,
            ServerHttpRequest request,
            GatewayFilterChain chain,
            String productKey) {
        Long tenantId = positiveLong(request.getHeaders().getFirst(
                VerifiedIdentityFilter.TENANT_HEADER));
        if (tenantId == null) return complete(exchange, HttpStatus.UNAUTHORIZED);
        return rolloutClient.evaluateProducts(
                        tenantId,
                        List.of(productKey),
                        metadata(request))
                .flatMap(rollouts -> {
                    if (rollouts.size() != 1
                            || !productKey.equals(rollouts.getFirst().productKey())) {
                        return Mono.error(new InvalidTelemetryProductException());
                    }
                    return chain.filter(withTrustedHeaders(
                            exchange, request, rollouts.getFirst()));
                })
                .onErrorMap(InvalidTelemetryProductException.class,
                        ignored -> new FeatureRolloutEvaluationClient
                                .RolloutAuthorityUnavailableException());
    }

    private Mono<Void> evaluateAndForward(
            ServerWebExchange exchange,
            ServerHttpRequest request,
            DataBuffer buffer,
            long tenantId,
            GatewayFilterChain chain) {
        byte[] body = new byte[buffer.readableByteCount()];
        try {
            buffer.read(body);
        } finally {
            DataBufferUtils.release(buffer);
        }
        String productKey = productKey(body);
        return rolloutClient.evaluateProducts(tenantId, List.of(productKey), metadata(request))
                .flatMap(rollouts -> {
                    if (rollouts.size() != 1
                            || !productKey.equals(rollouts.getFirst().productKey())) {
                        return Mono.error(new InvalidTelemetryProductException());
                    }
                    return chain.filter(withTrustedHeaders(
                            exchange, request, body, rollouts.getFirst(), false));
                });
    }

    private Mono<Void> evaluateWebVitalAndForward(
            ServerWebExchange exchange,
            ServerHttpRequest request,
            DataBuffer buffer,
            long tenantId,
            GatewayFilterChain chain) {
        byte[] body = readAndRelease(buffer);
        if (!homeRouteGroup(body)) {
            return chain.filter(withBody(exchange, request, body));
        }
        return rolloutClient.evaluateProducts(
                        tenantId, List.of("workplace"), metadata(request))
                .flatMap(rollouts -> {
                    if (rollouts.size() != 1
                            || !"workplace".equals(rollouts.getFirst().productKey())) {
                        return Mono.error(new InvalidTelemetryProductException());
                    }
                    return chain.filter(withTrustedHeaders(
                            exchange, request, body, rollouts.getFirst(), true));
                });
    }

    private ServerWebExchange withTrustedHeaders(
            ServerWebExchange exchange,
            ServerHttpRequest request,
            ProductSurfaceContextDtos.ProductRollout rollout) {
        ServerHttpRequest trusted = request.mutate().headers(headers -> {
            headers.set(COHORT_HEADER, rollout.cohort());
            headers.set(REVISION_HEADER, rollout.opaqueRevision());
            headers.set(STATE_HEADER, rollout.state());
            if (homeRuntimeRoute(request)) {
                headers.set(HOME_RUNTIME_STATE_HEADER, homeState(rollout.state()));
                headers.set(HOME_ROLLOUT_RING_HEADER, homeRing(rollout.cohort()));
                headers.set(HOME_ROLLOUT_REVISION_HEADER, rollout.opaqueRevision());
            }
        }).build();
        return exchange.mutate().request(trusted).build();
    }

    private ServerWebExchange withTrustedHeaders(
            ServerWebExchange exchange,
            ServerHttpRequest request,
            byte[] body,
            ProductSurfaceContextDtos.ProductRollout rollout,
            boolean homeRuntime) {
        ServerHttpRequest decorated = new ServerHttpRequestDecorator(request) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.remove(HttpHeaders.TRANSFER_ENCODING);
                headers.setContentLength(body.length);
                headers.set(COHORT_HEADER, rollout.cohort());
                headers.set(REVISION_HEADER, rollout.opaqueRevision());
                headers.set(STATE_HEADER, rollout.state());
                if (homeRuntime) {
                    headers.set(HOME_RUNTIME_STATE_HEADER, homeState(rollout.state()));
                    headers.set(HOME_ROLLOUT_RING_HEADER, homeRing(rollout.cohort()));
                    headers.set(HOME_ROLLOUT_REVISION_HEADER, rollout.opaqueRevision());
                }
                return headers;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(body));
            }
        };
        return exchange.mutate().request(decorated).build();
    }

    private ServerWebExchange withBody(
            ServerWebExchange exchange,
            ServerHttpRequest request,
            byte[] body) {
        ServerHttpRequest decorated = new ServerHttpRequestDecorator(request) {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(super.getHeaders());
                headers.remove(HttpHeaders.TRANSFER_ENCODING);
                headers.setContentLength(body.length);
                return headers;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(body));
            }
        };
        return exchange.mutate().request(decorated).build();
    }

    private byte[] readAndRelease(DataBuffer buffer) {
        byte[] body = new byte[buffer.readableByteCount()];
        try {
            buffer.read(body);
            return body;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private String productKey(byte[] body) {
        try {
            JsonNode payload = objectMapper.readerFor(JsonNode.class)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
            if (payload == null || !payload.isObject()) {
                throw new InvalidTelemetryProductException();
            }
            JsonNode product = payload.get("productKey");
            if (product == null || !product.isTextual()) {
                throw new InvalidTelemetryProductException();
            }
            String value = product.textValue();
            FeatureRolloutEvaluationClient.uiFlag(value);
            return value;
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidTelemetryProductException();
        }
    }

    private boolean homeRouteGroup(byte[] body) {
        try {
            JsonNode payload = objectMapper.readerFor(JsonNode.class)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
            if (payload == null || !payload.isObject()) {
                throw new InvalidTelemetryProductException();
            }
            JsonNode routeGroup = payload.get("routeGroup");
            if (routeGroup == null || !routeGroup.isTextual()) {
                throw new InvalidTelemetryProductException();
            }
            String value = routeGroup.textValue();
            return "home".equals(value)
                    || value.startsWith("home.")
                    || value.endsWith(".home");
        } catch (IOException exception) {
            throw new InvalidTelemetryProductException();
        }
    }

    private FeatureRolloutEvaluationClient.RequestMetadata metadata(ServerHttpRequest request) {
        return new FeatureRolloutEvaluationClient.RequestMetadata(
                request.getHeaders().getFirst(CORRELATION_HEADER),
                request.getHeaders().getFirst(TRACE_PARENT_HEADER),
                request.getHeaders().getFirst(TRACE_STATE_HEADER));
    }

    private boolean homeRuntimeRoute(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        return (request.getMethod() == HttpMethod.GET
                && "/api/platform/v2/home".equals(path))
                || (request.getMethod() == HttpMethod.POST
                && ("/api/platform/v2/home/widget-actions:execute".equals(path)
                || "/api/platform/v2/home/shadow-receipts".equals(path)));
    }

    private String homeState(String state) {
        return switch (state) {
            case "100" -> "SHADOW_COMPARE";
            case "110" -> "READ_ONLY_ACTIVE";
            case "111" -> "COMMAND_CANARY";
            default -> "DISABLED";
        };
    }

    private String homeRing(String cohort) {
        return switch (cohort) {
            case "eligible-10" -> "INTERNAL";
            case "eligible-25" -> "PILOT";
            case "eligible-50" -> "EARLY_ADOPTER";
            case "eligible-90", "full" -> "GA";
            default -> "CONTROL";
        };
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private Mono<Void> complete(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return -94;
    }

    private static final class InvalidTelemetryProductException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
