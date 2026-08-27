package com.dwp.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class ServiceIdentitySanitizingFilter implements GlobalFilter, Ordered {

    public static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    public static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(SERVICE_TOKEN_HEADER);
                    headers.remove(SERVICE_IDENTITY_HEADER);
                    headers.remove(DelegatedIdentityAssertionIssuer.HEADER);
                })
                .build();
        return chain.filter(exchange.mutate().request(sanitizedRequest).build());
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
