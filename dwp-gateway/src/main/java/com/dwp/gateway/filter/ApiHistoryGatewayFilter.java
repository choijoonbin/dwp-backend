package com.dwp.gateway.filter;

import com.dwp.observability.api.ApiHistoryAttributes;
import com.dwp.observability.api.ApiHistoryEvent;
import com.dwp.observability.api.ApiHistoryPrivacyHasher;
import com.dwp.observability.api.ApiHistoryPublisher;
import com.dwp.observability.api.ApiHistorySanitizer;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/** Captures the client-facing gateway hop, including rejections before route dispatch. */
@Component
public class ApiHistoryGatewayFilter implements GlobalFilter, Ordered {

    private static final String POLICY_VERSION = "dwp-api-history-v1";

    private final ApiHistoryPublisher publisher;
    private final ApiHistoryPrivacyHasher privacyHasher;
    private final String serviceName;
    private final String serviceVersion;
    private final String serviceInstance;
    private final String environment;

    public ApiHistoryGatewayFilter(
            ApiHistoryPublisher publisher,
            @Value("${dwp.observability.api-history.privacy-hash-secret:}") String privacyHashSecret,
            @Value("${spring.application.name:dwp-gateway}") String serviceName,
            @Value("${info.app.version:${spring.application.version:unknown}}") String serviceVersion,
            @Value("${dwp.observability.api-history.service-instance:${HOSTNAME:local}}") String serviceInstance,
            @Value("${dwp.observability.api-history.environment:${DWP_ENVIRONMENT:local}}") String environment) {
        this.publisher = publisher;
        this.privacyHasher = new ApiHistoryPrivacyHasher(privacyHashSecret);
        this.serviceName = ApiHistorySanitizer.truncate(serviceName, 120);
        this.serviceVersion = ApiHistorySanitizer.truncate(serviceVersion, 60);
        this.serviceInstance = ApiHistorySanitizer.truncate(serviceInstance, 160);
        this.environment = ApiHistorySanitizer.truncate(environment, 40);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Instant occurredAt = Instant.now();
        long started = System.nanoTime();
        AtomicLong responseBytes = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ServerHttpResponse decorated = countingResponse(exchange.getResponse(), responseBytes);
        ServerWebExchange observed = exchange.mutate().response(decorated).build();
        return chain.filter(observed)
                .doOnError(failure::set)
                .doFinally(signal -> publisher.publish(event(
                        observed,
                        occurredAt,
                        Instant.now(),
                        Math.max(0, (System.nanoTime() - started) / 1_000_000),
                        responseBytes.get(),
                        failure.get(),
                        signal == SignalType.CANCEL)));
    }

    private ServerHttpResponse countingResponse(
            ServerHttpResponse response,
            AtomicLong responseBytes) {
        return new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return super.writeWith(Flux.from(body).doOnNext(
                        buffer -> responseBytes.addAndGet(buffer.readableByteCount())));
            }

            @Override
            public Mono<Void> writeAndFlushWith(
                    Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return super.writeAndFlushWith(Flux.from(body).map(inner -> Flux.from(inner)
                        .doOnNext(buffer -> responseBytes.addAndGet(buffer.readableByteCount()))));
            }
        };
    }

    private ApiHistoryEvent event(
            ServerWebExchange exchange,
            Instant occurredAt,
            Instant completedAt,
            long durationMs,
            long responseBytes,
            Throwable failure,
            boolean cancelled) {
        ServerHttpRequest request = exchange.getRequest();
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        int statusCode = cancelled ? 499 : status == null ? 200 : status.value();
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        String userAgent = request.getHeaders().getFirst(HttpHeaders.USER_AGENT);
        String actorId = attribute(exchange, ApiHistoryAttributes.ACTOR_ID);
        String actorType = firstNonBlank(
                attribute(exchange, ApiHistoryAttributes.ACTOR_TYPE),
                actorId == null ? "ANONYMOUS" : "USER");
        String tenantValue = firstNonBlank(
                attribute(exchange, ApiHistoryAttributes.TENANT_ID),
                request.getHeaders().getFirst("X-Tenant-ID"));
        return new ApiHistoryEvent(
                UUID.randomUUID(),
                occurredAt,
                completedAt,
                positiveLong(tenantValue),
                actorType,
                ApiHistorySanitizer.truncate(actorId, 160),
                firstNonBlank(attribute(exchange, ApiHistoryAttributes.AUTH_TYPE), authType(request)),
                serviceName,
                serviceVersion,
                serviceInstance,
                environment,
                "GATEWAY",
                route == null ? null : ApiHistorySanitizer.truncate(route.getId(), 120),
                request.getMethod() == null ? "UNKNOWN" : request.getMethod().name(),
                ApiHistorySanitizer.normalizePath(request.getURI().getPath()),
                ApiHistorySanitizer.normalizePath(request.getURI().getPath()),
                request.getURI().getScheme(),
                request.getHeaders().getFirst("Forwarded") == null ? "HTTP" : "FORWARDED_HTTP",
                statusCode,
                cancelled ? "CANCELLED" : outcome(statusCode, failure),
                durationMs,
                nonNegative(request.getHeaders().getContentLength()),
                responseBytes,
                attribute(exchange, ApiHistoryAttributes.CORRELATION_ID),
                attribute(exchange, ApiHistoryAttributes.TRACE_ID),
                attribute(exchange, ApiHistoryAttributes.SPAN_ID),
                attribute(exchange, ApiHistoryAttributes.PARENT_SPAN_ID),
                privacyHasher.hash(remoteAddress(request)),
                ApiHistorySanitizer.userAgentFamily(userAgent),
                privacyHasher.hash(userAgent),
                failure == null
                        ? null
                        : ApiHistorySanitizer.truncate(failure.getClass().getSimpleName(), 80),
                POLICY_VERSION);
    }

    private static String authType(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            return "BEARER";
        }
        return request.getCookies().isEmpty() ? "NONE" : "SESSION";
    }

    private static String remoteAddress(ServerHttpRequest request) {
        InetSocketAddress address = request.getRemoteAddress();
        return address == null || address.getAddress() == null
                ? null
                : address.getAddress().getHostAddress();
    }

    private static String outcome(int status, Throwable failure) {
        if (failure != null || status >= 500) return "SERVER_ERROR";
        if (status >= 400) return "CLIENT_ERROR";
        if (status >= 300) return "REDIRECTION";
        return "SUCCESS";
    }

    private static String attribute(ServerWebExchange exchange, String name) {
        Object value = exchange.getAttribute(name);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private static Long nonNegative(long value) {
        return value < 0 ? null : value;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
