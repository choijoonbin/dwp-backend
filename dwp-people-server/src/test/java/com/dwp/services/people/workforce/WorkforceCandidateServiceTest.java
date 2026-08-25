package com.dwp.services.people.workforce;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkforceCandidateServiceTest {

    private final WorkforceCandidateRepository repository =
            mock(WorkforceCandidateRepository.class);
    private final HcmPopulationScopeService populationScopes =
            mock(HcmPopulationScopeService.class);
    private final WorkforceCandidateService service =
            new WorkforceCandidateService(repository, populationScopes);

    @AfterEach
    void clearContext() {
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "clear");
        PeopleRequestContext.clear();
    }

    @Test
    void exactAppConfigAuthorityReturnsOnlyTheTenantCandidateProjection() {
        PeopleRequestContext.set(41L, 7L, Set.of(), Set.of());
        setPep("route.hcm.management.org-design.page");
        WorkforceCandidateDtos.OrganizationCandidate candidate =
                new WorkforceCandidateDtos.OrganizationCandidate(
                        UUID.randomUUID(), "Kim DWP", "AI Platform", "Engineer",
                        WorkforceCandidateDtos.Eligibility.ELIGIBLE);
        when(repository.list(7L)).thenReturn(List.of(candidate));

        assertThat(service.list()).containsExactly(candidate);

        verify(populationScopes).requireConfigurationScope();
        verify(repository).list(7L);
    }

    @Test
    void anotherHcmRouteCannotReuseTheCandidateDataBoundary() {
        PeopleRequestContext.set(41L, 7L, Set.of(), Set.of());
        setPep("route.hcm.management.integration.page");

        assertThatThrownBy(service::list)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(populationScopes, never()).requireConfigurationScope();
        verify(repository, never()).list(7L);
    }

    @Test
    void baselineMemberCannotReadManagementCandidates() {
        PeopleRequestContext.set(41L, 7L, Set.of("WORKSPACE_MEMBER"), Set.of());

        assertThatThrownBy(service::list)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(repository, never()).list(7L);
    }

    private void setPep(String route) {
        HcmV3PepRegistry.RouteAuthority authority = mock(HcmV3PepRegistry.RouteAuthority.class);
        when(authority.routeContractKey()).thenReturn(route);
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "set",
                new HcmPepContext.Evidence(
                        authority, "psr-" + "a".repeat(64),
                        OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                        "hcm.management", "hcm-config-scope", "110"));
    }
}
