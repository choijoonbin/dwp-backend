package com.dwp.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
public class AgentServiceIdentityFilter implements GlobalFilter, Ordered {

    public static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";

    private final String serviceToken;
    private final DelegatedIdentityAssertionIssuer identityAssertionIssuer;

    public AgentServiceIdentityFilter(
            @Value("${dwp.agent.service-token:}") String serviceToken) {
        this(serviceToken, "", "gateway-agent-v1", new ObjectMapper());
    }

    @Autowired
    public AgentServiceIdentityFilter(
            @Value("${dwp.agent.service-token:}") String serviceToken,
            @Value("${dwp.agent.identity-signing-secret:}") String identitySigningSecret,
            @Value("${dwp.agent.identity-key-id:gateway-agent-v1}") String identityKeyId,
            ObjectMapper objectMapper) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.identityAssertionIssuer = new DelegatedIdentityAssertionIssuer(
                identitySigningSecret == null ? "" : identitySigningSecret,
                identityKeyId == null ? "" : identityKeyId,
                objectMapper);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!requiresServiceIdentity(exchange.getRequest())) {
            return chain.filter(exchange);
        }
        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(SERVICE_TOKEN_HEADER);
                    headers.remove(DelegatedIdentityAssertionIssuer.HEADER);
                })
                .build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitizedRequest).build();

        if (serviceToken.isBlank()) {
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return exchange.getResponse().setComplete();
        }

        String delegatedIdentity = null;
        if (identityAssertionIssuer.enabled()) {
            try {
                delegatedIdentity = identityAssertionIssuer.issue(sanitizedRequest);
            } catch (IllegalArgumentException invalidIdentityEvidence) {
                exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                return exchange.getResponse().setComplete();
            }
        }
        String signedDelegatedIdentity = delegatedIdentity;

        ServerHttpRequest authenticatedRequest = sanitizedRequest.mutate()
                .headers(headers -> {
                    headers.set(SERVICE_TOKEN_HEADER, serviceToken);
                    if (signedDelegatedIdentity != null) {
                        headers.set(
                                DelegatedIdentityAssertionIssuer.HEADER,
                                signedDelegatedIdentity);
                    }
                })
                .build();
        return chain.filter(sanitizedExchange.mutate().request(authenticatedRequest).build());
    }

    private boolean requiresServiceIdentity(ServerHttpRequest request) {
        return request.getMethod() != HttpMethod.OPTIONS
                && request.getURI().getPath().startsWith("/api/agent/");
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
