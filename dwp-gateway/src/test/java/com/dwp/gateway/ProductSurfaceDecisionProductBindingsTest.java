package com.dwp.gateway;

import com.dwp.gateway.filter.ProductSurfaceDecisionContextFilter;
import com.dwp.gateway.filter.ProductSurfaceRolloutHeaderFilter;
import com.dwp.gateway.filter.VerifiedIdentityFilter;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextAggregationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductSurfaceDecisionProductBindingsTest extends ProductSurfaceDecisionContextFilterTestSupport {

    @Test
    void hrm013DedicatedApprovalRouteIsHighCommandBoundAndForwardsOnlyTrustedDecisionContext() {
        String path = "/api/approvals/v1/hris/separation-waiver-tasks/task-62/approve";
        GeneratedProductRouteCatalog.Route route = catalog.match("POST", path).uniqueRoute();
        assertThat(route).isNotNull();
        assertThat(route.routeContractKey())
                .isEqualTo("route.approvals.work.hris-separation-waiver-task-approve.action");
        assertThat(route.productKey()).isEqualTo("approvals");
        assertThat(route.surfaceKey()).isEqualTo("approvals.work");
        assertThat(route.routeKind()).isEqualTo("ACTION");
        assertThat(route.stateChanging()).isTrue();
        assertThat(route.highRiskStepUp()).isTrue();
        assertThat(route.commandBoundStepUp()).isTrue();
        assertThat(route.genericSessionStepUp()).isFalse();

        ProductSurfaceContextAggregationService authority = authority(allowed());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post(path)
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER, REVISION)
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER, "9")
                .header(ProductSurfaceDecisionContextFilter.ROUTE_HEADER, "client-forged"));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return Mono.empty();
        }).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                .isEqualTo("route.approvals.work.hris-separation-waiver-task-approve.action");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER))
                .isEqualTo(REVISION);
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.SCOPE_HEADER)).isEqualTo("scope-approval");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.EXPECTED_OBJECT_VERSION_HEADER)).isEqualTo("9");
    }

    @Test
    void latestCatalogRegistersExactDwaionV5ReadBindingsAsSideEffectFree() {
        Map<String, String> expected = Map.of(
                "/api/agent/v1/runs", "route.dwaion.work.runs.data",
                "/api/agent/v1/runs/40000000-0000-4000-8000-000000000017",
                "route.dwaion.work.run-detail.data",
                "/api/agent/v1/activity/events", "route.dwaion.work.activity-events.data",
                "/api/agent/v1/activity/events/40000000-0000-4000-8000-000000000017",
                "route.dwaion.work.activity-event.data",
                "/api/agent/v1/activity/executions/summary",
                "route.dwaion.work.activity-summary.data");

        expected.forEach((path, routeKey) -> {
            GeneratedProductRouteCatalog.Match match = catalog.match("GET", path);
            assertThat(match.status()).isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.uniqueRoute()).isNotNull().satisfies(route -> {
                assertThat(route.routeContractKey()).isEqualTo(routeKey);
                assertThat(route.productKey()).isEqualTo("dwaion");
                assertThat(route.surfaceKey()).isEqualTo("dwaion.work");
                assertThat(route.routeKind()).isEqualTo("DATA");
                assertThat(route.stateChanging()).isFalse();
            });
        });
    }

    @Test
    void latestCatalogEnforcesExactDwaionV6StateChangeBindingsAndRevision() {
        record Case(String method, String path, String routeKey) {}
        List<Case> cases = List.of(
                new Case("POST", "/api/agent/v1/ask/stream",
                        "route.dwaion.work.ask-stream.action"),
                new Case("PATCH",
                        "/api/agent/v1/conversations/40000000-0000-4000-8000-000000000017",
                        "route.dwaion.work.conversation-rename.action"),
                new Case("DELETE",
                        "/api/agent/v1/conversations/40000000-0000-4000-8000-000000000017",
                        "route.dwaion.work.conversation-delete.action"));

        for (Case requestCase : cases) {
            GeneratedProductRouteCatalog.Match match =
                    catalog.match(requestCase.method(), requestCase.path());
            assertThat(match.status())
                    .isEqualTo(GeneratedProductRouteCatalog.MatchStatus.GOVERNED);
            assertThat(match.uniqueRoute()).isNotNull().satisfies(route -> {
                assertThat(route.routeContractKey()).isEqualTo(requestCase.routeKey());
                assertThat(route.productKey()).isEqualTo("dwaion");
                assertThat(route.surfaceKey()).isEqualTo("dwaion.work");
                assertThat(route.routeKind()).isEqualTo("ACTION");
                assertThat(route.stateChanging()).isTrue();
            });

            ProductSurfaceContextAggregationService authority = authority(allowedDwaion());
            ProductSurfaceDecisionContextFilter filter = filter(authority);
            MockServerHttpRequest.BaseBuilder<?> builder =
                    MockServerHttpRequest.method(
                                    HttpMethod.valueOf(requestCase.method()), requestCase.path())
                            .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                            .header(VerifiedIdentityFilter.USER_HEADER, "41")
                            .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                            .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                                    REVISION);
            MockServerWebExchange exchange = exchange(builder);
            AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                    new AtomicReference<>();

            filter.filter(exchange, filtered -> {
                forwarded.set(filtered.getRequest());
                return Mono.empty();
            }).block();

            assertThat(forwarded.get()).isNotNull();
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                    .isEqualTo(requestCase.routeKey());
            assertThat(forwarded.get().getHeaders().getFirst(
                    ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER))
                    .isEqualTo(REVISION);

            MockServerWebExchange stale = exchange(
                    MockServerHttpRequest.method(
                                    HttpMethod.valueOf(requestCase.method()), requestCase.path())
                            .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                            .header(VerifiedIdentityFilter.USER_HEADER, "41")
                            .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                            .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER,
                                    "psr-" + "b".repeat(64)));
            AtomicReference<Boolean> staleForwarded = new AtomicReference<>(false);

            filter.filter(stale, ignored -> {
                staleForwarded.set(true);
                return Mono.empty();
            }).block();

            assertThat(staleForwarded.get()).isFalse();
            assertThat(stale.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(body(stale)).contains("DECISION_REVISION_CONFLICT");
        }
    }

    @Test
    void enforcedHomePreferenceMutationInjectsExactTrustedEvidence() {
        ProductSurfaceContextAggregationService authority = authority(allowed());
        ProductSurfaceDecisionContextFilter filter = filter(authority);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.put(
                        "/api/platform/v1/home-preferences/surfaces/approval-home")
                .header(ProductSurfaceRolloutHeaderFilter.STATE_HEADER, "110")
                .header(VerifiedIdentityFilter.USER_HEADER, "41")
                .header(VerifiedIdentityFilter.TENANT_HEADER, "7")
                .header(ProductSurfaceDecisionContextFilter.EXPECTED_REVISION_HEADER, REVISION));
        AtomicReference<org.springframework.http.server.reactive.ServerHttpRequest> forwarded =
                new AtomicReference<>();

        filter.filter(exchange, filtered -> {
            forwarded.set(filtered.getRequest());
            return filtered.getResponse().setComplete();
        }).block();

        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.ROUTE_HEADER))
                .isEqualTo("route.approvals.work.home-preference-update.action");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.CURRENT_REVISION_HEADER))
                .isEqualTo(REVISION);
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.CURRENT_REVALIDATE_AT_HEADER))
                .isEqualTo(REVALIDATE_AT.toString());
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.CONTEXT_HEADER)).isEqualTo("ctx-approval");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.SCOPE_HEADER)).isEqualTo("scope-approval");
        assertThat(forwarded.get().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.ACTIVE_ACCESS_MODE_HEADER))
                .isEqualTo("NORMAL");
        assertThat(exchange.getResponse().getHeaders().getFirst(
                ProductSurfaceDecisionContextFilter.RESPONSE_REVISION_HEADER))
                .isEqualTo(REVISION);
    }

}
