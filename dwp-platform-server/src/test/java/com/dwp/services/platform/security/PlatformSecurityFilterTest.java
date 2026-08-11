package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformSecurityFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void rejectsDirectRequestsWithoutGatewayServiceIdentity() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/reference-sets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("E2000");
    }

    @Test
    void requiresAnAdministratorRoleForTheAdminSurface() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/reference-sets");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "EMPLOYEE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("E2001");
    }

    @Test
    void acceptsVerifiedTenantAdministratorsAndClearsTheActorContext() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/reference-sets");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "EMPLOYEE,TENANT_ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(RequestActorContext.current()).isEmpty();
    }

    @Test
    void acceptsAuditAccessFromScopedPermissionInsteadOfBuiltInRoleName() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/audit-control/overview");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "CUSTOM_AUDITOR");
        request.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.AUDIT_VIEW:VIEW");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(RequestActorContext.current()).isEmpty();
    }

    @Test
    void rejectsAuditAccessWithoutAResolvedPermission() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/audit-control/overview");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "AUDITOR");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsWorkspaceAccessWithoutAResolvedPermission() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/workspace/work-items");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "PROVIDER_ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Workspace permission is required.");
    }

    @Test
    void acceptsWorkspaceAccessWithAResolvedPermission() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/workspace/work-items");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.WORK:VIEW");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void acceptsTheRestrictedRuntimeIdentityOnlyForCatalogReads() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest catalogRequest = request("/v1/catalog/registry-entries/AGENT/PLANNER");
        catalogRequest.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        catalogRequest.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        catalogRequest.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        MockHttpServletResponse catalogResponse = new MockHttpServletResponse();

        filter.doFilter(catalogRequest, catalogResponse, new MockFilterChain());

        assertThat(catalogResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest adminRequest = request("/v1/admin/registry-entries");
        adminRequest.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        adminRequest.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        adminRequest.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        adminRequest.addHeader(PlatformSecurityFilter.ROLES_HEADER, "ADMIN");
        MockHttpServletResponse adminResponse = new MockHttpServletResponse();

        filter.doFilter(adminRequest, adminResponse, new MockFilterChain());

        assertThat(adminResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void acceptsOnlyThePlatformResourcesGrantedByAResolvedSupportSession() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/tenant-branding");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "PROVIDER_SUPPORT");
        request.addHeader(PlatformSecurityFilter.SUPPORT_SESSION_HEADER, "session-1");
        request.addHeader(PlatformSecurityFilter.SUPPORT_SCOPES_HEADER, "TENANT_CONFIGURATION_READ");
        request.addHeader(PlatformSecurityFilter.ACTOR_TENANT_HEADER, "3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void preventsReadOnlySupportSessionsFromChangingTenantConfiguration() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/v1/admin/tenant-branding");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "ADMIN,PROVIDER_ADMIN");
        request.addHeader(PlatformSecurityFilter.SUPPORT_SESSION_HEADER, "session-1");
        request.addHeader(PlatformSecurityFilter.SUPPORT_SCOPES_HEADER, "TENANT_CONFIGURATION_READ");
        request.addHeader(PlatformSecurityFilter.ACTOR_TENANT_HEADER, "3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void preventsSupportSessionsFromEnteringUnrelatedAdminSurfaces() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/reference-sets");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "ADMIN,PROVIDER_ADMIN");
        request.addHeader(PlatformSecurityFilter.SUPPORT_SESSION_HEADER, "session-1");
        request.addHeader(PlatformSecurityFilter.SUPPORT_SCOPES_HEADER, "TENANT_CONFIGURATION_WRITE");
        request.addHeader(PlatformSecurityFilter.ACTOR_TENANT_HEADER, "3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    private MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }
}
