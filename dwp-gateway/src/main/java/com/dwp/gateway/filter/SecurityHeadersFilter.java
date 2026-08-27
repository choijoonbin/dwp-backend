package com.dwp.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URISyntaxException;

@Component
public class SecurityHeadersFilter implements GlobalFilter, Ordered {

    private static final String CSP_PREFIX = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; "
            + "connect-src 'self'";
    private static final String CSP_SUFFIX = "; object-src 'none'; base-uri 'self'; "
            + "frame-ancestors 'none'; form-action 'self'";
    private static final String PERMISSIONS_POLICY = "camera=(self), microphone=(self), "
            + "display-capture=(self), geolocation=(), payment=(), usb=()";

    private final String contentSecurityPolicy;

    public SecurityHeadersFilter(
            @Value("${dwp.meeting.livekit.client-url:}") String liveKitClientUrl) {
        String liveKitOrigin = trustedWssOrigin(liveKitClientUrl);
        this.contentSecurityPolicy = CSP_PREFIX
                + (liveKitOrigin.isEmpty() ? "" : " " + liveKitOrigin)
                + CSP_SUFFIX;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set("Content-Security-Policy", contentSecurityPolicy);
        headers.set("Permissions-Policy", PERMISSIONS_POLICY);
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        if (exchange.getRequest().getSslInfo() != null) {
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload");
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static String trustedWssOrigin(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI configured = new URI(value.trim());
            int port = configured.getPort();
            if (!"wss".equalsIgnoreCase(configured.getScheme())
                    || configured.getHost() == null
                    || configured.getHost().isBlank()
                    || configured.getUserInfo() != null
                    || port > 65_535) {
                return "";
            }
            return new URI("wss", null, configured.getHost(), port, null, null, null)
                    .toASCIIString();
        } catch (IllegalArgumentException | URISyntaxException ignored) {
            return "";
        }
    }
}
