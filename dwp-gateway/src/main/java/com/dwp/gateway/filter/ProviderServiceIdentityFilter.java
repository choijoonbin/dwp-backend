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

import java.util.List;

@Component
public class ProviderServiceIdentityFilter implements GlobalFilter, Ordered {

    private static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    private static final List<String> SUPPORT_INTERNAL_HEADERS = List.of(
            "X-DWP-Support-Validation-Token",
            "X-DWP-Support-Resource-Method",
            "X-DWP-Support-Resource-Path");

    private final String serviceToken;

    public ProviderServiceIdentityFilter(
            @Value("${dwp.provider.service-token:}") String serviceToken) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!requiresServiceIdentity(exchange.getRequest())) return chain.filter(exchange);
        ServerHttpRequest sanitized = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(SERVICE_TOKEN_HEADER);
                    SUPPORT_INTERNAL_HEADERS.forEach(headers::remove);
                })
                .build();
        if (serviceToken.isBlank()) {
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return exchange.getResponse().setComplete();
        }
        ServerHttpRequest authenticated = sanitized.mutate()
                .header(SERVICE_TOKEN_HEADER, serviceToken)
                .build();
        return chain.filter(exchange.mutate().request(authenticated).build());
    }

    private boolean requiresServiceIdentity(ServerHttpRequest request) {
        return request.getMethod() != HttpMethod.OPTIONS
                && request.getURI().getPath().startsWith("/api/provider/");
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
