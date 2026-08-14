package com.dwp.gateway.config;

import com.dwp.observability.api.ApiHistoryAttributes;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Configuration
public class GatewayResilienceConfiguration {

    @Bean
    KeyResolver enterpriseKeyResolver() {
        return exchange -> Mono.just(identityKey(exchange));
    }

    private String identityKey(ServerWebExchange exchange) {
        String tenant = attribute(exchange, ApiHistoryAttributes.TENANT_ID);
        String actor = attribute(exchange, ApiHistoryAttributes.ACTOR_ID);
        if (tenant != null && actor != null) return "tenant:" + tenant + ":actor:" + actor;
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        String address = remote == null || remote.getAddress() == null
                ? "unknown"
                : remote.getAddress().getHostAddress();
        return "network:" + address;
    }

    private String attribute(ServerWebExchange exchange, String name) {
        Object value = exchange.getAttribute(name);
        return value == null ? null : value.toString();
    }
}
