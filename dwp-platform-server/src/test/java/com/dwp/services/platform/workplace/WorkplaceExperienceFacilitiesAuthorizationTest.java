package com.dwp.services.platform.workplace;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.security.PlatformSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminScopeRepository.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkplaceExperienceFacilitiesAuthorizationTest {
    @Test void nativeRoomMutationRequiresOwnerPermissionAndDoesNotAcceptPersonalWorkplaceAuthority() {
        assertThatCode(() -> WorkplaceExperienceFacilitiesPermissions.requirePermission("ADMIN.ROOMS:UPDATE","ADMIN.ROOMS:UPDATE")).doesNotThrowAnyException();
        assertThatThrownBy(() -> WorkplaceExperienceFacilitiesPermissions.requirePermission("ADMIN.WORKPLACE:MANAGE,APP.ROOMS:UPDATE","ADMIN.ROOMS:UPDATE")).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> WorkplaceExperienceFacilitiesController.requirePermission("APP.WORKPLACE:VIEW","ADMIN.WORKPLACE:VIEW")).isInstanceOf(BaseException.class);
    }
    @Test void everyAdminFacilityRouteHasExplicitSitePolicy() {
        String prefix="/v1/admin/workplace/experience/facilities";
        Map<String,String> routes=Map.of("GET /closures","CATALOG_VIEW","GET /closures/"+UUID.randomUUID(),"CATALOG_VIEW",
                "GET /resources/"+UUID.randomUUID()+"/room-booking-impact","CATALOG_VIEW",
                "POST /resources/"+UUID.randomUUID()+"/closures","CATALOG_MANAGE",
                "PUT /closures/"+UUID.randomUUID()+"/cancel","CATALOG_MANAGE","GET /requests","CATALOG_VIEW",
                "PUT /requests/"+UUID.randomUUID()+"/status","CATALOG_MANAGE");
        routes.forEach((route,permission) -> { String[] parts=route.split(" ",2);
            var match=WorkplaceDelegatedAdminRoutePolicy.match(new MockHttpServletRequest(parts[0],prefix+parts[1])).orElseThrow();
            assertThat(match.scopeMode()).isEqualTo(WorkplaceDelegatedAdminRoutePolicy.ScopeMode.SITE_QUERY);
            assertThat(match.permission().name()).isEqualTo(permission); });
    }
    @Test void delegatedReadCannotWriteOrCrossAnotherSite() {
        UUID site=UUID.randomUUID(),other=UUID.randomUUID(); var repo=mock(WorkplaceDelegatedAdminScopeRepository.class);
        var guard=new WorkplaceDelegatedAdminScopeGuard(repo,Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"),ZoneOffset.UTC));
        when(repo.candidateGrants(1L,7L,Set.of())).thenReturn(List.of(new DelegatedGrant(UUID.randomUUID(),DelegateType.USER,7L,null,
                DelegatedScopeType.SITE,site,null,Set.of(DelegatedPermission.CATALOG_VIEW),null,null)));
        when(repo.resolveSite(1L,WorkplaceDelegatedAdminTargetType.SITE,site)).thenReturn(Optional.of(site));
        when(repo.resolveSite(1L,WorkplaceDelegatedAdminTargetType.SITE,other)).thenReturn(Optional.of(other));
        assertThatCode(() -> guard.authorize(request("GET","/closures",site))).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.authorize(request("GET","/closures",other))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> guard.authorize(request("POST","/resources/"+UUID.randomUUID()+"/closures",site))).isInstanceOf(BaseException.class);
    }
    @Test void globalMethodClassificationIsNarrowAndMatchesNewNativeCreateAndGovernanceManage() {
        var filter=new PlatformSecurityFilter("trusted","runtime",false,new ObjectMapper(),null,null);
        var own=new MockHttpServletRequest("POST","/v1/workplace/experience/facilities/resources/"+UUID.randomUUID()+"/requests");
        own.addHeader("X-DWP-Permissions","APP.WORKPLACE:CREATE");
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(filter,"hasWorkplaceAuthority",own)).isTrue();
        for(String suffix:List.of("/sites/"+UUID.randomUUID()+"/access-rules/changes","/delegations/changes","/booking-policy/review")) {
            var admin=new MockHttpServletRequest("POST","/v1/admin/workplace/experience/collaboration"+suffix);
            admin.addHeader("X-DWP-Permissions","ADMIN.WORKPLACE:MANAGE");
            assertThat((Boolean)ReflectionTestUtils.invokeMethod(filter,"hasWorkplaceAdminAuthority",admin)).isTrue();
        }
        var connector=new MockHttpServletRequest("PUT","/v1/admin/workplace/experience/collaboration/connectors/ACTUAL_PRESENCE");
        connector.addHeader("X-DWP-Permissions","ADMIN.WORKPLACE:MANAGE");
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(filter,"hasWorkplaceAdminAuthority",connector)).isTrue();
        var ordinary=new MockHttpServletRequest("POST","/v1/admin/workplace/resources"); ordinary.addHeader("X-DWP-Permissions","ADMIN.WORKPLACE:CREATE");
        assertThat((Boolean)ReflectionTestUtils.invokeMethod(filter,"hasWorkplaceAdminAuthority",ordinary)).isTrue();
    }
    private static MockHttpServletRequest request(String method,String suffix,UUID site) {
        var r=new MockHttpServletRequest(method,"/v1/admin/workplace/experience/facilities"+suffix);
        r.addHeader("X-DWP-Tenant-ID","1");r.addHeader("X-DWP-User-ID","7");r.addParameter("siteId",site.toString());return r;
    }
}
