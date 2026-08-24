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

    private static final String TELEMETRY_PATH =
            "/api/platform/v1/observability/product-surface-events";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String TRACE_STATE_HEADER = "tracestate";
    private static final int MAX_TELEMETRY_BYTES = 32 * 1024;
    private static final List<String> UNTRUSTED_ROLLOUT_HEADERS = List.of(
            COHORT_HEADER,
            REVISION_HEADER,
            STATE_HEADER,
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
                && routeMatch.status() != GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED) {
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
                || !TELEMETRY_PATH.equals(sanitized.getURI().getPath())) {
            return chain.filter(sanitizedExchange);
        }

        Long tenantId = positiveLong(sanitized.getHeaders().getFirst(
                VerifiedIdentityFilter.TENANT_HEADER));
        if (tenantId == null) return complete(exchange, HttpStatus.UNAUTHORIZED);

        return DataBufferUtils.join(sanitized.getBody(), MAX_TELEMETRY_BYTES)
                .switchIfEmpty(Mono.error(new InvalidTelemetryProductException()))
                .flatMap(buffer -> evaluateAndForward(
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
                            exchange, request, body, rollouts.getFirst()));
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
        }).build();
        return exchange.mutate().request(trusted).build();
    }

    private ServerWebExchange withTrustedHeaders(
            ServerWebExchange exchange,
            ServerHttpRequest request,
            byte[] body,
            ProductSurfaceContextDtos.ProductRollout rollout) {
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
                return headers;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(body));
            }
        };
        return exchange.mutate().request(decorated).build();
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

    private FeatureRolloutEvaluationClient.RequestMetadata metadata(ServerHttpRequest request) {
        return new FeatureRolloutEvaluationClient.RequestMetadata(
                request.getHeaders().getFirst(CORRELATION_HEADER),
                request.getHeaders().getFirst(TRACE_PARENT_HEADER),
                request.getHeaders().getFirst(TRACE_STATE_HEADER));
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
