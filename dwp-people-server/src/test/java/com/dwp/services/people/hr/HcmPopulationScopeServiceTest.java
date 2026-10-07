package com.dwp.services.people.hr;

import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.workforce.WorkforceAccessPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HcmPopulationScopeServiceTest {

    @AfterEach
    void clearContext() {
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
}
