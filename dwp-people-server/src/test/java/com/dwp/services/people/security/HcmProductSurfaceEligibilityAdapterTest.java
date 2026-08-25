package com.dwp.services.people.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HcmProductSurfaceEligibilityAdapterTest {

    private static final long TENANT = 7L;
    private static final long ACTOR = 19L;
    private static final UUID PERSON = UUID.randomUUID();
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 25, 2, 0, 0, 0, ZoneOffset.UTC);

    private final HcmPopulationRepository repository = mock(HcmPopulationRepository.class);
    private final HcmPopulationScopeService populations =
            mock(HcmPopulationScopeService.class);
    private final HcmProductSurfaceEligibilityAdapter adapter =
            new HcmProductSurfaceEligibilityAdapter(repository, populations);

    @AfterEach
    void clear() {
        PeopleRequestContext.clear();
    }

    @Test
    void derivesAnActorAndTenantBoundTeamScopeFromLivePopulationEvidence() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("MANAGER"), Set.of());
        HcmPopulationScopeService.ResolvedPopulation population = teamPopulation();
        when(populations.findTeam()).thenReturn(Optional.of(population));
        String source = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.team",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(
                request("hcm.team", source, "TARGET_POPULATION", null));

        assertThat(result.decision()).isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(result.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.sourceScopeKey()).isEqualTo(source);
            assertThat(scope.key()).startsWith("hcm-scope-");
            assertThat(scope.isDefault()).isTrue();
            assertThat(scope.readOnly()).isFalse();
        });
        assertThat(result.productRelationshipRevision()).startsWith("hcm-rel-");
        assertThat(result.targetPopulationRevision()).startsWith("hcm-pop-");
    }

    @Test
    void rejectsAnOpaqueScopeIssuedForAnotherTenantOrResolver() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("MANAGER"), Set.of());
        when(populations.findTeam()).thenReturn(Optional.of(teamPopulation()));
        String crossTenant = ProductSurfaceScopeKey.key(
                TENANT + 1, ACTOR, "hcm", "hcm.team", "TEAM/ORG_UNIT",
                "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(
                request("hcm.team", crossTenant, "TARGET_POPULATION", null));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.SCOPE_INVALID);
        assertThat(result.scopes()).isEmpty();
    }

    @Test
    void invalidatesAPreviouslySelectedDerivedScopeAfterPopulationChange() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("MANAGER"), Set.of());
        when(populations.findTeam()).thenReturn(Optional.of(teamPopulation()));
        String source = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.team", "TEAM/ORG_UNIT",
                "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(
                request("hcm.team", source, "TARGET_POPULATION", "hcm-scope-stale"));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.SCOPE_INVALID);
        assertThat(result.reasonCode()).isEqualTo("HCM_SCOPE_CONTEXT_EXPIRED");
        assertThat(result.scopes()).isEmpty();
    }

    @Test
    void failsClosedWhenNoLiveTargetPopulationExists() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("HR_ADMIN"), Set.of());
        when(populations.findOperations("READ")).thenReturn(Optional.empty());
        when(repository.tenantRevision(TENANT)).thenReturn(Optional.of("3:9"));
        String source = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations", "TIME_TARGET_POPULATION",
                "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(
                request("hcm.operations", source, "TARGET_POPULATION", null));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.SCOPE_INVALID);
        assertThat(result.scopes()).isEmpty();
    }

    private ProductSurfaceEligibilityDtos.EvaluateRequest request(
            String surface,
            String key,
            String kind,
            String selected) {
        return new ProductSurfaceEligibilityDtos.EvaluateRequest(
                TENANT, ACTOR, "hcm", surface,
                ProductSurfaceEligibilityDtos.AccessMode.NORMAL, NOW,
                List.of(new ProductSurfaceEligibilityDtos.CandidateScope(key, kind)), selected);
    }

    private HcmPopulationScopeService.ResolvedPopulation teamPopulation() {
        HcmPopulationRepository.ActorWorkforce actor =
                new HcmPopulationRepository.ActorWorkforce(
                        41L, PERSON, "Manager", "A-MANAGER", "Lead", "Operations",
                        2L, 4L, 7L);
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        41L, "A-MANAGER", false, Set.of(),
                        Set.of("DIRECTORY", "EMPLOYMENT"), "DIRECT_REPORT");
        return new HcmPopulationScopeService.ResolvedPopulation(
                actor, scope, new HcmPopulationRepository.PopulationEvidence(
                        3L, "population-revision"));
    }
}
