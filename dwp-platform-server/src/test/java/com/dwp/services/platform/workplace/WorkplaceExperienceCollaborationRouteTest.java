package com.dwp.services.platform.workplace;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminScopeRepository.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkplaceExperienceCollaborationRouteTest {
    private static final String PREFIX = "/v1/admin/workplace/experience/collaboration";

    @Test void reviewedRuleWritesRequireAccessPermissionAndMatchingParentAndChildSite() {
        var repository = mock(WorkplaceDelegatedAdminScopeRepository.class);
        var guard = new WorkplaceDelegatedAdminScopeGuard(repository);
        UUID site = UUID.randomUUID(), rule = UUID.randomUUID();
        var request = request("POST", PREFIX + "/sites/" + site + "/access-rules/" + rule + "/changes");
        when(repository.candidateGrants(1L, 7L, Set.of())).thenReturn(List.of(grant(site, DelegatedPermission.ACCESS_MANAGE)));
        when(repository.resolveSite(1L, WorkplaceDelegatedAdminTargetType.SITE, site)).thenReturn(Optional.of(site));
        when(repository.resolveSite(1L, WorkplaceDelegatedAdminTargetType.ACCESS_RULE, rule)).thenReturn(Optional.of(site));
        assertThatCode(() -> guard.authorize(request)).doesNotThrowAnyException();
        when(repository.resolveSite(1L, WorkplaceDelegatedAdminTargetType.ACCESS_RULE, rule)).thenReturn(Optional.of(UUID.randomUUID()));
        assertThatThrownBy(() -> guard.authorize(request)).isInstanceOf(BaseException.class);
        when(repository.resolveSite(1L, WorkplaceDelegatedAdminTargetType.ACCESS_RULE, rule)).thenReturn(Optional.of(site));
        when(repository.candidateGrants(1L, 7L, Set.of())).thenReturn(List.of(grant(site, DelegatedPermission.CATALOG_VIEW)));
        assertThatThrownBy(() -> guard.authorize(request)).isInstanceOf(BaseException.class);
    }

    @Test void floorScopeOptionsReuseAccessManageSiteAuthorityWithoutCatalogGrantOrScopeExpansion() {
        var repository = mock(WorkplaceDelegatedAdminScopeRepository.class);
        var guard = new WorkplaceDelegatedAdminScopeGuard(repository);
        UUID site = UUID.randomUUID(), other = UUID.randomUUID();
        when(repository.candidateGrants(1L, 7L, Set.of())).thenReturn(List.of(grant(site, DelegatedPermission.ACCESS_MANAGE)));
        when(repository.resolveSite(1L, WorkplaceDelegatedAdminTargetType.SITE, site)).thenReturn(Optional.of(site));
        when(repository.resolveSite(1L, WorkplaceDelegatedAdminTargetType.SITE, other)).thenReturn(Optional.of(other));
        assertThatCode(() -> guard.authorize(request("GET", "/v1/admin/workplace/governance/sites/" + site + "/access-preview")))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.authorize(request("POST", PREFIX + "/sites/" + site + "/access-rules/review")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.authorize(request("GET", "/v1/admin/workplace/governance/sites/" + other + "/access-preview")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> guard.authorize(request("POST", PREFIX + "/sites/" + other + "/access-rules/changes")))
                .isInstanceOf(BaseException.class);
    }

    @Test void tenantPolicyConnectorAndDelegationChangesRemainGlobalAdministratorOnly() {
        var guard = new WorkplaceDelegatedAdminScopeGuard(mock(WorkplaceDelegatedAdminScopeRepository.class));
        for (String path : List.of("/policy", "/connectors/ACTUAL_PRESENCE", "/delegations/review", "/delegations/changes")) {
            String method = path.startsWith("/delegations") ? "POST" : "PUT";
            assertThatThrownBy(() -> guard.authorize(request(method, PREFIX + path))).isInstanceOf(BaseException.class);
        }
    }

    @Test void catalogPhotoReadIsScopedAndCannotBeUsedToWrite() {
        var repository = mock(WorkplaceDelegatedAdminScopeRepository.class);
        var guard = new WorkplaceDelegatedAdminScopeGuard(repository);
        UUID site = UUID.randomUUID(), resource = UUID.randomUUID();
        when(repository.candidateGrants(1L, 7L, Set.of())).thenReturn(List.of(grant(site, DelegatedPermission.CATALOG_VIEW)));
        when(repository.resolveSite(1L, WorkplaceDelegatedAdminTargetType.RESOURCE, resource)).thenReturn(Optional.of(site));
        assertThatCode(() -> guard.authorize(request("GET", PREFIX + "/resources/" + resource + "/photo"))).doesNotThrowAnyException();
        assertThatCode(() -> guard.authorize(request("GET", PREFIX + "/resources/" + resource + "/photo/metadata"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.authorize(request("POST", PREFIX + "/resources/" + resource + "/photo"))).isInstanceOf(BaseException.class);
        when(repository.resolveSite(1L, WorkplaceDelegatedAdminTargetType.RESOURCE, resource)).thenReturn(Optional.of(UUID.randomUUID()));
        assertThatThrownBy(() -> guard.authorize(request("GET", PREFIX + "/resources/" + resource + "/photo"))).isInstanceOf(BaseException.class);
    }

    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("X-DWP-Tenant-ID", "1"); request.addHeader("X-DWP-User-ID", "7");
        request.addHeader("X-DWP-Roles", "WORKPLACE_DELEGATE");
        return request;
    }

    private DelegatedGrant grant(UUID site, DelegatedPermission permission) {
        return new DelegatedGrant(UUID.randomUUID(), DelegateType.USER, 7L, null,
                DelegatedScopeType.SITE, site, null, Set.of(permission), null, null);
    }
}
