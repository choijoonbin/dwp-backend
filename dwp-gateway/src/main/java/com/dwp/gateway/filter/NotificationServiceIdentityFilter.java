package com.dwp.gateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class NotificationServiceIdentityFilter implements GlobalFilter, Ordered {

    private static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    private static final String SOURCE_SERVICE_HEADER = "X-DWP-Source-Service";
    private static final String CALLER_SERVICE_HEADER = "X-DWP-Caller-Service";
    private static final String GATEWAY_SERVICE = "dwp-gateway";

    private final String serviceToken;

    public NotificationServiceIdentityFilter(
            @Value("${dwp.notification.service-token:}") String serviceToken) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!requiresServiceIdentity(exchange.getRequest())) return chain.filter(exchange);
        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(SERVICE_TOKEN_HEADER);
                    headers.remove(SOURCE_SERVICE_HEADER);
                    headers.remove(CALLER_SERVICE_HEADER);
                })
                .build();
        if (serviceToken.isBlank()) {
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return exchange.getResponse().setComplete();
        }
        ServerHttpRequest authenticatedRequest = sanitizedRequest.mutate()
                .header(SERVICE_TOKEN_HEADER, serviceToken)
                .header(CALLER_SERVICE_HEADER, GATEWAY_SERVICE)
                .header(SOURCE_SERVICE_HEADER, GATEWAY_SERVICE)
                .build();
        return chain.filter(exchange.mutate().request(authenticatedRequest).build());
    }

    private boolean requiresServiceIdentity(ServerHttpRequest request) {
        return request.getMethod() != HttpMethod.OPTIONS
                && request.getURI().getPath().startsWith("/api/notifications/");
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
