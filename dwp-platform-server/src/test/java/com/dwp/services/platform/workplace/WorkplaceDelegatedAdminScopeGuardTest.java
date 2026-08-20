package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminScopeRepository.DelegatedGrant;
import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminScopeRepository.SiteTargetType;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegateType;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedScopeType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkplaceDelegatedAdminScopeGuardTest {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");

    private WorkplaceDelegatedAdminScopeRepository repository;
    private WorkplaceDelegatedAdminScopeGuard guard;

    @BeforeEach
    void setUp() {
        repository = mock(WorkplaceDelegatedAdminScopeRepository.class);
        guard = new WorkplaceDelegatedAdminScopeGuard(
                repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void trustedGlobalAdministratorBypassesDelegatedScopeGuard() {
        MockHttpServletRequest request = request(
                "GET", "/v1/admin/workplace/bookings", "TENANT_ADMIN");

        assertThatCode(() -> guard.authorize(request)).doesNotThrowAnyException();
        verifyNoInteractions(repository);
    }

    @Test
    void matchingSiteAndCatalogPermissionAllowTheOperation() {
        UUID siteId = UUID.randomUUID();
        MockHttpServletRequest request = request(
                "PUT", "/v1/admin/workplace/sites/" + siteId, "WORKPLACE_DELEGATE");
        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(grant(siteId,
                        Set.of(DelegatedPermission.CATALOG_MANAGE), null, null)));
        when(repository.resolveSite(1L, SiteTargetType.SITE, siteId))
                .thenReturn(java.util.Optional.of(siteId));

        assertThatCode(() -> guard.authorize(request)).doesNotThrowAnyException();
    }

    @Test
    void governedDraftBackgroundUsesTheFloorPlanDelegationBoundary() {
        UUID siteId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        MockHttpServletRequest request = request(
                "POST",
                "/v1/admin/workplace/governance/floor-plan-revisions/"
                        + revisionId + "/background",
                "WORKPLACE_DELEGATE");
        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(grant(siteId,
                        Set.of(DelegatedPermission.FLOOR_PLAN_MANAGE), null, null)));
        when(repository.resolveSite(1L, SiteTargetType.FLOOR_PLAN_REVISION, revisionId))
                .thenReturn(java.util.Optional.of(siteId));

        assertThatCode(() -> guard.authorize(request)).doesNotThrowAnyException();
    }

    @Test
    void crossSiteTargetIsDeniedWithoutDisclosingExistence() {
        UUID delegatedSiteId = UUID.randomUUID();
        UUID targetSiteId = UUID.randomUUID();
        MockHttpServletRequest request = request(
                "PUT", "/v1/admin/workplace/sites/" + targetSiteId, "WORKPLACE_DELEGATE");
        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(grant(delegatedSiteId,
                        Set.of(DelegatedPermission.CATALOG_MANAGE), null, null)));
        when(repository.resolveSite(1L, SiteTargetType.SITE, targetSiteId))
                .thenReturn(java.util.Optional.of(targetSiteId));

        assertForbidden(() -> guard.authorize(request));
    }

    @Test
    void mismatchedParentAndChildTargetsAreDenied() {
        UUID siteId = UUID.randomUUID();
        UUID anotherSiteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        MockHttpServletRequest request = request(
                "PUT", "/v1/admin/workplace/floors/" + floorId
                        + "/resources/" + resourceId, "WORKPLACE_DELEGATE");
        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(grant(siteId,
                        Set.of(DelegatedPermission.CATALOG_MANAGE), null, null)));
        when(repository.resolveSite(1L, SiteTargetType.FLOOR, floorId))
                .thenReturn(java.util.Optional.of(siteId));
        when(repository.resolveSite(1L, SiteTargetType.RESOURCE, resourceId))
                .thenReturn(java.util.Optional.of(anotherSiteId));

        assertForbidden(() -> guard.authorize(request));
    }

    @Test
    void expiredOrPermissionMismatchedDelegationsAreDenied() {
        UUID siteId = UUID.randomUUID();
        MockHttpServletRequest request = request(
                "PUT", "/v1/admin/workplace/sites/" + siteId, "WORKPLACE_DELEGATE");
        when(repository.resolveSite(1L, SiteTargetType.SITE, siteId))
                .thenReturn(java.util.Optional.of(siteId));
        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(grant(siteId,
                        Set.of(DelegatedPermission.CATALOG_MANAGE), null,
                        OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC))));

        assertForbidden(() -> guard.authorize(request));

        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(grant(siteId,
                        Set.of(DelegatedPermission.CATALOG_VIEW), null, null)));

        assertForbidden(() -> guard.authorize(request));
    }

    @Test
    void tenantWideBookingSearchIsFailClosedForDelegatedAdministrator() {
        MockHttpServletRequest request = request(
                "GET", "/v1/admin/workplace/bookings", "WORKPLACE_DELEGATE");

        assertForbidden(() -> guard.authorize(request));
        verifyNoInteractions(repository);
    }

    @Test
    void delegatedScopeMutationIsGlobalAdministratorOnly() {
        MockHttpServletRequest request = request(
                "POST",
                "/v1/admin/workplace/governance/delegated-admin-scopes",
                "WORKPLACE_DELEGATE");

        assertForbidden(() -> guard.authorize(request));
        verifyNoInteractions(repository);
    }

    @Test
    void siteCampusReassignmentIsGlobalAdministratorOnly() {
        MockHttpServletRequest request = request(
                "PUT",
                "/v1/admin/workplace/governance/sites/" + UUID.randomUUID() + "/campus",
                "WORKPLACE_DELEGATE");

        assertForbidden(() -> guard.authorize(request));
        verifyNoInteractions(repository);
    }

    @Test
    void effectiveSelfScopeAcceptsAnyActiveSitePermission() {
        UUID siteId = UUID.randomUUID();
        MockHttpServletRequest request = request(
                "GET",
                "/v1/admin/workplace/governance/delegated-admin-scopes/effective",
                "WORKPLACE_DELEGATE");
        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(grant(siteId,
                        Set.of(DelegatedPermission.POLICY_MANAGE), null, null)));

        assertThatCode(() -> guard.authorize(request)).doesNotThrowAnyException();
    }

    @Test
    void unsupportedGroupTargetScopeCannotUnlockEffectiveSelfEndpoint() {
        MockHttpServletRequest request = request(
                "GET",
                "/v1/admin/workplace/governance/delegated-admin-scopes/effective",
                "WORKPLACE_DELEGATE");
        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(new DelegatedGrant(
                        UUID.randomUUID(), DelegateType.USER, 7L, null,
                        DelegatedScopeType.GROUP_REF, null, UUID.randomUUID(),
                        Set.of(DelegatedPermission.POLICY_MANAGE), null, null)));

        assertForbidden(() -> guard.authorize(request));
    }

    @Test
    void siteManagePermissionsImplicitlyGrantMinimumCatalogRead() {
        for (DelegatedPermission permission : List.of(
                DelegatedPermission.CATALOG_MANAGE,
                DelegatedPermission.ACCESS_MANAGE,
                DelegatedPermission.POLICY_MANAGE,
                DelegatedPermission.FLOOR_PLAN_MANAGE)) {
            UUID siteId = UUID.randomUUID();
            MockHttpServletRequest request = request(
                    "GET", "/v1/admin/workplace/sites", "WORKPLACE_DELEGATE");
            when(repository.candidateGrants(1L, 7L, Set.of()))
                    .thenReturn(List.of(grant(siteId, Set.of(permission), null, null)));

            assertThatCode(() -> guard.authorize(request)).doesNotThrowAnyException();
            assertThat(guard.visibleSiteIds(request)).containsExactly(siteId);
        }
    }

    @Test
    void delegatedPolicyCrudRequiresAnAuthorizedNarrowScopeQuery() {
        UUID siteId = UUID.randomUUID();
        UUID overrideId = UUID.randomUUID();
        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(grant(siteId,
                        Set.of(DelegatedPermission.POLICY_MANAGE), null, null)));
        when(repository.resolveSite(1L, SiteTargetType.SITE, siteId))
                .thenReturn(java.util.Optional.of(siteId));
        when(repository.resolveSite(1L, SiteTargetType.POLICY_OVERRIDE, overrideId))
                .thenReturn(java.util.Optional.of(siteId));

        for (String method : List.of("GET", "POST")) {
            MockHttpServletRequest request = request(
                    method, "/v1/admin/workplace/governance/policy-overrides",
                    "WORKPLACE_DELEGATE");
            request.addParameter("scopeType", "SITE");
            request.addParameter("scopeId", siteId.toString());
            assertThatCode(() -> guard.authorize(request)).doesNotThrowAnyException();
        }

        MockHttpServletRequest update = request(
                "PUT", "/v1/admin/workplace/governance/policy-overrides/" + overrideId,
                "WORKPLACE_DELEGATE");
        update.addParameter("scopeType", "SITE");
        update.addParameter("scopeId", siteId.toString());
        assertThatCode(() -> guard.authorize(update)).doesNotThrowAnyException();
    }

    @Test
    void delegatedPolicyListWithoutScopeAndTenantScopeAreDenied() {
        UUID siteId = UUID.randomUUID();
        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(grant(siteId,
                        Set.of(DelegatedPermission.POLICY_MANAGE), null, null)));

        MockHttpServletRequest missing = request(
                "GET", "/v1/admin/workplace/governance/policy-overrides",
                "WORKPLACE_DELEGATE");
        assertForbidden(() -> guard.authorize(missing));

        MockHttpServletRequest tenant = request(
                "GET", "/v1/admin/workplace/governance/policy-overrides",
                "WORKPLACE_DELEGATE");
        tenant.addParameter("scopeType", "TENANT");
        assertForbidden(() -> guard.authorize(tenant));
    }

    @Test
    void policyPreviewAuthorizesEverySupportedSiteDescendantScope() {
        UUID siteId = UUID.randomUUID();
        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(grant(siteId,
                        Set.of(DelegatedPermission.POLICY_MANAGE), null, null)));
        Map<WorkplaceSpatialGovernanceDtos.PolicyScopeType, SiteTargetType> targets = Map.of(
                WorkplaceSpatialGovernanceDtos.PolicyScopeType.SITE, SiteTargetType.SITE,
                WorkplaceSpatialGovernanceDtos.PolicyScopeType.FLOOR, SiteTargetType.FLOOR,
                WorkplaceSpatialGovernanceDtos.PolicyScopeType.ZONE, SiteTargetType.ZONE,
                WorkplaceSpatialGovernanceDtos.PolicyScopeType.RESOURCE,
                SiteTargetType.RESOURCE);

        targets.forEach((scopeType, targetType) -> {
            UUID targetId = UUID.randomUUID();
            when(repository.resolveSite(1L, targetType, targetId))
                    .thenReturn(java.util.Optional.of(siteId));
            MockHttpServletRequest request = request(
                    "GET", "/v1/admin/workplace/governance/policy-preview",
                    "WORKPLACE_DELEGATE");
            request.addParameter("scopeType", scopeType.name());
            request.addParameter("scopeId", targetId.toString());

            assertThatCode(() -> guard.authorize(request)).doesNotThrowAnyException();
        });
    }

    @Test
    void policyQueryForAnotherSiteIsDenied() {
        UUID delegatedSiteId = UUID.randomUUID();
        UUID targetSiteId = UUID.randomUUID();
        MockHttpServletRequest request = request(
                "POST", "/v1/admin/workplace/governance/policy-overrides",
                "WORKPLACE_DELEGATE");
        request.addParameter("scopeType", "SITE");
        request.addParameter("scopeId", targetSiteId.toString());
        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(grant(delegatedSiteId,
                        Set.of(DelegatedPermission.POLICY_MANAGE), null, null)));
        when(repository.resolveSite(1L, SiteTargetType.SITE, targetSiteId))
                .thenReturn(java.util.Optional.of(targetSiteId));

        assertForbidden(() -> guard.authorize(request));
    }

    @Test
    void delegatedCampusCatalogUsesTheSameAuthorizedSiteFilter() {
        UUID siteId = UUID.randomUUID();
        MockHttpServletRequest request = request(
                "GET", "/v1/admin/workplace/governance/campuses",
                "WORKPLACE_DELEGATE");
        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(grant(siteId,
                        Set.of(DelegatedPermission.FLOOR_PLAN_MANAGE), null, null)));

        guard.authorize(request);

        assertThat(guard.visibleSiteIds(request)).containsExactly(siteId);
    }

    @Test
    void groupDelegationUsesOnlyVerifiedGroupReferences() {
        UUID siteId = UUID.randomUUID();
        UUID groupRef = UUID.randomUUID();
        MockHttpServletRequest request = request(
                "GET", "/v1/admin/workplace/floors", "WORKPLACE_DELEGATE");
        request.addParameter("siteId", siteId.toString());
        request.addHeader("X-DWP-Group-Refs", groupRef.toString());
        when(repository.candidateGrants(1L, 7L, Set.of(groupRef)))
                .thenReturn(List.of(new DelegatedGrant(
                        UUID.randomUUID(), DelegateType.GROUP_REF, null, groupRef,
                        DelegatedScopeType.SITE, siteId, null,
                        Set.of(DelegatedPermission.CATALOG_VIEW), null, null)));
        when(repository.resolveSite(1L, SiteTargetType.SITE, siteId))
                .thenReturn(java.util.Optional.of(siteId));

        assertThatCode(() -> guard.authorize(request)).doesNotThrowAnyException();
    }

    @Test
    void siteListStoresOnlyAuthorizedSitesForResponseFiltering() {
        UUID siteId = UUID.randomUUID();
        UUID hiddenSiteId = UUID.randomUUID();
        MockHttpServletRequest request = request(
                "GET", "/v1/admin/workplace/sites", "WORKPLACE_DELEGATE");
        when(repository.candidateGrants(1L, 7L, Set.of()))
                .thenReturn(List.of(grant(siteId,
                        Set.of(DelegatedPermission.CATALOG_VIEW), null, null)));

        guard.authorize(request);
        List<WorkplaceDtos.Site> filtered = guard.filterVisibleSites(request, List.of(
                site(siteId, "VISIBLE"), site(hiddenSiteId, "HIDDEN")));

        assertThat(filtered).extracting(WorkplaceDtos.Site::siteId).containsExactly(siteId);
    }

    private MockHttpServletRequest request(String method, String path, String roles) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("X-DWP-Tenant-ID", "1");
        request.addHeader("X-DWP-User-ID", "7");
        request.addHeader("X-DWP-Roles", roles);
        return request;
    }

    private DelegatedGrant grant(
            UUID siteId,
            Set<DelegatedPermission> permissions,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil) {
        return new DelegatedGrant(
                UUID.randomUUID(), DelegateType.USER, 7L, null,
                DelegatedScopeType.SITE, siteId, null,
                permissions, validFrom, validUntil);
    }

    private WorkplaceDtos.Site site(UUID siteId, String code) {
        return new WorkplaceDtos.Site(
                siteId, UUID.randomUUID(), code, code, code, code,
                WorkplaceTypes.SiteType.HEADQUARTERS, "", "Asia/Seoul",
                1, 1, 1, WorkplaceTypes.SiteState.ACTIVE, 0);
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
