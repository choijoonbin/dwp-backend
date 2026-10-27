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

class ProductSurfaceDecisionHcmOwnerContextTest extends ProductSurfaceDecisionContextFilterTestSupport {

    @Test
    void generatedHcmServicesRouteForwardsThePeopleMaterializedScope() {
        ProductSurfaceContextAggregationService authority = authority(allowedHcm());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/platform/v1/services/catalog?surface=hcm")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();
        ArgumentCaptor<ProductSurfaceContextDtos.ProductEvaluationRequest> evaluation =
                ArgumentCaptor.forClass(ProductSurfaceContextDtos.ProductEvaluationRequest.class);

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        verify(authority).evaluateProductTrusted(any(), evaluation.capture());
        assertThat(evaluation.getValue().subject().productKey()).isEqualTo("hcm");
        assertThat(evaluation.getValue().subject().surfaceKey()).isEqualTo("hcm.personal");
        assertThat(evaluation.getValue().routeContractKey())
                .isEqualTo("route.hcm.personal.services.page");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                .isEqualTo("route.hcm.personal.services.page");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.SCOPE_HEADER))
                .isEqualTo(HCM_DERIVED_SCOPE);
        assertThat(forwarded.get().getURI().getRawQuery()).isEqualTo("surface=hcm");
    }

    @Test
    void wave2OwnerEvidenceIsStrippedAndReprojectedFromTrustedAuthority() {
        ProductSurfaceContextAggregationService authority = authority(
                allowedWave2("LEGAL_ENTITY"));
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        String subject = "10000000-0000-4000-8000-000000000041";
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/time/v1/hris/workbench/time-interpretation"
                                + "?correlationId=20000000-0000-4000-8000-000000000041")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER, subject)
                .header(ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER,
                        "client-forged-subject")
                .header(ProductSurfaceDecisionContextFilter.FIELD_POLICY_REVISION_HEADER, "999")
                .header(ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER, "888")
                .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER, "SELF")
                .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                .header(ProductSurfaceDecisionContextFilter.MASKED_FIELD_GROUPS_HEADER,
                        "TRACE_SUMMARY"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER))
                .isEqualTo(subject);
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.FIELD_POLICY_REVISION_HEADER))
                .matches("[1-9][0-9]*").isNotEqualTo("999");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.AUTHORIZATION_REVISION_HEADER))
                .matches("[1-9][0-9]*").isNotEqualTo("888");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                .isEqualTo("LEGAL_ENTITY");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                .isEqualTo("TIME_OPERATIONS");
        assertThat(forwarded.get().getHeaders().containsKey(
                ProductSurfaceDecisionContextFilter.MASKED_FIELD_GROUPS_HEADER)).isFalse();
    }

    @Test
    void hrm009SameWireReadsUseDataAuthorityAndExactTrustedOwnerEvidence() {
        record Case(
                String path,
                String route,
                String purpose,
                String fieldPolicy,
                String populationKind) { }
        List<Case> cases = List.of(
                new Case(
                        "/api/people/v1/hris/workbench/self-service/requests",
                        "route.hcm.personal.requests.data",
                        "EMPLOYEE_SELF_SERVICE", "hrm009.iaq-003.fields.v1", "SELF"),
                new Case(
                        "/api/people/v1/hris/workbench/operations/certificates/documents",
                        "route.hcm.operations.certificates-documents.data",
                        "HR_OPERATIONS", "hrm009.iaq-010.fields.v1", "LEGAL_ENTITY"));
        String subject = "10000000-0000-4000-8000-000000000041";

        for (Case requestCase : cases) {
            GeneratedProductRouteCatalog.Match match = catalog.match("GET", requestCase.path());
            assertThat(match.status()).as(requestCase.path())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.routes()).as(requestCase.path()).hasSize(2);
            assertThat(match.uniqueRoute().routeContractKey()).isEqualTo(requestCase.route());

            ProductSurfaceContextAggregationService authority = authority(
                    "LEGAL_ENTITY".equals(requestCase.populationKind())
                            ? allowedWave2("TARGET_POPULATION", "LEGAL_ENTITY")
                            : allowedWave2(requestCase.populationKind()));
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(requestCase.path())
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER, subject)
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER,
                            "client.forged.fields")
                    .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                            "CLIENT_FORGED"));
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter(authority).filter(exchange, filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(requestCase.path()).isNotNull();
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                    .isEqualTo(requestCase.route());
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .isEqualTo(requestCase.purpose());
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER))
                    .isEqualTo(requestCase.fieldPolicy());
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                    .isEqualTo(requestCase.populationKind());
        }
    }

    @Test
    void hrm009OperationsReadRejectsUnsealedTargetPopulationDisplay() {
        String path = "/api/people/v1/hris/workbench/operations/certificates/documents";
        ProductSurfaceContextAggregationService authority = authority(
                allowedWave2("TARGET_POPULATION", "WORKFORCE_TARGET_POPULATION"));
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(path)
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041"));
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter(authority).filter(exchange, ignored -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(exchange)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE");
    }

    @Test
    void hrm009SharedActionIsRouteWideHighAndNeverGetsStaticPurposeOrFieldPolicy() {
        String path = "/api/people/v1/hris/certificates";
        GeneratedProductRouteCatalog.Route route = catalog.match("POST", path).uniqueRoute();
        assertThat(route).isNotNull();
        assertThat(route.routeContractKey())
                .isEqualTo("route.hcm.operations.certificate-issue.action");
        assertThat(route.stateChanging()).isTrue();
        assertThat(route.highRiskStepUp()).isTrue();
        assertThat(route.commandBoundStepUp()).isTrue();

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(path)
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER, REVISION)
                .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                .header(ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER,
                        "client.forged.fields")
                .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                        "TEAM_MANAGEMENT"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter(authority(allowedWave2("SELF"))).filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().containsKey(
                ProductSurfaceDecisionContextFilter.PURPOSE_HEADER)).isFalse();
        assertThat(forwarded.get().getHeaders().containsKey(
                ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER)).isFalse();
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER)).isEqualTo("SELF");
    }

    @Test
    void hrm011SameWireReadForwardsOnlyTrustedPurposePolicyAndConcretePopulation() {
        String path = "/api/people/v1/hris/workbench/operations/contracts/compensation/basis";
        GeneratedProductRouteCatalog.Match match = catalog.match("GET", path);
        assertThat(match.status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
        assertThat(match.routes()).hasSize(2);
        assertThat(match.uniqueRoute().routeContractKey())
                .isEqualTo("route.hcm.operations.contracts-compensation-basis.data");

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(path)
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041")
                .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                .header(ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER,
                        "client.forged.fields")
                .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                        "CLIENT_FORGED"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter(authority(allowedWave2("TARGET_POPULATION", "LEGAL_ENTITY")))
                .filter(exchange, filtered -> {
                    forwarded.set(filtered.getRequest());
                    return Mono.empty();
                }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.PURPOSE_HEADER)).isEqualTo("HR_OPERATIONS");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER))
                .isEqualTo("HRM-AUTH-007");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                .isEqualTo("LEGAL_ENTITY");
    }

    @Test
    void hrm011ActionRequiresHighCommandBindingAndForwardsTrustedOwnerEvidence() {
        String path = "/api/people/v1/hris/employment-contracts";
        GeneratedProductRouteCatalog.Route route = catalog.match("POST", path).uniqueRoute();
        assertThat(route).isNotNull();
        assertThat(route.routeContractKey())
                .isEqualTo("route.hcm.operations.employment-contract-issue.action");
        assertThat(route.stateChanging()).isTrue();
        assertThat(route.highRiskStepUp()).isTrue();
        assertThat(route.commandBoundStepUp()).isTrue();

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(path)
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER, REVISION)
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER, "4")
                .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "HR_LIFECYCLE")
                .header(ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER,
                        "client.forged.fields")
                .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                        "CLIENT_FORGED"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter(authority(allowedWave2("TARGET_POPULATION", "ORG_UNIT")))
                .filter(exchange, filtered -> {
                    forwarded.set(filtered.getRequest());
                    return Mono.empty();
                }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.PURPOSE_HEADER)).isEqualTo("HR_LIFECYCLE");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER))
                .isEqualTo("HRM-AUTH-015");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER)).isEqualTo("ORG_UNIT");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER)).isEqualTo("4");
    }

    @Test
    void hrm011ActionRejectsWrongOrAmbiguousCallerPurposeBeforeAuthority() {
        ProductSurfaceContextAggregationService authority = authority(
                allowedWave2("TARGET_POPULATION", "ORG_UNIT"));
        for (List<String> values : List.of(
                List.of("CLIENT_FORGED"),
                List.of("HR_LIFECYCLE", "HR_OPERATIONS"))) {
            MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post(
                            "/api/people/v1/hris/employment-contracts")
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                            REVISION);
            values.forEach(value -> request.header(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, value));
            MockServerWebExchange exchange = exchange(request);
            AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

            filter(authority).filter(exchange, ignored -> {
                forwarded.set(true);
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).isFalse();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body(exchange)).contains("INVALID_PURPOSE");
        }
        verify(authority, never()).evaluateProductTrusted(any(), any());
    }

    @Test
    void hrm011RejectsUnsealedTargetPopulationDisplay() {
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get(
                        "/api/people/v1/hris/workbench/operations/contracts/compensation/basis")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041"));
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter(authority(allowedWave2("TARGET_POPULATION", "WORKFORCE_TARGET_POPULATION")))
                .filter(exchange, ignored -> {
                    forwarded.set(true);
                    return Mono.empty();
                }).block();

        assertThat(forwarded.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body(exchange)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE");
    }

    @Test
    void per008EquivalentReadsForwardOnlyTrustedFixedPurposeProjectionAndPopulation() {
        record ReadCase(
                String path,
                String routeKey,
                String purpose,
                String fieldPolicy,
                String scopeKind,
                boolean sendCanonicalPurpose) { }
        for (ReadCase read : List.of(
                new ReadCase(
                        "/api/people/v1/hris/performance/workbench/team-performance",
                        "route.hcm.team.performance.data", "TEAM_MANAGEMENT",
                        "per008.iaq-038.fields.v2", "TARGET_POPULATION", true),
                new ReadCase(
                        "/api/people/v1/hris/performance/feedback-assignments/mine",
                        "route.hcm.personal.feedback-assignments-mine.data",
                        "PERFORMANCE_FEEDBACK",
                        "per008.feedback-assigned-list.fields.v1", "SELF", false))) {
            GeneratedProductRouteCatalog.Match match = catalog.match("GET", read.path());
            assertThat(match.status()).as(read.path())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.routes()).as(read.path()).hasSize(2);
            assertThat(match.uniqueRoute().routeContractKey()).isEqualTo(read.routeKey());
            MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(read.path())
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000041")
                    .header(ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER,
                            "client.forged.fields")
                    .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                            "CLIENT_FORGED");
            if (read.sendCanonicalPurpose()) {
                request.header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER,
                        read.purpose());
            }
            MockServerWebExchange exchange = exchange(request);
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter(authority(allowedWave2(read.scopeKind()))).filter(exchange, filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(read.path()).isNotNull();
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                    .isEqualTo(read.routeKey());
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .isEqualTo(read.purpose());
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.FIELD_POLICY_KEY_HEADER))
                    .isEqualTo(read.fieldPolicy());
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                    .isEqualTo(read.scopeKind());
        }
    }

    @Test
    void per008RejectsConflictingOrDuplicatePurposesBeforeAuthority() {
        record PurposeCase(String path, String canonical) { }
        for (PurposeCase route : List.of(
                new PurposeCase(
                        "/api/people/v1/hris/performance/workbench/team-performance",
                        "TEAM_MANAGEMENT"),
                new PurposeCase(
                        "/api/people/v1/hris/performance/feedback-assignments/mine",
                        "PERFORMANCE_FEEDBACK"))) {
            for (List<String> purposes : List.of(
                    List.of("CLIENT_FORGED"),
                    List.of(route.canonical(), route.canonical()))) {
                ProductSurfaceContextAggregationService authority = authority(
                        allowedWave2("TARGET_POPULATION"));
                MockServerHttpRequest.BaseBuilder<?> request =
                        MockServerHttpRequest.get(route.path())
                                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "000")
                                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                                .header(VerifiedIdentityFilter.TENANT_HEADER, "7");
                purposes.forEach(value -> request.header(
                        ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, value));
                MockServerWebExchange denied = exchange(request);

                filter(authority).filter(denied, ignored -> Mono.empty()).block();

                assertThat(denied.getResponse().getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(body(denied)).contains("INVALID_PURPOSE");
                verify(authority, never()).evaluateProductTrusted(any(), any());
            }
        }
    }

    @Test
    void wave2HrmAndPayPurposesAreGatewayOwned() {
        ProductSurfaceDecisionContextFilter filter = filter(authority(
                allowedWave2("LEGAL_ENTITY")));
        record Case(String method, String path, String purpose, boolean command) {}
        List<Case> cases = List.of(
                new Case("POST", "/api/people/v1/hris/assignment-events",
                        "HR_LIFECYCLE", true),
                new Case("GET", "/api/payroll/v1/workbench/settings-payroll",
                        "PAYROLL_CONFIGURATION", false));

        for (Case requestCase : cases) {
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(
                            HttpMethod.valueOf(requestCase.method()), requestCase.path())
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000041")
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED");
            if (requestCase.command()) {
                builder.header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                        REVISION);
            }
            MockServerWebExchange exchange = exchange(builder);
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange, filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).isNotNull();
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .isEqualTo(requestCase.purpose());
        }
    }

    @Test
    void hrm007PeopleEmploymentTranslatesOnlyTrustedResourceSetToConfigScope() {
        String path = "/api/people/v1/hris/workbench/configuration/people-employment";
        GeneratedProductRouteCatalog.Match match = catalog.match("GET", path);
        assertThat(match.uniqueRoute()).isNotNull();
        assertThat(match.uniqueRoute().routeContractKey())
                .isEqualTo("route.hcm.configuration.people-employment.data");

        ProductSurfaceDecisionContextFilter filter = filter(authority(
                allowedWave2("RESOURCE_SET")));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();
        filter.filter(exchange(MockServerHttpRequest.get(path)
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                        "10000000-0000-4000-8000-000000000041")
                .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                        "CLIENT_FORGED")), filtered -> {
                    forwarded.set(filtered.getRequest());
                    return Mono.empty();
                }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                .isEqualTo("CONFIG_SCOPE");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                .isEqualTo("HRIS_CONFIGURATION");

        for (String invalidTrustedKind : List.of("CONFIG_SCOPE", "TARGET_POPULATION")) {
            ProductSurfaceDecisionContextFilter invalidFilter = filter(authority(
                    allowedWave2(invalidTrustedKind)));
            MockServerWebExchange denied = exchange(MockServerHttpRequest.get(path)
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000041"));
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest>
                    invalidForward = new AtomicReference<>();

            invalidFilter.filter(denied, filtered -> {
                invalidForward.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(invalidForward.get()).as(invalidTrustedKind).isNull();
            assertThat(denied.getResponse().getStatusCode())
                    .as(invalidTrustedKind).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(body(denied)).contains("AUTHORITY_RESOLUTION_UNAVAILABLE");
        }
    }

    @Test
    void wave5SysPerTimOwnerContextIsExactAndReplacesSpoofedHeaders() {
        String id = "10000000-0000-4000-8000-000000000251";
        record Case(
                String method,
                String path,
                String route,
                String purpose,
                String population,
                boolean command) {}
        List<Case> cases = List.of(
                new Case("GET", "/api/platform/v1/admin/audit-control/events?product=HCM",
                        "route.hcm.audit.evidence.page", "HRIS_AUDIT_EVIDENCE", null, false),
                new Case("GET", "/api/platform/v1/admin/audit-control/events/" + id
                        + "?product=HCM", "route.hcm.audit.evidence-detail.data",
                        "HRIS_AUDIT_EVIDENCE", null, false),
                new Case("GET", "/api/people/v1/hris/performance/reviews/" + id,
                        "route.hcm.performance.assigned-review.data",
                        "PERFORMANCE_REVIEW", "TARGET_POPULATION", false),
                new Case("PATCH", "/api/people/v1/hris/performance/reviews/" + id,
                        "route.hcm.performance.assigned-review-draft-update.action",
                        "PERFORMANCE_REVIEW", "SELF", true),
                new Case("POST", "/api/people/v1/hris/performance/reviews/" + id + "/submit",
                        "route.hcm.performance.assigned-review-submit.action",
                        "PERFORMANCE_REVIEW", "SELF", true),
                new Case("GET", "/api/people/v1/hris/performance/workbench/multirater-surveys",
                        "route.hcm.operations.performance-multirater-surveys.page",
                        "PERFORMANCE_OPERATIONS", "TARGET_POPULATION", false),
                new Case("GET", "/api/time/v1/timecards/" + id,
                        "route.hcm.time.timecard.data", "TIME_TIMECARD", "SELF", false),
                new Case("POST", "/api/time/v1/timecards/" + id + "/corrections",
                        "route.hcm.time.timecard-correction.action",
                        "TIME_TIMECARD", "TARGET_POPULATION", true),
                new Case("POST", "/api/time/v1/timecards/" + id + "/submit",
                        "route.hcm.time.timecard-submit.action", "TIME_TIMECARD", "SELF", true),
                new Case("POST", "/api/time/v1/timecards/" + id + "/decisions",
                        "route.hcm.time.timecard-decision.action",
                        "TIME_TIMECARD", "TARGET_POPULATION", true),
                new Case("GET", "/api/time/v1/command-receipts/" + id,
                        "route.hcm.time.command-receipt.data",
                        "COMMAND_STATUS", "TARGET_POPULATION", false));

        for (Case requestCase : cases) {
            String scopeKind = requestCase.population() == null
                    ? "RESOURCE_SET" : requestCase.population();
            ProductSurfaceDecisionContextFilter filter = filter(authority(
                    allowedWave2(scopeKind)));
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.method(
                            HttpMethod.valueOf(requestCase.method()), requestCase.path())
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER,
                            "10000000-0000-4000-8000-000000000041")
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER,
                            "CLIENT_FORGED");
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

            assertThat(forwarded.get()).as(requestCase.route()).isNotNull();
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                    .containsExactly(requestCase.route());
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .containsExactly(requestCase.purpose());
            if (requestCase.population() == null) {
                assertThat(forwarded.get().getHeaders().containsKey(
                        ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER)).isFalse();
            } else {
                assertThat(forwarded.get().getHeaders().get(
                        ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                        .containsExactly(requestCase.population());
            }
        }
    }

    @Test
    void payrollFreshCommandsPreserveGeneratedStepUpForwarding() {
        String runId = "10000000-0000-4000-8000-000000000203";
        String subject = "10000000-0000-4000-8000-000000000041";
        List<String> paths = List.of(
                "/api/payroll/v1/runs",
                "/api/payroll/v1/runs/" + runId + "/attempts",
                "/api/payroll/v1/runs/" + runId + "/approvals",
                "/api/payroll/v1/runs/" + runId + "/finalize");

        for (String path : paths) {
            ProductSurfaceDecisionContextFilter filter = filter(authority(
                    stepUpWave2("PAY_GROUP")));
            MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(path)
                    .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                    .header(VerifiedIdentityFilter.USER_HEADER, "41")
                    .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                    .header(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER, subject)
                    .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                            REVISION)
                    .header(ProductSurfaceDecisionContextFilter.PURPOSE_HEADER, "CLIENT_FORGED")
                    .header(ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER, "SELF"));
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange, filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).as(path).isNotNull();
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.PURPOSE_HEADER))
                    .containsExactly("PAYROLL_PROCESSING");
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.POPULATION_KIND_HEADER))
                    .containsExactly("PAY_GROUP");
            assertThat(forwarded.get().getHeaders().get(
                    ProductSurfaceDecisionContextFilter.SUBJECT_PRINCIPAL_HEADER))
                    .containsExactly(subject);
        }
    }

}
