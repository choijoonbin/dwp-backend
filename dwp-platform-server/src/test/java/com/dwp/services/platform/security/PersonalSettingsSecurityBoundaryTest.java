package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalSettingsSecurityBoundaryTest {

    private final PersonalSettingsSecurityFilter filter = new PersonalSettingsSecurityFilter(
            new ObjectMapper().findAndRegisterModules());

    @Test
    void restrictsPersonalSettingsToTenantSelfServiceIdentity() throws Exception {
        MockHttpServletRequest provider = request("PROVIDER_ADMIN", "PROVIDER");
        MockHttpServletResponse providerResponse = new MockHttpServletResponse();

        filter.doFilter(provider, providerResponse, new MockFilterChain());

        assertThat(providerResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest tenant = request("WORKSPACE_MEMBER", "TENANT");
        MockHttpServletResponse tenantResponse = new MockHttpServletResponse();

        filter.doFilter(tenant, tenantResponse, new MockFilterChain());

        assertThat(tenantResponse.getStatus()).isEqualTo(200);
        assertThat(tenantResponse.getHeader("Cache-Control"))
                .isEqualTo("private, no-store, max-age=0");
    }

    private MockHttpServletRequest request(String role, String identityPlane) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/personal-settings/workspace");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, role);
        request.addHeader("X-DWP-Identity-Plane", identityPlane);
        return request;
    }
}
