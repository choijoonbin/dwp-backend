package com.dwp.gateway;

import com.dwp.gateway.filter.CsrfProtectionFilter;
import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import com.dwp.gateway.filter.RequiredHeaderFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextAggregationService;
import com.dwp.gateway.security.SessionVerifier;
import com.dwp.gateway.security.VerifiedIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProductSurfaceStepUpGatewayChainTest {

    private static final String REVISION = "psr-" + "a".repeat(64);

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void verifiedSessionAndCsrfFollowEachOfficialIssuerAuthorityContract(int bundleVersion) {
        AtomicInteger verifications = new AtomicInteger();
        SessionVerifier verifier = ignored -> {
            verifications.incrementAndGet();
            return Mono.just(new VerifiedIdentity(
                    "41", "7", List.of("WORKSPACE_MEMBER"), "TENANT"));
        };
        List<GlobalFilter> filters = filters(verifier, bundleVersion);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                issuerRequest()
                        .cookie(new HttpCookie("XSRF-TOKEN", "csrf-1"))
                        .header("X-XSRF-TOKEN", "csrf-1")
                        .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                                REVISION)
                        .header(ProductSurfaceDecisionContextFilter.ROUTE_HEADER, "spoofed")
                        .build());
        AtomicReference<ServerWebExchange> authCall = new AtomicReference<>();

        invoke(filters, 0, exchange, authCall).block();

        assertThat(verifications).hasValue(1);
        assertThat(authCall.get()).isNotNull();
        assertThat(authCall.get().getRequest().getHeaders().getFirst(
                VerifiedIdentityFilter.USER_HEADER)).isEqualTo("41");
        assertThat(authCall.get().getRequest().getHeaders().getFirst(
                VerifiedIdentityFilter.TENANT_HEADER)).isEqualTo("7");
        if (bundleVersion == 1) {
            assertThat(authCall.get().getRequest().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER)).isFalse();
        } else {
            assertThat(authCall.get().getRequest().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER))
                    .containsExactly(REVISION);
        }
        assertThat(authCall.get().getRequest().getHeaders().containsKey(
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER)).isFalse();
    }

    @Test
    void anonymousMissingCsrfAndWrongTenantNeverReachAuthIssuer() {
        AtomicInteger anonymousCalls = new AtomicInteger();
        MockServerWebExchange anonymous = MockServerWebExchange.from(
                issuerRequest()
                        .cookie(new HttpCookie("XSRF-TOKEN", "csrf-1"))
                        .header("X-XSRF-TOKEN", "csrf-1")
                        .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                                REVISION)
                        .build());
        invoke(filters(ignored -> Mono.empty()), 0, anonymous,
                new AtomicReference<>(), anonymousCalls).block();
        assertThat(anonymous.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymousCalls).hasValue(0);

        AtomicInteger verifierCalls = new AtomicInteger();
        SessionVerifier counted = ignored -> {
            verifierCalls.incrementAndGet();
            return Mono.just(new VerifiedIdentity(
                    "41", "7", List.of("WORKSPACE_MEMBER"), "TENANT"));
        };
        MockServerWebExchange noCsrf = MockServerWebExchange.from(
                issuerRequest()
                        .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                                REVISION)
                        .build());
        AtomicInteger noCsrfAuthCalls = new AtomicInteger();
        invoke(filters(counted), 0, noCsrf, new AtomicReference<>(), noCsrfAuthCalls).block();
        assertThat(noCsrf.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(verifierCalls).hasValue(0);
        assertThat(noCsrfAuthCalls).hasValue(0);

        MockServerWebExchange wrongTenant = MockServerWebExchange.from(
                issuerRequest()
                        .header("X-Tenant-ID", "8")
                        .cookie(new HttpCookie("XSRF-TOKEN", "csrf-1"))
                        .header("X-XSRF-TOKEN", "csrf-1")
                        .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                                REVISION)
                        .build());
        AtomicInteger wrongTenantAuthCalls = new AtomicInteger();
        invoke(filters(counted), 0, wrongTenant,
                new AtomicReference<>(), wrongTenantAuthCalls).block();
        assertThat(wrongTenant.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(wrongTenantAuthCalls).hasValue(0);
    }

    private List<GlobalFilter> filters(SessionVerifier verifier) {
        return filters(verifier, 3);
    }

    private List<GlobalFilter> filters(SessionVerifier verifier, int bundleVersion) {
        ObjectMapper objectMapper = new ObjectMapper();
        return List.of(
                new RequiredHeaderFilter(),
                new CsrfProtectionFilter(),
                new VerifiedIdentityFilter(verifier),
                new ProductSurfaceDecisionContextFilter(
                        mock(ProductSurfaceContextAggregationService.class),
                        new GeneratedProductRouteCatalog(
                                objectMapper,
                                new FileSystemResource(
                                        "../contracts/product-authorization/"
                                                + "product-surfaces-v1.bundle-v"
                                                + bundleVersion + ".json")),
                        objectMapper));
    }

    private MockServerHttpRequest.BodyBuilder issuerRequest() {
        return MockServerHttpRequest.post(
                "/api/auth/product-surface-step-up-challenges");
    }

    private Mono<Void> invoke(
            List<GlobalFilter> filters,
            int index,
            ServerWebExchange exchange,
            AtomicReference<ServerWebExchange> terminal) {
        return invoke(filters, index, exchange, terminal, new AtomicInteger());
    }

    private Mono<Void> invoke(
            List<GlobalFilter> filters,
            int index,
            ServerWebExchange exchange,
            AtomicReference<ServerWebExchange> terminal,
            AtomicInteger terminalCalls) {
        if (index == filters.size()) {
            terminal.set(exchange);
            terminalCalls.incrementAndGet();
            return Mono.empty();
        }
        GatewayFilterChain next = nextExchange -> invoke(
                filters, index + 1, nextExchange, terminal, terminalCalls);
        return filters.get(index).filter(exchange, next);
    }
}
