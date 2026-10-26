package com.dwp.gateway;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import com.dwp.gateway.filter.PlatformServiceIdentityFilter;
import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import com.dwp.gateway.filter.ProductSurfaceRolloutHeaderFilter;
import com.dwp.gateway.filter.Sys008InboundAuthorityFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextAggregationService;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import com.dwp.gateway.productsurface.Sys008GatewayRoutes;
import com.dwp.gateway.security.VerifiedIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Sys008GatewayAuthorityTest {
    private static final String SUBJECT = "10000000-0000-4000-8000-000000000041";
    private static final String CORRELATION = "10000000-0000-4000-8000-000000000099";
    private static final String IDEMPOTENCY_KEY = "sys008-idempotency-0001";
    private static final OffsetDateTime EXPIRY = OffsetDateTime.of(
            2026, 10, 26, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    @SuppressWarnings("unchecked")
    void exactTwentyBindingsHaveClosedCardinalityAndAreTenantFeatureGoverned() throws Exception {
        assertThat(Sys008GatewayRoutes.BINDINGS).hasSize(20);
        assertThat(Sys008GatewayRoutes.BINDINGS.stream().map(Sys008GatewayRoutes.Binding::api))
                .hasSize(20).doesNotHaveDuplicates();
        assertThat(Sys008GatewayRoutes.BINDINGS.stream().map(Sys008GatewayRoutes.Binding::pep))
                .hasSize(20).doesNotHaveDuplicates();
        assertThat(Sys008GatewayRoutes.BINDINGS.stream()
                .map(Sys008GatewayRoutes.Binding::routeContractKey))
                .hasSize(20).doesNotHaveDuplicates();
        assertThat(Sys008GatewayRoutes.BINDINGS)
                .filteredOn(binding -> "PAGE".equals(binding.routeKind())).hasSize(1);
        assertThat(Sys008GatewayRoutes.BINDINGS)
                .filteredOn(binding -> "DATA".equals(binding.routeKind())).hasSize(3);
        assertThat(Sys008GatewayRoutes.BINDINGS)
                .filteredOn(Sys008GatewayRoutes.Binding::stateChanging).hasSize(16);
        assertThat(Sys008GatewayRoutes.BINDINGS)
                .filteredOn(Sys008GatewayRoutes.Binding::casCommand).hasSize(12);
        assertThat(Sys008GatewayRoutes.BINDINGS.stream()
                .filter(binding -> Sys008GatewayRoutes.commandBoundStepUp(
                        binding.routeContractKey()) != null)).hasSize(7);

        var governed = ProductSurfaceDecisionContextFilter.class.getDeclaredField(
                "TENANT_FEATURE_GOVERNED_ROUTE_KEYS");
        governed.setAccessible(true);
        assertThat((Set<String>) governed.get(null)).containsAll(Sys008GatewayRoutes.ROUTE_KEYS);
    }

    @Test
    void generatedV60CatalogResolvesOnlyTheExactSealedMappingsAndPathConstraints() {
        var catalog = new GeneratedProductRouteCatalog(
                new ObjectMapper(),
                new FileSystemResource(
                        "../contracts/product-authorization/product-surfaces-v1.bundle-v60.json"));

        for (var binding : Sys008GatewayRoutes.BINDINGS) {
            String path = actualPath(binding.browserPath());
            var match = catalog.match(binding.method(), path);
            assertThat(match.status()).as(binding.api())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(Sys008GatewayRoutes.matches(match.uniqueRoute(), binding.method(), path))
                    .as(binding.api()).isTrue();
        }

        var binding = Sys008GatewayRoutes.BINDINGS.stream()
                .filter(candidate -> candidate.browserPath().contains("{connectorId}"))
                .findFirst().orElseThrow();
        var generated = catalog.match(binding.method(), actualPath(binding.browserPath()))
                .uniqueRoute();
        assertThat(Sys008GatewayRoutes.matches(generated, binding.method(),
                binding.browserPath().replace("{connectorId}", "not-a-uuid")
                        .replace("{version}", "01"))).isFalse();
        var versioned = Sys008GatewayRoutes.BINDINGS.stream()
                .filter(candidate -> candidate.browserPath().contains("{version}"))
                .findFirst().orElseThrow();
        var versionedRoute = catalog.match(
                versioned.method(), actualPath(versioned.browserPath())).uniqueRoute();
        assertThat(Sys008GatewayRoutes.matches(versionedRoute, versioned.method(),
                versioned.browserPath()
                        .replace("{connectorId}",
                                "10000000-0000-4000-8000-000000000001")
                        .replace("{version}", "01"))).isFalse();
        assertThat(Sys008GatewayRoutes.claimsPath(
                "/api/platform/v1/admin/hris/connectors/unregistered-operation")).isTrue();
    }

    @Test
    void everyBindingForwardsOnlyReconstructedTrustedContext() {
        for (var binding : Sys008GatewayRoutes.BINDINGS) {
            var fixture = new Fixture(binding);
            var exchange = fixture.run(fixture.request());

            assertThat(exchange.getResponse().getStatusCode()).as(binding.api()).isNull();
            verify(fixture.authority, times(1)).evaluateProductTrusted(any(), any());
            assertThat(fixture.ownerCalls.get()).as(binding.api()).isEqualTo(1);
            var forwarded = fixture.forwarded.get();
            assertThat(forwarded.getHeaders()).doesNotContainKey("X-Tenant-ID");
            assertThat(forwarded.getHeaders().getFirst(VerifiedIdentityFilter.TENANT_HEADER))
                    .isEqualTo("7");
            assertThat(forwarded.getHeaders().getFirst(VerifiedIdentityFilter.USER_HEADER))
                    .isEqualTo("41");
            assertThat(forwarded.getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER))
                    .isEqualTo(SUBJECT);
            assertThat(forwarded.getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                    .isEqualTo(binding.routeContractKey());
            assertThat(forwarded.getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.CONTEXT_HEADER))
                    .isEqualTo("trusted-context");
            assertThat(forwarded.getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.SCOPE_HEADER))
                    .isEqualTo("trusted-scope");
            assertThat(forwarded.getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                    .isEqualTo("CONFIG_SCOPE");
            assertThat(forwarded.getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .isEqualTo(Sys008GatewayRoutes.PURPOSE);
            assertThat(forwarded.getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER))
                    .matches("[1-9][0-9]*");
            assertThat(forwarded.getHeaders().getFirst("X-Correlation-ID"))
                    .isEqualTo(CORRELATION);
            if (binding.stateChanging()) {
                assertThat(forwarded.getHeaders().getOrEmpty("Idempotency-Key"))
                        .containsExactly(IDEMPOTENCY_KEY);
            }
            if (binding.casCommand()) {
                assertThat(forwarded.getHeaders().getOrEmpty("If-Match"))
                        .containsExactly("\"revision-1\"");
                assertThat(forwarded.getHeaders().getOrEmpty(
                        ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER))
                        .containsExactly("revision-1");
            } else {
                assertThat(forwarded.getHeaders()).doesNotContainKey(
                        ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER);
            }
        }
    }

    @Test
    void forgedAuthorityPurposeSubstitutionAndInvalidCommandHeadersFailBeforeAuth() {
        var binding = Sys008GatewayRoutes.BINDINGS.get(2);
        for (String header : Set.of(
                VerifiedIdentityFilter.TENANT_HEADER,
                VerifiedIdentityFilter.USER_HEADER,
                ProductSurfaceDecisionContextFilter.CONTEXT_HEADER,
                ProductSurfaceDecisionContextFilter.SCOPE_HEADER,
                ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER,
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER,
                PlatformServiceIdentityFilter.SERVICE_TOKEN_HEADER,
                "X-DWP-Connector-Authority")) {
            var fixture = new Fixture(binding);
            var exchange = fixture.run(fixture.request().header(header, "spoof"));
            assertThat(exchange.getResponse().getStatusCode()).as(header)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            fixture.assertNoAuthorityOrOwner();
        }

        var purposeFixture = new Fixture(binding);
        var purpose = purposeFixture.run(purposeFixture.baseRequest()
                .header("X-Purpose-Code", "OTHER"));
        assertThat(purpose.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        purposeFixture.assertNoAuthorityOrOwner();

        var cas = Sys008GatewayRoutes.BINDINGS.stream()
                .filter(Sys008GatewayRoutes.Binding::casCommand).findFirst().orElseThrow();
        var missingIfMatch = new Fixture(cas);
        var invalid = missingIfMatch.run(missingIfMatch.baseRequest()
                .header("X-Purpose-Code", Sys008GatewayRoutes.PURPOSE)
                .header("Idempotency-Key", IDEMPOTENCY_KEY));
        assertThat(invalid.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        missingIfMatch.assertNoAuthorityOrOwner();

        var command = Sys008GatewayRoutes.BINDINGS.stream()
                .filter(candidate -> candidate.stateChanging() && !candidate.casCommand())
                .findFirst().orElseThrow();
        var shortKey = new Fixture(command);
        invalid = shortKey.run(shortKey.baseRequest()
                .header("X-Purpose-Code", Sys008GatewayRoutes.PURPOSE)
                .header("Idempotency-Key", "too-short"));
        assertThat(invalid.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        shortKey.assertNoAuthorityOrOwner();
    }

    @Test
    void defaultOffStillRequiresAuthAndNeverFallsThroughOnActivationRequired() {
        var fixture = new Fixture(Sys008GatewayRoutes.BINDINGS.getFirst());
        fixture.decision = ProductSurfaceContextDtos.Decision.ACTIVATION_REQUIRED;
        var exchange = fixture.run(fixture.request());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(fixture.authority, times(1)).evaluateProductTrusted(any(), any());
        assertThat(fixture.ownerCalls.get()).isZero();
        assertThat(new Sys008InboundAuthorityFilter().getOrder())
                .isGreaterThan(-200).isLessThan(-100);
    }

    private static String actualPath(String template) {
        return template
                .replace("{connectorId}", "10000000-0000-4000-8000-000000000001")
                .replace("{executionId}", "10000000-0000-4000-8000-000000000002")
                .replace("{version}", "1");
    }

    private static final class Fixture {
        final Sys008GatewayRoutes.Binding binding;
        final String actualPath;
        final ProductSurfaceContextAggregationService authority = mock(
                ProductSurfaceContextAggregationService.class);
        final GeneratedProductRouteCatalog catalog = mock(GeneratedProductRouteCatalog.class);
        final AtomicInteger ownerCalls = new AtomicInteger();
        final AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
        ProductSurfaceContextDtos.Decision decision = ProductSurfaceContextDtos.Decision.ALLOWED;

        Fixture(Sys008GatewayRoutes.Binding binding) {
            this.binding = binding;
            this.actualPath = actualPath(binding.browserPath());
            var route = new GeneratedProductRouteCatalog.Route(
                    binding.routeContractKey(), null, "hcm", "hcm.management",
                    binding.method(), binding.browserPath(), binding.routeKind(),
                    binding.stateChanging(), false, false, false,
                    Pattern.compile("^" + Pattern.quote(actualPath) + "$"),
                    Pattern.compile("^" + Pattern.quote(actualPath) + "$"), Map.of());
            when(catalog.match(any(), any(), any())).thenReturn(
                    new GeneratedProductRouteCatalog.Match(
                            GeneratedProductRouteCatalog.MatchStatus.GOVERNED,
                            List.of(route)));
            when(authority.evaluateProductTrusted(any(), any())).thenAnswer(ignored -> {
                var scope = new ProductSurfaceContextDtos.EffectiveScope(
                        "trusted-scope", "CONFIG_SCOPE", "trusted connector scope",
                        true, false, EXPIRY);
                var data = new ProductSurfaceContextDtos.ProductEvaluationData(
                        decision, decision.name(), "decision-1", null, "grant", scope,
                        false, EXPIRY, null, null, null, EXPIRY);
                var revisions = new ProductSurfaceContextDtos.SourceRevisions(
                        "auth-1", "policy-1", "relationship-1", "population-1",
                        "tenant-1", null);
                return Mono.just(
                        new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                                data, "trusted-context", scope, revisions, false));
            });
        }

        ProductSurfaceDecisionContextFilter filter() {
            return new ProductSurfaceDecisionContextFilter(
                    authority, catalog, new ObjectMapper(), GatewayDenialAuditSink.NOOP);
        }

        MockServerHttpRequest.BaseBuilder<?> baseRequest() {
            var builder = MockServerHttpRequest.method(
                            HttpMethod.valueOf(binding.method()), actualPath)
                    .header("X-Tenant-ID", "7")
                    .header("X-Correlation-ID", CORRELATION);
            if (binding.stateChanging()) {
                builder.header(
                        ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        "decision-1");
            }
            return builder;
        }

        MockServerHttpRequest.BaseBuilder<?> request() {
            var builder = baseRequest()
                    .header("X-Purpose-Code", Sys008GatewayRoutes.PURPOSE);
            if (binding.stateChanging()) {
                builder.header("Idempotency-Key", IDEMPOTENCY_KEY);
                if (binding.casCommand()) builder.header("If-Match", "\"revision-1\"");
            }
            return builder;
        }

        MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request) {
            var exchange = MockServerWebExchange.from(request);
            var identity = new VerifiedIdentity(
                    "41", "7", List.of("HR_ADMIN"), List.of(), List.of(), List.of(),
                    SUBJECT, null, false, "session", "TENANT");
            var identityFilter = new VerifiedIdentityFilter(ignored -> Mono.just(identity));
            new Sys008InboundAuthorityFilter().filter(exchange, clean ->
                    identityFilter.filter(clean, verified -> {
                        var rollout = verified.getRequest().mutate().headers(headers ->
                                headers.set(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000"))
                                .build();
                        return filter().filter(
                                verified.mutate().request(rollout).build(), owner -> {
                                    ownerCalls.incrementAndGet();
                                    forwarded.set(owner.getRequest());
                                    return Mono.empty();
                                });
                    })).block();
            return exchange;
        }

        void assertNoAuthorityOrOwner() {
            verify(authority, never()).evaluateProductTrusted(any(), any());
            assertThat(ownerCalls.get()).isZero();
        }
    }
}
