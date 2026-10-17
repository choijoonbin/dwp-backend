package com.dwp.services.people.hr;

import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.people.security.HcmEligibilityScopeKeys;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.workforce.WorkforceAccessPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HcmPopulationScopeServiceTest {

    @AfterEach
    void clearContext() {
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "clear");
        PeopleRequestContext.clear();
    }

    @Test
    void verifiesMembershipThroughTheProductionPopulationRepositoryBoundary() {
        HcmPopulationRepository repository = mock(HcmPopulationRepository.class);
        HcmPopulationScopeService service = new HcmPopulationScopeService(
                repository, mock(WorkforceAccessPolicyService.class));
        PeopleRequestContext.set(1001L, 41L, UUID.randomUUID(), Set.of(), Set.of());
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        10L, null, true, Set.of(), Set.of("DIRECTORY"), "fixture-policy");
        HcmPopulationScopeService.ResolvedPopulation population =
                new HcmPopulationScopeService.ResolvedPopulation(
                        null, scope,
                        new HcmPopulationRepository.PopulationEvidence(2L, "fixture-revision"));
        when(repository.containsWorker(41L, scope, 11L)).thenReturn(true);

        assertThat(service.containsWorker(population, 11L)).isTrue();
        verify(repository).containsWorker(41L, scope, 11L);
    }

    @Test
    void organizationIsAnExplicitFieldDecisionAndUnknownFieldsRemainDenied() {
        HcmPopulationScopeService service = new HcmPopulationScopeService(
                mock(HcmPopulationRepository.class),
                mock(WorkforceAccessPolicyService.class));
        HcmPopulationScopeService.ResolvedPopulation population = population(
                Set.of("DIRECTORY", "ORGANIZATION"));

        service.requireField(population, "ORGANIZATION");

        assertThatThrownBy(() -> service.requireField(population, "UNKNOWN"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void organizationResolverMatchesOnlyItsOwnerDerivedScope() {
        long userId = 1001L;
        long tenantId = 41L;
        PeopleRequestContext.set(
                userId, tenantId, UUID.randomUUID(), Set.of(), Set.of());
        HcmPopulationScopeService.ResolvedPopulation population = population(
                Set.of("DIRECTORY", "ORGANIZATION"));
        String sourceKey = ProductSurfaceScopeKey.key(
                tenantId, userId, "hcm", "hcm.operations",
                "ORGANIZATION_TARGET_POPULATION", "TARGET_POPULATION");
        String scopeKey = HcmEligibilityScopeKeys.derived(
                tenantId, userId, "hcm.operations", sourceKey,
                population.relationshipRevision(),
                population.targetPopulationRevision());
        HcmV3PepRegistry.RouteAuthority authority = new HcmV3PepRegistry.RouteAuthority(
                "route.hcm.operations.organization-graph.data",
                "DATA", "organization-viewer", true,
                Set.of("predicate.hcm-organization-graph-authority.v1"),
                Set.of("TARGET_POPULATION"),
                "route.hcm.operations.organization-graph.data.binding.01",
                "hcm.operations.organization.view", null, "GET",
                "/api/people/v1/hris/organizations/graph", null);
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "set",
                new HcmPepContext.Evidence(
                        authority,
                        "psr-" + "a".repeat(64),
                        OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                        "hcm.operations", scopeKey, "110"));
        HcmPopulationScopeService service = new HcmPopulationScopeService(
                mock(HcmPopulationRepository.class),
                mock(WorkforceAccessPolicyService.class));

        service.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "ORGANIZATION_TARGET_POPULATION");

        assertThatThrownBy(() -> service.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "WORKFORCE_TARGET_POPULATION", "ORG_UNIT/LEGAL_ENTITY"))
                .isInstanceOf(BaseException.class);
    }

    private HcmPopulationScopeService.ResolvedPopulation population(
            Set<String> fields) {
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        10L, null, true, Set.of(), fields, "fixture-policy");
        return new HcmPopulationScopeService.ResolvedPopulation(
                null,
                scope,
                new HcmPopulationRepository.PopulationEvidence(
                        2L, "fixture-revision"));
    }
}
