package com.dwp.services.auth.security;

import com.dwp.services.auth.dto.GovernedRouteAuthorityDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.dwp.services.auth.service.AccessReviewWorkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdentityRoutePredicateEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-08-24T03:00:00Z");

    private final AccessReviewWorkService workService = mock(AccessReviewWorkService.class);
    private final ProductAuthorizationContractRepository contracts =
            mock(ProductAuthorizationContractRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ResultSet resultSet = mock(ResultSet.class);
    private IdentityRoutePredicateEvaluator evaluator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        evaluator = new IdentityRoutePredicateEvaluator(
                workService,
                contracts,
                jdbc,
                Clock.fixed(NOW, ZoneOffset.UTC));
        UUID bundleId = UUID.randomUUID();
        when(contracts.findActivePointer("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", bundleId, 3L, "security-owner", OffsetDateTime.now())));
        when(contracts.findActive("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.StoredBundle(
                        bundleId,
                        "product-surfaces",
                        1L,
                        "ACTIVE",
                        1,
                        "SHA-256",
                        "0".repeat(64),
                        "Identity + Security",
                        "security-owner",
                        OffsetDateTime.now(),
                        OffsetDateTime.now(),
                        OffsetDateTime.now())));
        when(resultSet.getLong("access_revision")).thenReturn(12L);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq(7L)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
    }

    @Test
    void allowsOnlyTheExactAssignedObjectAndCarriesOwnerRevisions() {
        UUID ref = UUID.randomUUID();
        when(workService.predicateEvidence(1L, 7L, ref, 11L, true))
                .thenReturn(new AccessReviewWorkService.PredicateEvidence(
                        AccessReviewWorkService.PredicateState.ALLOWED,
                        NOW.plusSeconds(600),
                        11L));

        GovernedRouteAuthorityDtos.AuthorityResult result = evaluator.evaluate(
                request(IdentityRoutePredicateEvaluator.DECISION_ROUTE, ref, "11",
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL));

        assertThat(result.decision()).isEqualTo(GovernedRouteAuthorityDtos.Decision.ALLOWED);
        assertThat(result.accessSource())
                .isEqualTo(ProductSurfaceAuthorityDtos.AccessSource.RELATIONSHIP);
        assertThat(result.authRevision()).isEqualTo("auth:12");
        assertThat(result.policyRevision()).contains("product-surfaces:v1:r3:");
        assertThat(result.routeGrantRef()).isEqualTo("named-reviewer:" + ref + ":v11");
        assertThat(result.effectiveReadOnly()).isFalse();
        assertThat(result.revalidateAt()).isEqualTo(
                OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC));
    }

    @Test
    void deniesStaleObjectVersionWithoutFallingBackToARole() {
        UUID ref = UUID.randomUUID();
        when(workService.predicateEvidence(1L, 7L, ref, 10L, true))
                .thenReturn(new AccessReviewWorkService.PredicateEvidence(
                        AccessReviewWorkService.PredicateState.STALE_VERSION,
                        NOW.plusSeconds(600),
                        11L));

        GovernedRouteAuthorityDtos.AuthorityResult result = evaluator.evaluate(
                request(IdentityRoutePredicateEvaluator.DECISION_ROUTE, ref, "10",
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL));

        assertThat(result.decision()).isEqualTo(GovernedRouteAuthorityDtos.Decision.ROUTE_DENIED);
        assertThat(result.reasonCode()).isEqualTo("OBJECT_VERSION_STALE");
        assertThat(result.routeGrantRef()).isNull();
    }

    @Test
    void supportModeCannotBorrowANormalNamedReviewerRelationship() {
        UUID ref = UUID.randomUUID();

        GovernedRouteAuthorityDtos.AuthorityResult result = evaluator.evaluate(
                request(IdentityRoutePredicateEvaluator.DETAIL_ROUTE, ref, null,
                        ProductSurfaceAuthorityDtos.AccessMode.PROVIDER_SUPPORT));

        assertThat(result.decision()).isEqualTo(GovernedRouteAuthorityDtos.Decision.ROUTE_DENIED);
        assertThat(result.reasonCode()).isEqualTo("ROUTE_DENIED");
    }

    @Test
    void failsClosedUntilTheCanonicalBundleHasAnActivePointer() {
        when(contracts.findActivePointer("product-surfaces")).thenReturn(Optional.empty());
        UUID ref = UUID.randomUUID();

        GovernedRouteAuthorityDtos.AuthorityResult result = evaluator.evaluate(
                request(IdentityRoutePredicateEvaluator.DETAIL_ROUTE, ref, null,
                        ProductSurfaceAuthorityDtos.AccessMode.NORMAL));

        assertThat(result.decision())
                .isEqualTo(GovernedRouteAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE);
    }

    private GovernedRouteAuthorityDtos.EvaluateRequest request(
            String route,
            UUID ref,
            String version,
            ProductSurfaceAuthorityDtos.AccessMode mode) {
        return new GovernedRouteAuthorityDtos.EvaluateRequest(
                1L,
                7L,
                "work.work",
                route,
                mode,
                ref.toString(),
                version,
                null);
    }
}
