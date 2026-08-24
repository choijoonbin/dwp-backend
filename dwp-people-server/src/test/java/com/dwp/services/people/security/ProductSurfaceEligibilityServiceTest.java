package com.dwp.services.people.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductSurfaceEligibilityServiceTest {

    private final OffsetDateTime now =
            OffsetDateTime.of(2026, 8, 24, 1, 0, 0, 0, ZoneOffset.UTC);

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void returnsProductOwnedRelationshipEligibility() {
        PeopleRequestContext.set(7L, 1L, Set.of("WORKSPACE_MEMBER"));
        ProductSurfaceEligibilityPort port = ignored ->
                new ProductSurfaceEligibilityDtos.EligibilityResult(
                        ProductSurfaceEligibilityDtos.Decision.ALLOWED,
                        null,
                        "relationship-4",
                        "population-8",
                        List.of(new ProductSurfaceEligibilityDtos.EligibleScope(
                                "team-scope", "team-scope", "TEAM", "My team", true, false,
                                now.plusHours(1))),
                        now.plusMinutes(5),
                        "evidence-team-1");

        var result = service(Stream.of(port)).evaluate(request(1L, 7L));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(result.productRelationshipRevision()).isEqualTo("relationship-4");
    }

    @Test
    void rejectsAnInternalBodyThatDoesNotMatchVerifiedHeaders() {
        PeopleRequestContext.set(7L, 1L, Set.of("WORKSPACE_MEMBER"));
        ProductSurfaceEligibilityPort port = ignored -> {
            throw new AssertionError("mismatched request must not reach the product evaluator");
        };

        var result = service(Stream.of(port)).evaluate(request(1L, 9L));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.AUTHORITY_UNAVAILABLE);
    }

    @Test
    void failsClosedWhenNoProductEvaluatorIsInstalled() {
        PeopleRequestContext.set(7L, 1L, Set.of("WORKSPACE_MEMBER"));

        var result = service(Stream.empty()).evaluate(request(1L, 7L));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.AUTHORITY_UNAVAILABLE);
    }

    private ProductSurfaceEligibilityService service(
            Stream<ProductSurfaceEligibilityPort> ports) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSurfaceEligibilityPort> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(ports);
        return new ProductSurfaceEligibilityService(provider);
    }

    private ProductSurfaceEligibilityDtos.EvaluateRequest request(Long tenantId, Long actorId) {
        return new ProductSurfaceEligibilityDtos.EvaluateRequest(
                tenantId,
                actorId,
                "hcm",
                "hcm.team",
                ProductSurfaceEligibilityDtos.AccessMode.NORMAL,
                now,
                List.of(new ProductSurfaceEligibilityDtos.CandidateScope(
                        "team-scope", "TEAM")),
                null);
    }
}
