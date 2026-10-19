package com.dwp.gateway.filter;

import com.dwp.gateway.productsurface.Per004GatewayRoutes;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Locale;

/** Runs before any identity/rollout sanitizer can erase duplicate or spoofed input. */
@Component
public final class Per004InboundAuthorityFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        if (!Per004GatewayRoutes.isPath(request.getURI().getPath())
                || request.getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }
        boolean authorityInput = request.getHeaders().keySet().stream().anyMatch(name -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.startsWith("x-dwp-") || lower.equals("x-purpose-code");
        });
        var tenantSelectors = request.getHeaders().getOrEmpty("X-Tenant-ID");
        if (!request.getURI().getRawPath().equals(request.getURI().getPath())
                || authorityInput || tenantSelectors.size() > 1
                || (!tenantSelectors.isEmpty()
                && !tenantSelectors.getFirst().matches("[1-9][0-9]{0,18}"))) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }
        exchange.getAttributes().put(Per004GatewayRoutes.INBOUND_CHECKED_ATTRIBUTE, true);
        if (!tenantSelectors.isEmpty()) {
            exchange.getAttributes().put(Per004GatewayRoutes.TENANT_SELECTOR_ATTRIBUTE,
                    tenantSelectors.getFirst());
        }
        // RequiredHeaderFilter already checked the legacy selector. It is never owner authority.
        var sanitized = request.mutate().headers(headers -> headers.remove("X-Tenant-ID"))
                .build();
        return chain.filter(exchange.mutate().request(sanitized).build());
    }

    @Override
    public int getOrder() { return -199; }
}
