package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.GovernedRouteAuthorityDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernedRouteAuthorityServiceTest {

    private final OffsetDateTime now =
            OffsetDateTime.of(2026, 8, 24, 1, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void returnsTheBoundAssignedWorkDecision() {
        GovernedRouteAuthorityPort port = ignored -> new GovernedRouteAuthorityDtos.AuthorityResult(
                GovernedRouteAuthorityDtos.Decision.ALLOWED,
                null,
                "auth-2",
                "policy-3",
                "governed-context",
                "work.work",
                ProductSurfaceAuthorityDtos.AccessSource.RELATIONSHIP,
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "review-grant",
                false,
                now.plusHours(1),
                null,
                null,
                null,
                now.plusMinutes(5),
                "review-evidence");

        var result = service(Stream.of(port)).evaluate(request());

        assertThat(result.decision()).isEqualTo(GovernedRouteAuthorityDtos.Decision.ALLOWED);
        assertThat(result.navigationContextId()).isEqualTo("work.work");
        assertThat(result.routeGrantRef()).isEqualTo("review-grant");
    }

    @Test
    void failsClosedForAmbiguousAdapters() {
        GovernedRouteAuthorityPort port = ignored ->
                GovernedRouteAuthorityDtos.AuthorityResult.unavailable(request());

        var result = service(Stream.of(port, port)).evaluate(request());

        assertThat(result.decision())
                .isEqualTo(GovernedRouteAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE);
    }

    @Test
    void rejectsADenialWithoutSourceRevisions() {
        GovernedRouteAuthorityPort port = ignored ->
                new GovernedRouteAuthorityDtos.AuthorityResult(
                        GovernedRouteAuthorityDtos.Decision.ROUTE_DENIED,
                        "ROUTE_CAPABILITY_REQUIRED",
                        null,
                        null,
                        null,
                        "work.work",
                        null,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                        null,
                        true,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        var result = service(Stream.of(port)).evaluate(request());

        assertThat(result.decision())
                .isEqualTo(GovernedRouteAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE);
        assertThat(result.reasonCode()).isEqualTo("AUTHORITY_RESOLUTION_UNAVAILABLE");
    }

    private GovernedRouteAuthorityService service(Stream<GovernedRouteAuthorityPort> ports) {
        @SuppressWarnings("unchecked")
        ObjectProvider<GovernedRouteAuthorityPort> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(ports);
        return new GovernedRouteAuthorityService(provider);
    }

    private GovernedRouteAuthorityDtos.EvaluateRequest request() {
        return new GovernedRouteAuthorityDtos.EvaluateRequest(
                1L,
                7L,
                "work.work",
                "route.context.work__work.review-detail.data",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                "opaque-work-item",
                "v11",
                null);
    }
}
