package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class HomeStudioPlatformSecurityFilterTest {

    private final PlatformSecurityFilter filter = new PlatformSecurityFilter(
            "trusted", "runtime", new ObjectMapper().findAndRegisterModules());

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/home-views",
            "/v1/home-experience/background",
            "/v1/home-templates/00000000-0000-0000-0000-000000000001",
            "/v1/home-composer/proposals",
            "/v1/home-preferences",
            "/v2/home",
            "/v2/home/widget-actions:execute"
    })
    void accountHomeSettingsAcceptOnlyTenantDataPlaneIdentity(String path) throws Exception {
        MockHttpServletRequest tenant = request(path, "TENANT", "EMPLOYEE");
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(tenant, accepted, new MockFilterChain());
        assertThat(accepted.getStatus()).isEqualTo(200);

        MockHttpServletRequest provider = request(path, "PROVIDER", "PROVIDER_ADMIN");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(provider, denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(denied.getContentAsString())
                .contains("Personal Home settings require a tenant data-plane identity.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/home-views",
            "/v1/home-experience",
            "/v1/home-templates",
            "/v1/home-composer/proposals",
            "/v1/home-preferences",
            "/v2/home"
    })
    void missingIdentityPlaneFailsClosed(String path) throws Exception {
        MockHttpServletRequest missingPlane = request(path, null, "EMPLOYEE");
        MockHttpServletResponse denied = new MockHttpServletResponse();

        filter.doFilter(missingPlane, denied, new MockFilterChain());

        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(denied.getContentAsString())
                .contains("Personal Home settings require a tenant data-plane identity.");
    }

    @Test
    void adminHomeExperienceReadsRequireExactViewAuthority() throws Exception {
        MockHttpServletRequest allowed = request(
                "GET", "/v1/admin/home-experience/revisions", "TENANT",
                "HOME_EXPERIENCE_READER", "ADMIN.HOME_EXPERIENCE:VIEW");
        MockHttpServletResponse accepted = new MockHttpServletResponse();

        filter.doFilter(allowed, accepted, new MockFilterChain());

        assertThat(accepted.getStatus()).isEqualTo(200);
        for (MockHttpServletRequest denied : java.util.List.of(
                request("GET", "/v1/admin/home-experience", "TENANT",
                        "TENANT_ADMIN", null),
                request("GET", "/v1/admin/home-experience", "TENANT",
                        "TENANT_ADMIN", "ADMIN.HOME_EXPERIENCE_LEGACY:VIEW"),
                request("GET", "/v1/admin/home-experience", "PROVIDER",
                        "PROVIDER_ADMIN", "ADMIN.HOME_EXPERIENCE:VIEW"),
                request("GET", "/v1/admin/home-experience", null,
                        "TENANT_ADMIN", "ADMIN.HOME_EXPERIENCE:VIEW"))) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(denied, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "X-DWP-Support-Session-ID",
            "X-DWP-Provider-Tenant-ID",
            "X-DWP-Actor-Tenant-ID"
    })
    void runtimeHomeRejectsAmbientCrossPlaneHeaders(String header) throws Exception {
        MockHttpServletRequest request = request("/v2/home", "TENANT", "EMPLOYEE");
        request.addHeader(header, "ambient-value");
        if (header.equals("X-DWP-Support-Session-ID")) {
            request.addHeader("X-DWP-Support-Scopes", "PLATFORM_READ");
        }
        MockHttpServletResponse denied = new MockHttpServletResponse();

        filter.doFilter(request, denied, new MockFilterChain());

        assertThat(denied.getStatus()).isEqualTo(403);
    }

    @Test
    void adminHomeExperienceMutationsRequireManageAuthority() throws Exception {
        String path = "/v1/admin/home-experience/revisions/17/rollback";
        MockHttpServletRequest allowed = request(
                "POST", path, "TENANT", "HOME_EXPERIENCE_MANAGER",
                "ADMIN.HOME_EXPERIENCE:MANAGE");
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(allowed, accepted, new MockFilterChain());
        assertThat(accepted.getStatus()).isEqualTo(200);

        MockHttpServletRequest viewOnly = request(
                "POST", path, "TENANT", "TENANT_ADMIN",
                "ADMIN.HOME_EXPERIENCE:VIEW");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(viewOnly, denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(denied.getContentAsString())
                .contains("Home Experience administration permission is required.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/home-views-lookalike",
            "/v1/home-experience-lookalike",
            "/v1/home-templates-lookalike",
            "/v1/home-composer/proposals-lookalike",
            "/v1/home-preferences-lookalike"
    })
    void unrelatedLookalikesAreNotClaimedByTheHomeSettingsBoundary(String path)
            throws Exception {
        MockHttpServletRequest provider = request(path, "PROVIDER", "PROVIDER_ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(provider, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void adminHomeExperienceLookalikeIsNotClaimedByTheExactBoundary() throws Exception {
        MockHttpServletRequest request = request(
                "GET", "/v1/admin/home-experience-lookalike", "TENANT",
                "TENANT_ADMIN", null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest request(String path, String plane, String roles) {
        return request("GET", path, plane, roles, null);
    }

    private MockHttpServletRequest request(
            String method, String path, String plane, String roles, String permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "11");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "7");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, roles);
        if (plane != null) request.addHeader("X-DWP-Identity-Plane", plane);
        if (permissions != null) {
            request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, permissions);
        }
        return request;
    }
}
