package com.dwp.gateway.filter;

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
public class RequiredHeaderFilter implements GlobalFilter, Ordered {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/csrf",
            "/api/auth/policy",
            "/api/auth/idp",
            "/api/auth/oidc/",
            "/actuator/");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (request.getMethod() == HttpMethod.OPTIONS || isPublic(path)) {
            return chain.filter(exchange);
        }

        String tenantId = request.getHeaders().getFirst(TENANT_HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    private boolean isPublic(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
