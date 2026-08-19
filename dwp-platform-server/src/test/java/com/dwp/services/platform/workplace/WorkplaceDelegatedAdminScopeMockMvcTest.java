package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminScopeRepository.DelegatedGrant;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegateType;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedScopeType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class WorkplaceDelegatedAdminScopeMockMvcTest {

    @Test
    void delegatedSiteListIsFilteredBeforeSerialization() throws Exception {
        UUID allowedSiteId = UUID.randomUUID();
        UUID hiddenSiteId = UUID.randomUUID();
        WorkplaceService service = mock(WorkplaceService.class);
        WorkplaceDelegatedAdminScopeRepository repository =
                mock(WorkplaceDelegatedAdminScopeRepository.class);
        WorkplaceDelegatedAdminScopeGuard guard = new WorkplaceDelegatedAdminScopeGuard(
                repository,
                Clock.fixed(Instant.parse("2026-08-19T01:00:00Z"), ZoneOffset.UTC));
        when(repository.candidateGrants(1L, 7L, Set.of())).thenReturn(List.of(
                new DelegatedGrant(
                        UUID.randomUUID(), DelegateType.USER, 7L, null,
                        DelegatedScopeType.SITE, allowedSiteId, null,
                        Set.of(DelegatedPermission.CATALOG_VIEW), null, null)));
        when(service.sites(1L, null)).thenReturn(List.of(
                site(allowedSiteId, "VISIBLE"), site(hiddenSiteId, "HIDDEN")));
        AdminWorkplaceController controller = new AdminWorkplaceController(service, guard);
        MockMvc mvc = standaloneSetup(controller)
                .addInterceptors(new WorkplaceDelegatedAdminScopeInterceptor(guard))
                .build();

        mvc.perform(get("/v1/admin/workplace/sites")
                        .header("X-DWP-Tenant-ID", "1")
                        .header("X-DWP-User-ID", "7")
                        .header("X-DWP-Roles", "WORKPLACE_DELEGATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].siteId").value(allowedSiteId.toString()))
                .andExpect(jsonPath("$.data[0].code").value("VISIBLE"));
    }

    @Test
    void delegatedCampusListReceivesOnlyTheAuthorizedSiteSet() throws Exception {
        UUID allowedSiteId = UUID.randomUUID();
        WorkplaceSpatialGovernanceService service =
                mock(WorkplaceSpatialGovernanceService.class);
        WorkplaceDelegatedAdminScopeRepository repository =
                mock(WorkplaceDelegatedAdminScopeRepository.class);
        WorkplaceDelegatedAdminScopeGuard guard = new WorkplaceDelegatedAdminScopeGuard(
                repository,
                Clock.fixed(Instant.parse("2026-08-19T01:00:00Z"), ZoneOffset.UTC));
        when(repository.candidateGrants(1L, 7L, Set.of())).thenReturn(List.of(
                new DelegatedGrant(
                        UUID.randomUUID(), DelegateType.USER, 7L, null,
                        DelegatedScopeType.SITE, allowedSiteId, null,
                        Set.of(DelegatedPermission.POLICY_MANAGE), null, null)));
        when(service.campuses(1L, Set.of(allowedSiteId))).thenReturn(List.of());
        WorkplaceSpatialGovernanceAdminController controller =
                new WorkplaceSpatialGovernanceAdminController(service, guard);
        MockMvc mvc = standaloneSetup(controller)
                .addInterceptors(new WorkplaceDelegatedAdminScopeInterceptor(guard))
                .build();

        mvc.perform(get("/v1/admin/workplace/governance/campuses")
                        .header("X-DWP-Tenant-ID", "1")
                        .header("X-DWP-User-ID", "7")
                        .header("X-DWP-Roles", "WORKPLACE_DELEGATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        verify(service).campuses(1L, Set.of(allowedSiteId));
    }

    private WorkplaceDtos.Site site(UUID siteId, String code) {
        return new WorkplaceDtos.Site(
                siteId, UUID.randomUUID(), code, code, code, code,
                WorkplaceTypes.SiteType.HEADQUARTERS, "", "Asia/Seoul",
                1, 1, 1, WorkplaceTypes.SiteState.ACTIVE, 0);
    }
}
