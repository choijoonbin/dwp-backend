package com.dwp.gateway;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import com.dwp.gateway.filter.PlatformServiceIdentityFilter;
import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import com.dwp.gateway.filter.ProductSurfaceRolloutHeaderFilter;
import com.dwp.gateway.filter.Sys006InboundAuthorityFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextAggregationService;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import com.dwp.gateway.productsurface.Sys006GatewayRoutes;
import com.dwp.gateway.security.VerifiedIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Sys006GatewayAuthorityTest {
    private static final String SUBJECT = "10000000-0000-4000-8000-000000000041";
    private static final String IDEMPOTENCY_KEY = "sys006-idempotency";
    private static final String MAX_SAFE_OBJECT_REVISION = "9007199254740991";
    private static final OffsetDateTime EXPIRY = OffsetDateTime.of(
            2026, 10, 20, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final List<String> EXPECTED_BINDINGS = List.of(
            "SYS-API-017.GET|PEP-SYS-017|GET|/api/platform/v1/admin/hris/automations|"
                    + "route.hcm.configuration.automation-catalog.data|DATA|false",
            "SYS-API-017.POST|PEP-SYS-018|POST|/api/platform/v1/admin/hris/automations|"
                    + "route.hcm.configuration.automation-definition-create.action|ACTION|true",
            "SYS-API-018|PEP-SYS-019|POST|"
                    + "/api/platform/v1/admin/hris/automations/{automationId}/runs|"
                    + "route.hcm.configuration.automation-run-queue.action|ACTION|true",
            "SYS-API-019|PEP-SYS-020|GET|"
                    + "/api/platform/v1/admin/hris/automation-runs/{runId}|"
                    + "route.hcm.configuration.automation-run-detail.data|DATA|false",
            "SYS-API-020|PEP-SYS-021|POST|"
                    + "/api/platform/v1/admin/hris/automation-runs/{runId}/retry|"
                    + "route.hcm.configuration.automation-run-retry.action|ACTION|true",
            "SYS-API-046|PEP-SYS-048|POST|"
                    + "/api/platform/v1/admin/hris/automations/{automationId}/versions|"
                    + "route.hcm.configuration.automation-version-create.action|ACTION|true",
            "SYS-API-047|PEP-SYS-049|POST|"
                    + "/api/platform/v1/admin/hris/automations/{automationId}/versions/"
                    + "{version}/validate|"
                    + "route.hcm.configuration.automation-version-validate.action|ACTION|true",
            "SYS-API-048|PEP-SYS-050|POST|"
                    + "/api/platform/v1/admin/hris/automations/{automationId}/versions/"
                    + "{version}/submit|"
                    + "route.hcm.configuration.automation-version-submit.action|ACTION|true",
            "SYS-API-049|PEP-SYS-051|POST|"
                    + "/api/platform/v1/admin/hris/automations/{automationId}/versions/"
                    + "{version}/approve|"
                    + "route.hcm.configuration.automation-version-approve.action|ACTION|true",
            "SYS-API-050|PEP-SYS-052|POST|"
                    + "/api/platform/v1/admin/hris/automations/{automationId}/versions/"
                    + "{version}/activate|"
                    + "route.hcm.configuration.automation-version-activate.action|ACTION|true",
            "SYS-API-051|PEP-SYS-053|POST|"
                    + "/api/platform/v1/admin/hris/automations/{automationId}/schedules|"
                    + "route.hcm.configuration.automation-schedule-create.action|ACTION|true",
            "SYS-API-052|PEP-SYS-054|POST|"
                    + "/api/platform/v1/admin/hris/automation-schedules/{scheduleId}/submit|"
                    + "route.hcm.configuration.automation-schedule-submit.action|ACTION|true",
            "SYS-API-053|PEP-SYS-055|POST|"
                    + "/api/platform/v1/admin/hris/automation-schedules/{scheduleId}/approve|"
                    + "route.hcm.configuration.automation-schedule-approve.action|ACTION|true",
            "SYS-API-054|PEP-SYS-056|POST|"
                    + "/api/platform/v1/admin/hris/automation-schedules/{scheduleId}/activate|"
                    + "route.hcm.configuration.automation-schedule-activate.action|ACTION|true",
            "SYS-API-055|PEP-SYS-057|POST|"
                    + "/api/platform/v1/admin/hris/automation-schedules/{scheduleId}/suspend|"
                    + "route.hcm.configuration.automation-schedule-suspend.action|ACTION|true",
            "SYS-API-056|PEP-SYS-058|POST|"
                    + "/api/platform/v1/admin/hris/automation-runs/{runId}/cancel|"
                    + "route.hcm.configuration.automation-run-cancel.action|ACTION|true");

    @Test
    @SuppressWarnings("unchecked")
    void exactSixteenBindingsMatchTheSealedApiPepHttpAndRouteContract() throws Exception {
        List<String> actual = Sys006GatewayRoutes.BINDINGS.stream()
                .map(binding -> String.join("|", binding.api(), binding.pep(), binding.method(),
                        binding.browserPath(), binding.routeContractKey(), binding.routeKind(),
                        Boolean.toString(binding.stateChanging())))
                .toList();

        assertThat(actual).containsExactlyElementsOf(EXPECTED_BINDINGS);
        assertThat(Sys006GatewayRoutes.BINDINGS).hasSize(16);
        assertThat(Sys006GatewayRoutes.BINDINGS.stream().map(
                Sys006GatewayRoutes.Binding::api)).doesNotHaveDuplicates();
        assertThat(Sys006GatewayRoutes.BINDINGS.stream().map(
                Sys006GatewayRoutes.Binding::pep)).doesNotHaveDuplicates();
        assertThat(Sys006GatewayRoutes.BINDINGS.stream().map(
                Sys006GatewayRoutes.Binding::routeContractKey)).doesNotHaveDuplicates();
        assertThat(Sys006GatewayRoutes.BINDINGS).filteredOn(
                binding -> "DATA".equals(binding.routeKind())).hasSize(2);
        assertThat(Sys006GatewayRoutes.BINDINGS).filteredOn(
                binding -> "ACTION".equals(binding.routeKind())).hasSize(14);
        var governed = ProductSurfaceDecisionContextFilter.class.getDeclaredField(
                "TENANT_FEATURE_GOVERNED_ROUTE_KEYS");
        governed.setAccessible(true);
        assertThat((Set<String>) governed.get(null))
                .containsAll(Sys006GatewayRoutes.ROUTE_KEYS);
    }

    @Test
    void commandDescriptorParityIsExactlyFourteenActionsAndThirteenResourceActions() {
        var actions = Sys006GatewayRoutes.BINDINGS.stream()
                .filter(Sys006GatewayRoutes.Binding::stateChanging)
                .toList();
        var resourceActions = actions.stream()
                .filter(binding -> Sys006GatewayRoutes.requiresExpectedObjectRevision(
                        binding.routeContractKey()))
                .toList();

        assertThat(actions).hasSize(14);
        assertThat(resourceActions).hasSize(13);
        assertThat(actions).filteredOn(binding -> !Sys006GatewayRoutes
                        .requiresExpectedObjectRevision(binding.routeContractKey()))
                .extracting(Sys006GatewayRoutes.Binding::routeContractKey)
                .containsExactly(Sys006GatewayRoutes.CREATE_DEFINITION_ROUTE_KEY);
    }

    @Test
    void everyBindingInvokesAuthOnceAndForwardsOnlyReconstructedTrustedContext() {
        for (var binding : Sys006GatewayRoutes.BINDINGS) {
            var fixture = new Fixture(binding);
            var exchange = fixture.run(fixture.request());

            assertThat(exchange.getResponse().getStatusCode()).as(binding.api()).isNull();
            verify(fixture.authority, times(1)).evaluateProductTrusted(any(), any());
            assertThat(fixture.ownerCalls.get()).as(binding.api()).isEqualTo(1);
            var forwarded = fixture.forwarded.get();
            assertThat(forwarded.getURI().getPath()).isEqualTo(fixture.actualPath);
            assertThat(forwarded.getHeaders()).doesNotContainKey("X-Tenant-ID");
            assertThat(forwarded.getHeaders().getFirst(VerifiedIdentityFilter.TENANT_HEADER))
                    .isEqualTo("7");
            assertThat(forwarded.getHeaders().getFirst(VerifiedIdentityFilter.USER_HEADER))
                    .isEqualTo("41");
            assertThat(forwarded.getHeaders().getFirst(
                    VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER)).isEqualTo(SUBJECT);
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
                    .isEqualTo(Sys006GatewayRoutes.PURPOSE);
            assertThat(forwarded.getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.FIELD_POLICY_REVISION_HEADER))
                    .matches("[1-9][0-9]*");
            assertThat(forwarded.getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER))
                    .matches("[1-9][0-9]*");
            assertThat(forwarded.getHeaders().getFirst("X-Correlation-ID"))
                    .isEqualTo("sys006-correlation");
            assertThat(forwarded.getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER))
                    .isEqualTo("decision-1");
            assertThat(forwarded.getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.CURRENT_REVALIDATE_AT_HEADER))
                    .isEqualTo(EXPIRY.toString());
            if (binding.stateChanging()) {
                assertThat(forwarded.getHeaders().getOrEmpty("Idempotency-Key"))
                        .containsExactly(IDEMPOTENCY_KEY);
            } else {
                assertThat(forwarded.getHeaders()).doesNotContainKey("Idempotency-Key");
            }
            if (Sys006GatewayRoutes.requiresExpectedObjectRevision(
                    binding.routeContractKey())) {
                assertThat(forwarded.getHeaders().getOrEmpty("If-Match"))
                        .containsExactly('"' + MAX_SAFE_OBJECT_REVISION + '"');
                assertThat(forwarded.getHeaders().getOrEmpty(
                        ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER))
                        .containsExactly(MAX_SAFE_OBJECT_REVISION);
            } else {
                assertThat(forwarded.getHeaders()).doesNotContainKey("If-Match");
                assertThat(forwarded.getHeaders()).doesNotContainKey(
                        ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER);
            }
        }
    }

    @Test
    void generatedV56CatalogResolvesOnlyTheExactSixteenSealedMappings() {
        var catalog = new GeneratedProductRouteCatalog(
                new ObjectMapper(),
                new FileSystemResource(
                        "../contracts/product-authorization/product-surfaces-v1.bundle-v56.json"));

        for (var binding : Sys006GatewayRoutes.BINDINGS) {
            String path = actualPath(binding.browserPath());
            var match = catalog.match(binding.method(), path);
            assertThat(match.status()).as(binding.api())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(Sys006GatewayRoutes.matches(match.uniqueRoute(), binding.method(), path))
                    .as(binding.api()).isTrue();
            String wrongMethod = "GET".equals(binding.method()) ? "POST" : "GET";
            assertThat(Sys006GatewayRoutes.matches(match.uniqueRoute(), wrongMethod, path))
                    .as(binding.api() + " wrong method").isFalse();
        }

        var sibling = catalog.match("GET",
                "/api/platform/v1/admin/hris/automations/"
                        + "10000000-0000-4000-8000-000000000001/unregistered-operation");
        assertThat(sibling.uniqueRoute()).isNull();
    }

    @Test
    void browserAuthClaimsFieldDecisionsAndOwnerHeadersAreRejectedBeforeAuth() {
        Set<String> forbidden = Set.of(
                VerifiedIdentityFilter.TENANT_HEADER,
                VerifiedIdentityFilter.USER_HEADER,
                VerifiedIdentityFilter.ROLES_HEADER,
                VerifiedIdentityFilter.PERMISSIONS_HEADER,
                ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER,
                ProductSurfaceDecisionContextFilter.CONTEXT_HEADER,
                ProductSurfaceDecisionContextFilter.SCOPE_HEADER,
                ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                ProductSurfaceDecisionContextFilter.FIELD_POLICY_REVISION_HEADER,
                ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER,
                ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER,
                ProductSurfaceDecisionContextFilter.MASKED_FIELD_GROUPS_HEADER,
                ProductSurfaceDecisionContextFilter.OMITTED_FIELD_GROUPS_HEADER,
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER,
                ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER,
                ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER,
                PlatformServiceIdentityFilter.SERVICE_TOKEN_HEADER,
                "X-DWP-Authority-Future-Unknown");

        for (String header : forbidden) {
            var fixture = new Fixture(Sys006GatewayRoutes.BINDINGS.getFirst());
            var exchange = fixture.run(fixture.request().header(header, "spoof"));
            assertThat(exchange.getResponse().getStatusCode()).as(header)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            fixture.assertNoAuthorityOrOwner();
        }
    }

    @Test
    void tenantMismatchDuplicateClaimsAndPurposeSubstitutionFailClosed() {
        var data = new Fixture(Sys006GatewayRoutes.BINDINGS.getFirst());
        var mismatch = data.run(data.request("8"));
        assertThat(mismatch.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        data.assertNoAuthorityOrOwner();

        data = new Fixture(Sys006GatewayRoutes.BINDINGS.getFirst());
        var duplicateTenant = data.run(data.request().header("X-Tenant-ID", "7"));
        assertThat(duplicateTenant.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        data.assertNoAuthorityOrOwner();

        data = new Fixture(Sys006GatewayRoutes.BINDINGS.getFirst());
        var substitutedRead = data.run(data.request().header("X-Purpose-Code", "OTHER"));
        assertThat(substitutedRead.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        data.assertNoAuthorityOrOwner();

        var action = new Fixture(Sys006GatewayRoutes.BINDINGS.get(1));
        var missingPurpose = action.run(action.baseRequest());
        assertThat(missingPurpose.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        action.assertNoAuthorityOrOwner();

        action = new Fixture(Sys006GatewayRoutes.BINDINGS.get(1));
        var substitutedAction = action.run(action.baseRequest().header("X-Purpose-Code", "OTHER"));
        assertThat(substitutedAction.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        action.assertNoAuthorityOrOwner();
    }

    @Test
    void everyActionRequiresExactlyOneSafeIdempotencyKeyBeforeAuth() {
        var actions = Sys006GatewayRoutes.BINDINGS.stream()
                .filter(Sys006GatewayRoutes.Binding::stateChanging)
                .toList();
        for (var binding : actions) {
            String ifMatch = Sys006GatewayRoutes.requiresExpectedObjectRevision(
                    binding.routeContractKey())
                    ? '"' + MAX_SAFE_OBJECT_REVISION + '"' : null;

            var missingFixture = new Fixture(binding);
            var missing = missingFixture.run(missingFixture.requestWithCommandHeaders(
                    null, ifMatch));
            assertThat(missing.getResponse().getStatusCode()).as(binding.api() + " missing")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            missingFixture.assertNoAuthorityOrOwner();

            var duplicateFixture = new Fixture(binding);
            var duplicate = duplicateFixture.run(duplicateFixture.request()
                    .header("Idempotency-Key", "duplicate"));
            assertThat(duplicate.getResponse().getStatusCode()).as(binding.api() + " duplicate")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            duplicateFixture.assertNoAuthorityOrOwner();

            for (String malformedKey : List.of(
                    "unsafe key", "-leading", "a".repeat(121), "key,other")) {
                var malformedFixture = new Fixture(binding);
                var malformed = malformedFixture.run(
                        malformedFixture.requestWithCommandHeaders(malformedKey, ifMatch));
                assertThat(malformed.getResponse().getStatusCode())
                        .as(binding.api() + " malformed " + malformedKey)
                        .isEqualTo(HttpStatus.BAD_REQUEST);
                malformedFixture.assertNoAuthorityOrOwner();
            }
        }
    }

    @Test
    void everyResourceActionRequiresOneBoundedStrongIfMatchBeforeAuth() {
        var resourceActions = Sys006GatewayRoutes.BINDINGS.stream()
                .filter(binding -> Sys006GatewayRoutes.requiresExpectedObjectRevision(
                        binding.routeContractKey()))
                .toList();
        for (var binding : resourceActions) {
            var missingFixture = new Fixture(binding);
            var missing = missingFixture.run(missingFixture.requestWithCommandHeaders(
                    IDEMPOTENCY_KEY, null));
            assertThat(missing.getResponse().getStatusCode()).as(binding.api() + " missing")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            missingFixture.assertNoAuthorityOrOwner();

            var duplicateFixture = new Fixture(binding);
            var duplicate = duplicateFixture.run(duplicateFixture.request()
                    .header("If-Match", "\"1\""));
            assertThat(duplicate.getResponse().getStatusCode()).as(binding.api() + " duplicate")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            duplicateFixture.assertNoAuthorityOrOwner();

            for (String malformedIfMatch : List.of(
                    "1", "W/\"1\"", "\"0\"", "\"01\"",
                    "\"9007199254740992\"", "\"not-a-revision\"")) {
                var malformedFixture = new Fixture(binding);
                var malformed = malformedFixture.run(
                        malformedFixture.requestWithCommandHeaders(
                                IDEMPOTENCY_KEY, malformedIfMatch));
                assertThat(malformed.getResponse().getStatusCode())
                        .as(binding.api() + " malformed " + malformedIfMatch)
                        .isEqualTo(HttpStatus.BAD_REQUEST);
                malformedFixture.assertNoAuthorityOrOwner();
            }
        }
    }

    @Test
    void collectionCreateAndDataRejectExtraneousCommandHeadersBeforeAuth() {
        var createBinding = Sys006GatewayRoutes.binding(
                Sys006GatewayRoutes.CREATE_DEFINITION_ROUTE_KEY);
        var createFixture = new Fixture(createBinding);
        var createWithIfMatch = createFixture.run(createFixture.requestWithCommandHeaders(
                IDEMPOTENCY_KEY, "\"1\""));
        assertThat(createWithIfMatch.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        createFixture.assertNoAuthorityOrOwner();

        for (var dataBinding : Sys006GatewayRoutes.BINDINGS.stream()
                .filter(binding -> !binding.stateChanging()).toList()) {
            var idempotencyFixture = new Fixture(dataBinding);
            var dataWithIdempotency = idempotencyFixture.run(
                    idempotencyFixture.requestWithCommandHeaders(IDEMPOTENCY_KEY, null));
            assertThat(dataWithIdempotency.getResponse().getStatusCode())
                    .as(dataBinding.api() + " idempotency")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            idempotencyFixture.assertNoAuthorityOrOwner();

            var ifMatchFixture = new Fixture(dataBinding);
            var dataWithIfMatch = ifMatchFixture.run(
                    ifMatchFixture.requestWithCommandHeaders(null, "\"1\""));
            assertThat(dataWithIfMatch.getResponse().getStatusCode())
                    .as(dataBinding.api() + " If-Match")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            ifMatchFixture.assertNoAuthorityOrOwner();
        }
    }

    @Test
    void missingDuplicateOrMalformedCorrelationCannotBecomeTrustedOwnerEvidence() {
        var binding = Sys006GatewayRoutes.BINDINGS.getFirst();
        var missingFixture = new Fixture(binding);
        var missing = missingFixture.run(MockServerHttpRequest.get(missingFixture.actualPath)
                .header("X-Tenant-ID", "7"));
        assertThat(missing.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        missingFixture.assertNoAuthorityOrOwner();

        var duplicateFixture = new Fixture(binding);
        var duplicate = duplicateFixture.run(
                MockServerHttpRequest.get(duplicateFixture.actualPath)
                        .header("X-Tenant-ID", "7")
                        .header("X-Correlation-ID", "one", "two"));
        assertThat(duplicate.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        duplicateFixture.assertNoAuthorityOrOwner();

        var malformedFixture = new Fixture(binding);
        var malformed = malformedFixture.run(
                MockServerHttpRequest.get(malformedFixture.actualPath)
                        .header("X-Tenant-ID", "7")
                        .header("X-Correlation-ID", "not trusted"));
        assertThat(malformed.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        malformedFixture.assertNoAuthorityOrOwner();
    }

    @Test
    void missingIdentityAndUnknownSiblingCannotBypassTheGatewayPep() {
        var directFixture = new Fixture(Sys006GatewayRoutes.BINDINGS.getFirst());
        var direct = MockServerWebExchange.from(directFixture.baseRequest()
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER, SUBJECT));
        directFixture.filter().filter(direct, ignored -> {
            directFixture.ownerCalls.incrementAndGet();
            return Mono.empty();
        }).block();
        assertThat(direct.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        directFixture.assertNoAuthorityOrOwner();

        var fixture = new Fixture(Sys006GatewayRoutes.BINDINGS.getFirst());
        var sibling = fixture.run(MockServerHttpRequest.get(
                        fixture.actualPath + "/unregistered-operation")
                .header("X-Tenant-ID", "7")
                .header("X-Correlation-ID", "sys006-correlation"));
        assertThat(sibling.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        fixture.assertNoAuthorityOrOwner();

        fixture = new Fixture(Sys006GatewayRoutes.BINDINGS.getFirst());
        when(fixture.catalog.match(any(), any(), any())).thenReturn(
                new GeneratedProductRouteCatalog.Match(
                        GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED, List.of()));
        var missingRoute = fixture.run(fixture.request());
        assertThat(missingRoute.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        fixture.assertNoAuthorityOrOwner();
    }

    @Test
    void productRolloutOffStillCallsAuthAndActivationRequiredNeverReachesOwner() {
        var fixture = new Fixture(Sys006GatewayRoutes.BINDINGS.getFirst());
        fixture.decision = ProductSurfaceContextDtos.Decision.ACTIVATION_REQUIRED;

        var exchange = fixture.run(fixture.request());

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(fixture.authority, times(1)).evaluateProductTrusted(any(), any());
        assertThat(fixture.ownerCalls.get()).isZero();
    }

    @Test
    void incompleteTrustedFieldScopeOrFreshnessEvidenceNeverReachesOwner() {
        var fixture = new Fixture(Sys006GatewayRoutes.BINDINGS.getFirst());
        fixture.scopeKind = "";
        var missingScopeKind = fixture.run(fixture.request());
        assertThat(missingScopeKind.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(fixture.ownerCalls.get()).isZero();

        fixture = new Fixture(Sys006GatewayRoutes.BINDINGS.getFirst());
        fixture.revalidateAt = null;
        var missingFreshness = fixture.run(fixture.request());
        assertThat(missingFreshness.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(fixture.ownerCalls.get()).isZero();

        fixture = new Fixture(Sys006GatewayRoutes.BINDINGS.getFirst());
        fixture.policyRevision = null;
        var missingFieldRevision = fixture.run(fixture.request());
        assertThat(missingFieldRevision.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(fixture.ownerCalls.get()).isZero();
    }

    @Test
    void inboundGuardHasTheRequiredPreIdentityOrdering() {
        int order = new Sys006InboundAuthorityFilter().getOrder();
        assertThat(order).isGreaterThan(-200).isLessThan(-100);
    }

    private static String actualPath(String template) {
        return template
                .replace("{automationId}", "10000000-0000-4000-8000-000000000001")
                .replace("{runId}", "10000000-0000-4000-8000-000000000002")
                .replace("{scheduleId}", "10000000-0000-4000-8000-000000000003")
                .replace("{version}", "1");
    }

    private static final class Fixture {
        final Sys006GatewayRoutes.Binding binding;
        final String actualPath;
        final ProductSurfaceContextAggregationService authority = mock(
                ProductSurfaceContextAggregationService.class);
        final GeneratedProductRouteCatalog catalog = mock(GeneratedProductRouteCatalog.class);
        final AtomicInteger ownerCalls = new AtomicInteger();
        final AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
        ProductSurfaceContextDtos.Decision decision = ProductSurfaceContextDtos.Decision.ALLOWED;
        String scopeKind = "CONFIG_SCOPE";
        String policyRevision = "policy-1";
        OffsetDateTime revalidateAt = EXPIRY;

        Fixture(Sys006GatewayRoutes.Binding binding) {
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
                        "trusted-scope", scopeKind, "trusted configuration scope",
                        true, false, EXPIRY);
                var data = new ProductSurfaceContextDtos.ProductEvaluationData(
                        decision, decision.name(), "decision-1", null, "grant", scope,
                        false, EXPIRY, null, null, null, revalidateAt);
                var revisions = new ProductSurfaceContextDtos.SourceRevisions(
                        "auth-1", policyRevision, "relationship-1", "population-1",
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
            return baseRequest("7");
        }

        MockServerHttpRequest.BaseBuilder<?> baseRequest(String tenantId) {
            var builder = minimalRequest(tenantId);
            if (binding.stateChanging()) {
                builder.header("Idempotency-Key", IDEMPOTENCY_KEY);
                if (Sys006GatewayRoutes.requiresExpectedObjectRevision(
                        binding.routeContractKey())) {
                    builder.header("If-Match", '"' + MAX_SAFE_OBJECT_REVISION + '"');
                }
            }
            return builder;
        }

        MockServerHttpRequest.BaseBuilder<?> minimalRequest(String tenantId) {
            var builder = MockServerHttpRequest.method(
                            HttpMethod.valueOf(binding.method()), actualPath)
                    .header("X-Tenant-ID", tenantId)
                    .header("X-Correlation-ID", "sys006-correlation");
            if (binding.stateChanging()) {
                builder.header(
                        ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        "decision-1");
            }
            return builder;
        }

        MockServerHttpRequest.BaseBuilder<?> requestWithCommandHeaders(
                String idempotencyKey,
                String ifMatch) {
            var builder = minimalRequest("7");
            if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
            if (ifMatch != null) builder.header("If-Match", ifMatch);
            if (binding.stateChanging()) {
                builder.header("X-Purpose-Code", Sys006GatewayRoutes.PURPOSE);
            }
            return builder;
        }

        MockServerHttpRequest.BaseBuilder<?> request() {
            return request("7");
        }

        MockServerHttpRequest.BaseBuilder<?> request(String tenantId) {
            var builder = baseRequest(tenantId);
            if (binding.stateChanging()) {
                builder.header("X-Purpose-Code", Sys006GatewayRoutes.PURPOSE);
            }
            return builder;
        }

        MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> request) {
            var exchange = MockServerWebExchange.from(request);
            var identity = new VerifiedIdentity(
                    "41", "7", List.of("HR_ADMIN"), List.of(), List.of(), List.of(),
                    SUBJECT, null, false, "session", "TENANT");
            var identityFilter = new VerifiedIdentityFilter(ignored -> Mono.just(identity));
            new Sys006InboundAuthorityFilter().filter(exchange, clean ->
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
