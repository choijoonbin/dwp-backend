package com.dwp.services.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WidgetRegistryProviderPlatformSecurityFilterTest {
    private final PlatformSecurityFilter filter = new PlatformSecurityFilter(
            "trusted", "runtime", new ObjectMapper().findAndRegisterModules());

    @Test
    void isolatesProviderWidgetRegistryBehindTrustedProviderRouteMarker() throws Exception {
        MockHttpServletRequest provider = request(
                "PROVIDER_ADMIN", "PROVIDER", "WIDGET_REGISTRY_PROVIDER");
        MockHttpServletResponse providerResponse = new MockHttpServletResponse();

        filter.doFilter(provider, providerResponse, new MockFilterChain());

        assertThat(providerResponse.getStatus()).isEqualTo(200);

        for (MockHttpServletRequest denied : List.of(
                request("TENANT_ADMIN", "TENANT", "WIDGET_REGISTRY_PROVIDER"),
                request("PROVIDER_ADMIN", "PROVIDER", null),
                request("TENANT_ADMIN", "TENANT", null))) {
            MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
            filter.doFilter(denied, deniedResponse, new MockFilterChain());
            assertThat(deniedResponse.getStatus()).isEqualTo(403);
        }
    }

    private static MockHttpServletRequest request(
            String roles, String identityPlane, String controlPlane) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/admin/widget-definitions");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "900001");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "1");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, roles);
        request.addHeader("X-DWP-Identity-Plane", identityPlane);
        if (controlPlane != null) {
            request.addHeader(PlatformSecurityFilter.CONTROL_PLANE_HEADER, controlPlane);
        }
        return request;
    }
}
