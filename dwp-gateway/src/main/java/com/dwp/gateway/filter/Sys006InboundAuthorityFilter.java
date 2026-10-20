package com.dwp.gateway.filter;

import com.dwp.gateway.productsurface.Sys006GatewayRoutes;
import com.dwp.gateway.security.VerifiedIdentity;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/** Rejects browser-authored SYS-006 authority evidence before identity reconstruction. */
@Component
public final class Sys006InboundAuthorityFilter implements GlobalFilter, Ordered {
    public static final String INBOUND_CHECKED_ATTRIBUTE =
            Sys006InboundAuthorityFilter.class.getName() + ".inboundChecked";
    public static final String TENANT_SELECTOR_ATTRIBUTE =
            Sys006InboundAuthorityFilter.class.getName() + ".tenantSelector";
    public static final String VERIFIED_IDENTITY_ATTRIBUTE =
            Sys006InboundAuthorityFilter.class.getName() + ".verifiedIdentity";

    private static final String LEGACY_TENANT_HEADER = "X-Tenant-ID";
    private static final String EXPECTED_REVISION =
            ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER.toLowerCase(Locale.ROOT);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        if (!Sys006GatewayRoutes.claimsPath(request.getURI().getPath())
                || request.getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }
        boolean forgedAuthority = request.getHeaders().keySet().stream().anyMatch(name -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.startsWith("x-dwp-") && !EXPECTED_REVISION.equals(lower);
        });
        List<String> tenantSelectors = request.getHeaders().getOrEmpty(LEGACY_TENANT_HEADER);
        if (!request.getURI().getRawPath().equals(request.getURI().getPath())
                || forgedAuthority
                || tenantSelectors.size() != 1
                || !tenantSelectors.getFirst().matches("[1-9][0-9]{0,18}")) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }
        exchange.getAttributes().put(INBOUND_CHECKED_ATTRIBUTE, true);
        exchange.getAttributes().put(TENANT_SELECTOR_ATTRIBUTE, tenantSelectors.getFirst());
        var sanitized = request.mutate().headers(headers -> headers.remove(LEGACY_TENANT_HEADER))
                .build();
        return chain.filter(exchange.mutate().request(sanitized).build());
    }

    public static boolean validInbound(ServerWebExchange exchange) {
        if (!Boolean.TRUE.equals(exchange.getAttribute(INBOUND_CHECKED_ATTRIBUTE))) return false;
        Object value = exchange.getAttribute(VERIFIED_IDENTITY_ATTRIBUTE);
        if (!(value instanceof VerifiedIdentity identity)
                || blank(identity.tenantId())
                || blank(identity.userId())
                || blank(identity.personPublicId())) {
            return false;
        }
        String tenantSelector = exchange.getAttribute(TENANT_SELECTOR_ATTRIBUTE);
        var headers = exchange.getRequest().getHeaders();
        return identity.tenantId().equals(tenantSelector)
                && headers.getOrEmpty(LEGACY_TENANT_HEADER).isEmpty()
                && single(headers.getOrEmpty(VerifiedIdentityFilter.TENANT_HEADER),
                        identity.tenantId())
                && single(headers.getOrEmpty(VerifiedIdentityFilter.USER_HEADER),
                        identity.userId())
                && single(headers.getOrEmpty(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER),
                        identity.personPublicId())
                && correlation(headers.getOrEmpty("X-Correlation-ID"));
    }

    private static boolean single(List<String> values, String expected) {
        return values.equals(List.of(expected));
    }

    private static boolean correlation(List<String> values) {
        return values.size() == 1
                && values.getFirst().matches("[A-Za-z0-9._:-]{1,128}");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public int getOrder() {
        return -199;
    }
}
