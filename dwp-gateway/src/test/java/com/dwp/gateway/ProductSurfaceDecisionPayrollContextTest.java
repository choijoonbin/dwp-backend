package com.dwp.gateway;

import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import com.dwp.gateway.filter.ProductSurfaceRolloutHeaderFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextAggregationService;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductSurfaceDecisionPayrollContextTest extends ProductSurfaceDecisionContextFilterTestSupport {

    @Test
    void pay003ReadsForwardOnlyExactTrustedPurposeFieldPolicyAndPopulation() {
        record Case(String path, String route, String purpose, String fieldPolicy,
                    String authorityKind, String forwardedKind) { }
        List<Case> cases = List.of(
                new Case(
                        "/api/payroll/v1/workbench/configuration/statutory",
                        "route.hcm.configuration.statutory.data",
                        "PAYROLL_CONFIGURATION", "pay003.iaq-026.fields.v1",
                        "RESOURCE_SET", "CONFIG_SCOPE"),
                new Case(
                        "/api/payroll/v1/workbench/pay-tax-social-insurance",
                        "route.hcm.operations.pay-tax-social-insurance.data",
                        "PAYROLL_OPERATIONS", "pay003.iaq-048.fields.v1",
                        "TARGET_POPULATION", "TARGET_POPULATION"));
        String subject = "10000000-0000-4000-8000-000000000041";

        for (Case requestCase : cases) {
            GeneratedProductRouteCatalog.Match match = catalog.match("GET", requestCase.path());
            assertThat(match.status()).as(requestCase.path())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.routes()).as(requestCase.path()).hasSize(2);
            assertThat(match.uniqueRoute().routeContractKey()).isEqualTo(requestCase.route());

            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(requestCase.path())
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER, subject)
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER,
                            "client.forged.fields")
                    .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                            "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER,
                            "999"));
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter(authority(allowedWave2(requestCase.authorityKind())))
                    .filter(exchange, filtered -> {
                        forwarded.set(filtered.getRequest());
                        return Mono.empty();
                    }).block();

            assertThat(forwarded.get()).as(requestCase.path()).isNotNull();
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .isEqualTo(requestCase.purpose());
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER))
                    .isEqualTo(requestCase.fieldPolicy());
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                    .isEqualTo(requestCase.forwardedKind());
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER)).isFalse();
        }
    }

    @Test
    void pay003GenericResultReadChallengesNormalAndForwardsElevatedAuthority() {
        String path = "/api/payroll/v1/workbench/pay-tax-social-insurance";
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path)
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041");
        MockServerWebExchange challenged = exchange(request);
        AtomicReference<Boolean> challengedForwarded = new AtomicReference<>(false);

        filter(authority(stepUpWave2("TARGET_POPULATION"))).filter(challenged, ignored -> {
            challengedForwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(challengedForwarded.get()).isFalse();
        assertThat(challenged.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(challenged)).contains("STEP_UP_REQUIRED");

        MockServerWebExchange elevated = exchange(request);
        elevated.getAttributes().put(
                VerifiedIdentityFilter.VERIFIED_ACTIVE_ACCESS_MODE_ATTRIBUTE, "ELEVATED");
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();
        filter(authority(allowedWave2("TARGET_POPULATION"))).filter(elevated, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER))
                .isEqualTo("ELEVATED");
    }

    @Test
    void pay004ReadForwardsOnlyTrustedPurposeFieldPolicyAndTargetPopulation() {
        String path = "/api/payroll/v1/workbench/pay-retirement";
        GeneratedProductRouteCatalog.Match match = catalog.match("GET", path);
        assertThat(match.status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(match.routes()).hasSize(2);
        assertThat(match.uniqueRoute().routeContractKey())
                .isEqualTo("route.hcm.operations.pay-retirement.data");
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(path)
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041")
                .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER,
                        "PAYROLL_OPERATIONS")
                .header(ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER,
                        "client.forged.fields")
                .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                        "CLIENT_FORGED")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER,
                        "999"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter(authority(allowedWave2("TARGET_POPULATION")))
                .filter(exchange, filtered -> {
                    forwarded.set(filtered.getRequest());
                    return Mono.empty();
                }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                .isEqualTo("route.hcm.operations.pay-retirement.data");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                .isEqualTo("PAYROLL_OPERATIONS");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER))
                .isEqualTo("pay004.iaq-049.fields.v1");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                .isEqualTo("TARGET_POPULATION");
        assertThat(forwarded.get().getHeaders().containsKey(
                ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER)).isFalse();
    }

    @Test
    void pay004RejectsInvalidPurposeAndNearBindingsBeforeAuthority() {
        String path = "/api/payroll/v1/workbench/pay-retirement";
        for (List<String> purposes : List.of(
                List.of("CLIENT_FORGED"),
                List.of("PAYROLL_OPERATIONS", "PAYROLL_OPERATIONS"))) {
            ProductSurfaceContextAggregationService authority = authority(
                    allowedWave2("TARGET_POPULATION"));
            MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path)
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7");
            purposes.forEach(value -> request.header(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, value));
            MockServerWebExchange denied = exchange(request);

            filter(authority).filter(denied, ignored -> Mono.empty()).block();

            assertThat(denied.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body(denied)).contains("INVALID_PURPOSE");
            verify(authority, never()).evaluateProductTrusted(any(), any());
        }

        List<MockServerHttpRequest.BaseBuilder<?>> nearBindings = List.of(
                MockServerHttpRequest.post(path),
                MockServerHttpRequest.get(path + "/export"));
        for (MockServerHttpRequest.BaseBuilder<?> request : nearBindings) {
            ProductSurfaceContextAggregationService authority = authority(
                    allowedWave2("TARGET_POPULATION"));
            MockServerWebExchange denied = exchange(request
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));

            filter(authority).filter(denied, ignored -> Mono.empty()).block();

            assertThat(denied.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(body(denied)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE");
            verify(authority, never()).evaluateProductTrusted(any(), any());
        }
    }

    @Test
    void pay004GenericReadChallengesNormalAndForwardsElevatedAuthority() {
        String path = "/api/payroll/v1/workbench/pay-retirement";
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path)
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041");
        MockServerWebExchange challenged = exchange(request);
        AtomicReference<Boolean> challengedForwarded = new AtomicReference<>(false);

        filter(authority(stepUpWave2("TARGET_POPULATION"))).filter(challenged, ignored -> {
            challengedForwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(challengedForwarded.get()).isFalse();
        assertThat(challenged.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(challenged)).contains("STEP_UP_REQUIRED");

        MockServerWebExchange elevated = exchange(request);
        elevated.getAttributes().put(
                VerifiedIdentityFilter.VERIFIED_ACTIVE_ACCESS_MODE_ATTRIBUTE, "ELEVATED");
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();
        filter(authority(allowedWave2("TARGET_POPULATION"))).filter(elevated, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER))
                .isEqualTo("ELEVATED");
    }

    @Test
    void pay003CountryPackActionForwardsCanonicalCasWithoutInventingOwnerHeaders() {
        String packId = "10000000-0000-4000-8000-000000000054";
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(
                        "/api/payroll/v1/country-packs/" + packId + "/activate")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER, REVISION)
                .header("If-Match", "\"7\"")
                .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER,
                        "STATUTORY_CONFIGURATION")
                .header(VerifiedIdentityFilter.LEGAL_ENTITY_PUBLIC_ID_HEADER,
                        "client-forged-legal-entity")
                .header("X-DWP-Pay-Group-Public-Id", "client-forged-pay-group"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter(authority(allowedWave2("TARGET_POPULATION"))).filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst("If-Match")).isEqualTo("\"7\"");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER))
                .isEqualTo("7");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                .isEqualTo("STATUTORY_CONFIGURATION");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                .isEqualTo("TARGET_POPULATION");
        assertThat(forwarded.get().getHeaders().containsKey(
                VerifiedIdentityFilter.LEGAL_ENTITY_PUBLIC_ID_HEADER)).isFalse();
        assertThat(forwarded.get().getHeaders().containsKey("X-DWP-Pay-Group-Public-Id"))
                .isFalse();
    }

    @Test
    void pay003CountryPackActionRejectsEveryNonCanonicalIfMatchAsConflictBeforeOwner() {
        String path = "/api/payroll/v1/country-packs/"
                + "10000000-0000-4000-8000-000000000054/activate";
        List<List<String>> cases = List.of(
                List.of(),
                List.of("\"7\"", "\"8\""),
                List.of("W/\"7\""),
                List.of("\"07\""),
                List.of("\"9223372036854775808\""));

        for (List<String> values : cases) {
            ProductSurfaceContextAggregationService authority = authority(
                    allowedWave2("TARGET_POPULATION"));
            MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post(path)
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                            REVISION);
            values.forEach(value -> request.header("If-Match", value));
            MockServerWebExchange exchange = exchange(request);
            AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

            filter(authority).filter(exchange, ignored -> {
                forwarded.set(true);
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(values.toString()).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).as(values.toString())
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(body(exchange)).contains("OBJECT_VERSION_CONFLICT");
            verify(authority, never()).evaluateProductTrusted(any(), any());
        }
    }

    @Test
    void v49ElevatedSessionReevaluatesAndOnlyAllowedReachesTheOwner() {
        String path = "/api/payroll/v1/runs/run-49";
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path)
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041");
        MockServerWebExchange challenged = exchange(request);
        challenged.getAttributes().put(
                VerifiedIdentityFilter.VERIFIED_ACTIVE_ACCESS_MODE_ATTRIBUTE, "ELEVATED");
        AtomicReference<Boolean> challengedForwarded = new AtomicReference<>(false);

        filter(authority(stepUpWave2("TARGET_POPULATION"))).filter(challenged, ignored -> {
            challengedForwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(challengedForwarded.get()).isFalse();
        assertThat(challenged.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(challenged)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE")
                .doesNotContain("STEP_UP_REQUIRED");

        ProductSurfaceContextAggregationService allowedAuthority = authority(
                allowedWave2("TARGET_POPULATION"));
        MockServerWebExchange allowed = exchange(request);
        allowed.getAttributes().put(
                VerifiedIdentityFilter.VERIFIED_ACTIVE_ACCESS_MODE_ATTRIBUTE, "ELEVATED");
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();
        ArgumentCaptor<ProductSurfaceContextDtos.RequestContext> context =
                ArgumentCaptor.forClass(ProductSurfaceContextDtos.RequestContext.class);

        filter(allowedAuthority).filter(allowed, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        verify(allowedAuthority).evaluateProductTrusted(context.capture(), any());
        assertThat(context.getValue().activeAccessMode())
                .isEqualTo(ProductSurfaceContextDtos.AccessMode.ELEVATED);
        assertThat(forwarded.get().getHeaders().get(
                ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER))
                .containsExactly("ELEVATED");
    }

    @Test
    void payrollRunRoutesStripSpoofedEvidenceAndInjectExactTrustedOwnerContext() {
        String runId = "10000000-0000-4000-8000-000000000201";
        String receiptId = "10000000-0000-4000-8000-000000000202";
        String subject = "10000000-0000-4000-8000-000000000041";
        String context = "psc-" + "d".repeat(64);
        record Case(
                String method,
                String path,
                String routeKey,
                String purpose,
                String populationKind,
                boolean command) {}
        List<Case> cases = List.of(
                new Case("POST", "/api/payroll/v1/runs/" + runId + "/attempts",
                        "route.hcm.operations.payroll-run-calculate.action",
                        "PAYROLL_PROCESSING", "PAY_GROUP", true),
                new Case("GET", "/api/payroll/v1/runs/" + runId,
                        "route.hcm.operations.payroll-run.data",
                        "PAYROLL_PROCESSING", "NAMED_POPULATION", false),
                new Case("POST", "/api/payroll/v1/runs/" + runId + "/approvals",
                        "route.hcm.operations.payroll-run-approve.action",
                        "PAYROLL_PROCESSING", "LEGAL_ENTITY", true),
                new Case("POST", "/api/payroll/v1/runs/" + runId + "/finalize",
                        "route.hcm.operations.payroll-run-finalize.action",
                        "PAYROLL_PROCESSING", "PAY_GROUP", true),
                new Case("GET", "/api/payroll/v1/command-receipts/" + receiptId,
                        "route.hcm.operations.payroll-command-receipt.data",
                        "COMMAND_STATUS", "ORIGINATING_COMMAND_SCOPE", false));

        for (Case requestCase : cases) {
            ProductSurfaceDecisionContextFilter filter = filter(authority(
                    allowedWave2(requestCase.populationKind())));
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(
                            HttpMethod.valueOf(requestCase.method()), requestCase.path())
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER, subject)
                    .header(ProductSurfaceDecisionContextFilter.ROUTE_HEADER, "client-route")
                    .header(ProductSurfaceDecisionContextFilter.CONTEXT_HEADER, "client-context")
                    .header(ProductSurfaceDecisionContextFilter.SCOPE_HEADER, "client-scope")
                    .header(ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER,
                            "client-subject")
                    .header(ProductSurfaceDecisionContextFilter.FIELD_POLICY_REVISION_HEADER,
                            "999")
                    .header(ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER,
                            "888")
                    .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER, "SELF")
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.MASKED_FIELD_GROUPS_HEADER,
                            "PAYROLL_AMOUNT");
            if (requestCase.command()) {
                builder.header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        REVISION);
            }
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange(builder), filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(requestCase.path()).isNotNull();
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                    .containsExactly(requestCase.routeKey());
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.CONTEXT_HEADER))
                    .containsExactly(context);
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.SCOPE_HEADER))
                    .containsExactly(HCM_DERIVED_SCOPE);
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER))
                    .containsExactly(subject);
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.FIELD_POLICY_REVISION_HEADER))
                    .containsExactly("4663670355146917040");
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER))
                    .containsExactly("1991387878219104048");
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                    .containsExactly(requestCase.populationKind());
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .containsExactly(requestCase.purpose());
            assertThat(forwarded.get().getHeaders().containsKey(
                    ProductSurfaceDecisionContextFilter.MASKED_FIELD_GROUPS_HEADER)).isFalse();
        }
    }

}
