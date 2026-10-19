package com.dwp.gateway;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import com.dwp.gateway.filter.Per004InboundAuthorityFilter;
import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import com.dwp.gateway.filter.ProductSurfaceRolloutHeaderFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.Per004DecisionContextForwarder;
import com.dwp.gateway.productsurface.Per004FieldDecisionPort;
import com.dwp.gateway.productsurface.Per004GatewayRoutes;
import com.dwp.gateway.productsurface.ProductSurfaceContextAggregationService;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import com.dwp.gateway.security.VerifiedIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Per004GatewayAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-10-19T09:00:00Z");
    private static final OffsetDateTime EXPIRY = NOW.plusSeconds(60).atOffset(ZoneOffset.UTC);
    private static final String FORMS = "route.hcm.operations.performance-forms-scales.data";
    private static final String SETTINGS = "route.hcm.configuration.settings-performance.data";
    private static final String SUBJECT = "10000000-0000-4000-8000-000000000041";
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "route.hcm.operations.performance-forms-scales.page",
            "route.hcm.operations.performance-forms-scales.data",
            "route.hcm.configuration.settings-performance.page",
            "route.hcm.configuration.settings-performance.data"})
    void eachExactRouteMakesOnePeopleCallWithBoundCanonicalFieldDecisions(String routeKey)
            throws Exception {
        var fixture = new Fixture(routeKey);
        fixture.run(fixture.request().header("X-Tenant-ID", "7"));

        assertThat(fixture.ownerCalls.get()).isEqualTo(1);
        verify(fixture.authority).evaluateProductTrusted(any(), any());
        var forwarded = fixture.forwarded.get();
        assertThat(forwarded.getURI().getPath()).isEqualTo(fixture.policy.path());
        assertThat(forwarded.getURI().getPath().substring("/api/people".length()))
                .startsWith("/v1/hris/performance/workbench/");
        assertThat(forwarded.getQueryParams().getFirst("locale")).isEqualTo("ko-KR");
        assertThat(forwarded.getQueryParams().getFirst("timeZone")).isEqualTo("Asia/Seoul");
        assertThat(forwarded.getHeaders()).doesNotContainKey("X-Tenant-ID");
        var envelope = mapper.readTree(Base64.getUrlDecoder().decode(forwarded.getHeaders()
                .getFirst(Per004DecisionContextForwarder.AUTHORITY_CONTEXT_HEADER)));
        var binding = envelope.path("binding");
        assertThat(binding.path("tenantId").asLong()).isEqualTo(7);
        assertThat(binding.path("subjectPrincipalPublicId").asText()).isEqualTo(SUBJECT);
        assertThat(binding.path("routeContractKey").asText()).isEqualTo(routeKey);
        assertThat(binding.path("scopeDigest").asText()).matches("[a-f0-9]{64}");
        assertThat(binding.path("decisionRevision").asText()).isEqualTo("decision-1");
        assertThat(binding.path("fieldPolicyKey").asText()).isEqualTo(fixture.policy.fieldPolicyKey());
        assertThat(binding.path("fieldPolicyRevision").asText()).matches("[1-9][0-9]*");
        assertThat(binding.path("purpose").asText()).isEqualTo(fixture.policy.purpose());
        assertThat(binding.path("locale").asText()).isEqualTo("ko-KR");
        assertThat(binding.path("timeZone").asText()).isEqualTo("Asia/Seoul");
        assertThat(binding.path("correlationId").asText()).isEqualTo("per004-correlation");
        assertThat(envelope.path("scopeKind").asText()).isEqualTo(fixture.policy.ownerScopeKind());
        assertThat(envelope.path("decisions").findValuesAsText("group"))
                .containsExactlyElementsOf(fixture.policy.fieldGroups());
        assertThat(envelope.path("decisions").findValuesAsText("decision"))
                .containsExactly("VIEW", "MASK", "OMIT");
        assertThat(envelope.path("actionTargetsVisible").asBoolean()).isFalse();
        assertThat(forwarded.getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.MASKED_FIELD_GROUPS_HEADER))
                .isEqualTo(fixture.policy.fieldGroups().get(1));
        assertThat(forwarded.getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.OMITTED_FIELD_GROUPS_HEADER))
                .isEqualTo(fixture.policy.fieldGroups().get(2));
    }

    @Test
    void browserAuthorityHeadersIncludingUnknownSuffixAndDuplicatesNeverReachAuthOrPeople() {
        for (String header : List.of("X-DWP-Tenant-ID", "x-dwp-user-id", "X-DWP-Context-Scope-Key",
                "X-DWP-Field-Policy-Revision", "X-DWP-Masked-Field-Groups", "X-DWP-Purpose",
                "X-DWP-Authority-Future-Unknown", "X-DWP-Per004-Authority-Context",
                "X-DWP-Service-Token", "X-Purpose-Code")) {
            for (String[] values : List.of(new String[]{"spoof"}, new String[]{"spoof", "spoof"},
                    new String[]{"spoof,conflict"})) {
                var fixture = new Fixture(FORMS);
                var exchange = fixture.run(fixture.request().header(header, values));
                assertThat(exchange.getResponse().getStatusCode()).as(header)
                        .isEqualTo(HttpStatus.BAD_REQUEST);
                fixture.assertNoAuthorityOrOwner();
            }
        }
    }

    @Test
    void equivalentPageAndDataCandidatesStillProduceOneDecisionAndOnePeopleCall() {
        for (String routeKey : List.of(FORMS, SETTINGS)) {
            var fixture = new Fixture(routeKey);
            var data = fixture.catalog.match("GET", fixture.policy.path(), "").uniqueRoute();
            var page = new GeneratedProductRouteCatalog.Route(routeKey.replace(".data", ".page"),
                    data.authorizationEquivalenceKey(), data.productKey(), data.surfaceKey(),
                    data.method(), data.publicPath(), "PAGE", false, false, false, false,
                    data.structuralPattern(), data.exactPattern(), data.queryConstraints());
            when(fixture.catalog.match(any(), any(), any())).thenReturn(
                    new GeneratedProductRouteCatalog.Match(
                            GeneratedProductRouteCatalog.MatchStatus.GOVERNED, List.of(page, data)));
            fixture.run(fixture.request());
            assertThat(fixture.fieldCalls.get()).isEqualTo(1);
            assertThat(fixture.ownerCalls.get()).isEqualTo(1);
            assertThat(fixture.forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER)).isEqualTo(routeKey);
            verify(fixture.authority).evaluateProductTrusted(any(), any());
        }
    }

    @Test
    void duplicateOrConflictingLegacyTenantSelectorsAreRejectedBeforeProxying() {
        for (String[] values : List.of(new String[]{"8"}, new String[]{"7", "7"},
                new String[]{"7", "8"}, new String[]{"7,8"})) {
            var fixture = new Fixture(FORMS);
            var exchange = fixture.run(fixture.request().header("X-Tenant-ID", values));
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            fixture.assertNoAuthorityOrOwner();
        }
    }

    @Test
    void missingServerIdentityAttributeCannotBeReplacedByInternalLookingHeaders() {
        var fixture = new Fixture(FORMS);
        var exchange = MockServerWebExchange.from(fixture.request()
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER, SUBJECT));
        fixture.filter().filter(exchange, ignored -> {
            fixture.ownerCalls.incrementAndGet();
            return Mono.empty();
        }).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        fixture.assertNoAuthorityOrOwner();
    }

    @Test
    void missingV55RouteCannotFallThroughAnUngovernedGenericPeopleRoute() {
        var fixture = new Fixture(FORMS);
        when(fixture.catalog.match(any(), any(), any())).thenReturn(
                new GeneratedProductRouteCatalog.Match(
                        GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED, List.of()));
        var exchange = fixture.run(fixture.request());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        fixture.assertNoAuthorityOrOwner();
    }

    @Test
    void defaultOffAndUnauthorizedAuthorityNeverInvokesTheFieldProviderOrPeople() {
        for (var decision : List.of(ProductSurfaceContextDtos.Decision.ACTIVATION_REQUIRED,
                ProductSurfaceContextDtos.Decision.APP_DENIED,
                ProductSurfaceContextDtos.Decision.ROUTE_DENIED,
                ProductSurfaceContextDtos.Decision.SCOPE_INVALID,
                ProductSurfaceContextDtos.Decision.AUTHORITY_UNAVAILABLE)) {
            var fixture = new Fixture(FORMS);
            fixture.decision = decision;
            fixture.run(fixture.request());
            assertThat(fixture.fieldCalls.get()).isZero();
            assertThat(fixture.ownerCalls.get()).isZero();
        }
    }

    @Test
    void wrongScopeKindAndStaleRouteDecisionNeverInvokeTheFieldProviderOrPeople() {
        for (String route : List.of(FORMS, SETTINGS)) {
            var fixture = new Fixture(route);
            fixture.scopeKind = "CONFIG_SCOPE";
            fixture.run(fixture.request());
            assertThat(fixture.fieldCalls.get()).isZero();
            assertThat(fixture.ownerCalls.get()).isZero();
            fixture = new Fixture(route);
            fixture.expiry = NOW.minusSeconds(1).atOffset(ZoneOffset.UTC);
            fixture.run(fixture.request());
            assertThat(fixture.fieldCalls.get()).isZero();
            assertThat(fixture.ownerCalls.get()).isZero();
        }
    }

    @Test
    void everyFieldBindingComponentMustMatchExactly() throws Exception {
        for (var component : Per004FieldDecisionPort.Binding.class.getRecordComponents()) {
            var fixture = new Fixture(SETTINGS);
            fixture.provider = binding -> {
                try {
                    ObjectNode changed = mapper.valueToTree(binding);
                    if (component.getType() == long.class) changed.put(component.getName(), 99L);
                    else changed.put(component.getName(), "mismatch");
                    return Mono.just(fixture.result(mapper.treeToValue(changed,
                            Per004FieldDecisionPort.Binding.class)));
                } catch (Exception failure) { return Mono.error(failure); }
            };
            fixture.run(fixture.request());
            assertThat(fixture.fieldCalls.get()).as(component.getName()).isEqualTo(1);
            assertThat(fixture.ownerCalls.get()).as(component.getName()).isZero();
        }
    }

    @Test
    void missingDuplicateExtraOrInvalidFieldDecisionsMakeZeroPeopleCalls() {
        for (int invalid = 0; invalid < 4; invalid++) {
            var fixture = new Fixture(FORMS);
            var decisions = new ArrayList<>(fixture.decisions());
            switch (invalid) {
                case 0 -> decisions.removeLast();
                case 1 -> decisions.add(decisions.getFirst());
                case 2 -> decisions.add(new Per004FieldDecisionPort.FieldDecision("UNKNOWN",
                        Per004FieldDecisionPort.Decision.VIEW));
                case 3 -> decisions.set(0, new Per004FieldDecisionPort.FieldDecision(
                        fixture.policy.fieldGroups().getFirst(), null));
                default -> throw new AssertionError();
            }
            fixture.provider = binding -> Mono.just(new Per004FieldDecisionPort.Result(
                    binding, decisions, NOW, NOW.plusSeconds(30)));
            fixture.run(fixture.request());
            assertThat(fixture.ownerCalls.get()).isZero();
        }
    }

    @Test
    void allThreeDecisionValuesAreAcceptedOnlyAsACompletePolicyAndHideActionTargets() throws Exception {
        for (var decision : Per004FieldDecisionPort.Decision.values()) {
            var fixture = new Fixture(SETTINGS);
            fixture.provider = binding -> Mono.just(new Per004FieldDecisionPort.Result(binding,
                    fixture.policy.fieldGroups().stream().map(group ->
                            new Per004FieldDecisionPort.FieldDecision(group, decision)).toList(),
                    NOW, NOW.plusSeconds(30)));
            fixture.run(fixture.request());
            assertThat(fixture.ownerCalls.get()).isEqualTo(1);
            var context = mapper.readTree(Base64.getUrlDecoder().decode(fixture.forwarded.get()
                    .getHeaders().getFirst(Per004DecisionContextForwarder.AUTHORITY_CONTEXT_HEADER)));
            assertThat(context.path("actionTargetsVisible").asBoolean())
                    .isEqualTo(decision == Per004FieldDecisionPort.Decision.VIEW);
        }
    }

    @Test
    void unboundUnavailableEmptyStaleAndFutureFieldEvidenceMakeZeroPeopleCalls() {
        for (int failure = 0; failure < 7; failure++) {
            var fixture = new Fixture(FORMS);
            final int selected = failure;
            fixture.provider = binding -> switch (selected) {
                case 0 -> Mono.empty();
                case 1 -> Mono.error(new IllegalStateException());
                case 2 -> throw new IllegalStateException();
                case 3 -> Mono.just(new Per004FieldDecisionPort.Result(binding, fixture.decisions(),
                        NOW.minusSeconds(60), NOW.minusSeconds(1)));
                case 4 -> Mono.just(new Per004FieldDecisionPort.Result(binding, fixture.decisions(),
                        NOW.plusSeconds(1), NOW.plusSeconds(30)));
                case 5 -> Mono.just(new Per004FieldDecisionPort.Result(binding, fixture.decisions(),
                        null, NOW.plusSeconds(30)));
                case 6 -> Mono.never();
                default -> throw new AssertionError();
            };
            var exchange = fixture.run(fixture.request());
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(fixture.ownerCalls.get()).isZero();
        }
        var fixture = new Fixture(FORMS);
        fixture.productionDefault = true;
        fixture.expiry = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1);
        var exchange = fixture.run(fixture.request());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(fixture.fieldCalls.get()).isZero();
        assertThat(fixture.ownerCalls.get()).isZero();
    }

    @Test
    void duplicateLocaleOrTimezoneAndMissingCorrelationCannotProduceTrustedContext() {
        for (String query : List.of("locale=ko-KR&locale=en-US&timeZone=Asia/Seoul",
                "locale=ko-KR&timeZone=UTC&timeZone=Asia/Seoul", "locale=ko-KR",
                "locale=und&timeZone=Asia/Seoul", "locale=ko-KR&timeZone=invalid")) {
            var fixture = new Fixture(FORMS);
            fixture.run(MockServerHttpRequest.get(fixture.policy.path() + "?" + query)
                    .header("X-Correlation-ID", "per004-correlation"));
            assertThat(fixture.fieldCalls.get()).isZero();
            assertThat(fixture.ownerCalls.get()).isZero();
        }
        var fixture = new Fixture(FORMS);
        fixture.run(MockServerHttpRequest.get(fixture.policy.path()
                + "?locale=ko-KR&timeZone=Asia/Seoul"));
        assertThat(fixture.fieldCalls.get()).isZero();
        assertThat(fixture.ownerCalls.get()).isZero();
    }

    @Test
    void canonicalTimezoneRetainsItsMeaningWhenItContainsAPlusSign() throws Exception {
        var fixture = new Fixture(FORMS);
        fixture.run(MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET,
                java.net.URI.create(fixture.policy.path()
                        + "?locale=en-US&timeZone=Etc/GMT%2B9"))
                .header("X-Correlation-ID", "per004-correlation"));
        assertThat(fixture.ownerCalls.get()).isEqualTo(1);
        assertThat(fixture.forwarded.get().getQueryParams().getFirst("timeZone"))
                .isEqualTo("Etc/GMT+9");
        var context = mapper.readTree(Base64.getUrlDecoder().decode(fixture.forwarded.get()
                .getHeaders().getFirst(Per004DecisionContextForwarder.AUTHORITY_CONTEXT_HEADER)));
        assertThat(context.path("binding").path("timeZone").asText()).isEqualTo("Etc/GMT+9");
    }

    @Test
    void inboundGuardPreservesNonPerRequestsExactlyAndHasTheRequiredOrdering() {
        var filter = new Per004InboundAuthorityFilter();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(
                Per004GatewayRoutes.FORMS_PATH + "/extra").header("X-DWP-Unknown", "kept"));
        var passed = new AtomicReference<>();
        filter.filter(exchange, next -> { passed.set(next); return Mono.empty(); }).block();
        assertThat(passed.get()).isSameAs(exchange);
        assertThat(filter.getOrder()).isGreaterThan(-200).isLessThan(-190);
    }

    private final class Fixture {
        final String routeKey;
        final Per004GatewayRoutes.Policy policy;
        final ProductSurfaceContextAggregationService authority = mock(
                ProductSurfaceContextAggregationService.class);
        final GeneratedProductRouteCatalog catalog = mock(GeneratedProductRouteCatalog.class);
        final AtomicInteger fieldCalls = new AtomicInteger();
        final AtomicInteger ownerCalls = new AtomicInteger();
        final AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
        Function<Per004FieldDecisionPort.Binding, Mono<Per004FieldDecisionPort.Result>> provider;
        ProductSurfaceContextDtos.Decision decision = ProductSurfaceContextDtos.Decision.ALLOWED;
        OffsetDateTime expiry = EXPIRY;
        String scopeKind;
        boolean productionDefault;

        Fixture(String routeKey) {
            this.routeKey = routeKey;
            policy = Per004GatewayRoutes.policy(routeKey);
            scopeKind = policy.authScopeKind();
            var route = new GeneratedProductRouteCatalog.Route(routeKey, "per004-equivalent",
                    "hcm", routeKey.contains("configuration") ? "hcm.management" : "hcm.operations",
                    "GET", policy.path(), routeKey.endsWith("page") ? "PAGE" : "DATA",
                    false, false, false, false, Pattern.compile(Pattern.quote(policy.path())),
                    Pattern.compile(Pattern.quote(policy.path())), Map.of());
            when(catalog.match(any(), any(), any())).thenReturn(new GeneratedProductRouteCatalog.Match(
                    GeneratedProductRouteCatalog.MatchStatus.GOVERNED, List.of(route)));
            when(authority.evaluateProductTrusted(any(), any())).thenAnswer(ignored -> {
                var scope = new ProductSurfaceContextDtos.EffectiveScope("trusted-scope", scopeKind,
                        "trusted population", true, false, expiry);
                var data = new ProductSurfaceContextDtos.ProductEvaluationData(decision, decision.name(),
                        "decision-1", null, "grant", scope, false, expiry, null, null, null, expiry);
                return Mono.just(new ProductSurfaceContextAggregationService.TrustedProductEvaluation(
                        data, "trusted-context", scope, new ProductSurfaceContextDtos.SourceRevisions(
                                "auth-1", "policy-1", "relationship-1", "population-1", "tenant-1", null),
                        false));
            });
            provider = binding -> Mono.just(result(binding));
        }

        List<Per004FieldDecisionPort.FieldDecision> decisions() {
            return List.of(new Per004FieldDecisionPort.FieldDecision(policy.fieldGroups().get(0),
                            Per004FieldDecisionPort.Decision.VIEW),
                    new Per004FieldDecisionPort.FieldDecision(policy.fieldGroups().get(1),
                            Per004FieldDecisionPort.Decision.MASK),
                    new Per004FieldDecisionPort.FieldDecision(policy.fieldGroups().get(2),
                            Per004FieldDecisionPort.Decision.OMIT));
        }

        Per004FieldDecisionPort.Result result(Per004FieldDecisionPort.Binding binding) {
            var reversed = new ArrayList<>(decisions());
            java.util.Collections.reverse(reversed);
            return new Per004FieldDecisionPort.Result(binding, reversed, NOW, NOW.plusSeconds(30));
        }

        ProductSurfaceDecisionContextFilter filter() {
            var forwarder = productionDefault ? new Per004DecisionContextForwarder(mapper)
                    : new Per004DecisionContextForwarder(mapper, binding -> {
                        fieldCalls.incrementAndGet();
                        return provider.apply(binding);
                    }, Clock.fixed(NOW, ZoneOffset.UTC));
            return new ProductSurfaceDecisionContextFilter(authority, catalog, mapper,
                    GatewayDenialAuditSink.NOOP, forwarder);
        }

        MockServerHttpRequest.BaseBuilder<?> request() {
            return MockServerHttpRequest.get(policy.path() + "?locale=ko_kr&timeZone=Asia/Seoul")
                    .header("X-Correlation-ID", "per004-correlation");
        }

        MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> builder) {
            var exchange = MockServerWebExchange.from(builder);
            var identity = new VerifiedIdentity("41", "7", List.of("HR_ADMIN"), List.of(), List.of(),
                    List.of(), SUBJECT, null, false, "session", "TENANT");
            var identityFilter = new VerifiedIdentityFilter(ignored -> Mono.just(identity));
            new Per004InboundAuthorityFilter().filter(exchange, clean ->
                    identityFilter.filter(clean, verified -> {
                        var rollout = verified.getRequest().mutate().headers(headers ->
                                headers.set(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")).build();
                        return filter().filter(verified.mutate().request(rollout).build(), owner -> {
                            ownerCalls.incrementAndGet();
                            forwarded.set(owner.getRequest());
                            return Mono.empty();
                        });
                    })).block();
            return exchange;
        }

        void assertNoAuthorityOrOwner() {
            verify(authority, never()).evaluateProductTrusted(any(), any());
            assertThat(fieldCalls.get()).isZero();
            assertThat(ownerCalls.get()).isZero();
        }
    }
}
