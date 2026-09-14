package com.dwp.services.platform.workplace;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminScopeRepository.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkplaceExperienceReportAuthorizationTest {
    @Test
    void administrationDoesNotRequirePersonalProductEntitlement() {
        assertThatCode(() -> WorkplaceExperienceReportController.requireAccess("ADMIN.WORKPLACE:VIEW")).doesNotThrowAnyException();
        assertThatThrownBy(() -> WorkplaceExperienceReportController.requireAccess("APP.WORKPLACE:VIEW")).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> WorkplaceExperienceReportController.requireAccess(null)).isInstanceOf(BaseException.class);
        assertThatCode(() -> WorkplaceExperienceReportController.requireAccess("APP.WORKPLACE:VIEW,ADMIN.WORKPLACE:VIEW")).doesNotThrowAnyException();
    }

    @Test
    void delegatedCatalogReportCannotCrossSiteOrBecomeAPolicyPreview() {
        UUID siteId = UUID.randomUUID();
        UUID otherSiteId = UUID.randomUUID();
        var repository = mock(WorkplaceDelegatedAdminScopeRepository.class);
        var guard = new WorkplaceDelegatedAdminScopeGuard(repository,
                Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC));
        when(repository.candidateGrants(1L, 7L, Set.of())).thenReturn(List.of(new DelegatedGrant(
                UUID.randomUUID(), DelegateType.USER, 7L, null, DelegatedScopeType.SITE, siteId,
                null, Set.of(DelegatedPermission.CATALOG_VIEW), null, null)));
        when(repository.resolveSite(1L, WorkplaceDelegatedAdminTargetType.SITE, siteId)).thenReturn(Optional.of(siteId));
        when(repository.resolveSite(1L, WorkplaceDelegatedAdminTargetType.SITE, otherSiteId)).thenReturn(Optional.of(otherSiteId));
        assertThatCode(() -> guard.authorize(request("/v1/admin/workplace/experience-report", siteId))).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.authorize(request("/v1/admin/workplace/experience-report", otherSiteId))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> guard.authorize(request("/v1/admin/workplace/policy-impact-preview", siteId))).isInstanceOf(BaseException.class);
    }

    @Test
    void invalidPageAndDateBoundsAreRejectedBeforeQuery() {
        assertThatThrownBy(() -> WorkplaceExperienceReportService.validatePage(0, 101)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> WorkplaceExperienceReportService.validatePage(-1, 20)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> WorkplaceExperienceReportService.validateDates(null, null)).isInstanceOf(BaseException.class);
    }

    private MockHttpServletRequest request(String path, UUID siteId) {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-DWP-Tenant-ID", "1");
        request.addHeader("X-DWP-User-ID", "7");
        request.addParameter("siteId", siteId.toString());
        return request;
    }
}
