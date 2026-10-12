package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class HcmPlatformPepSecurityContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HcmPlatformPepRegistry registry =
            new HcmPlatformPepRegistry(objectMapper);
    private final PlatformSecurityFilter filter = new PlatformSecurityFilter(
            "trusted", "runtime", false, objectMapper,
            new PlatformCanaryPepRegistry(objectMapper),
            new PlatformApprovalsPepRegistry(objectMapper),
            registry);

    @Test
    void loadsOnlyTheTwelveExactSysHandoffBindings() {
        assertThat(registry.bindingContracts()).hasSize(12)
                .extracting(HcmPlatformPepRegistry.BindingContract::routeContractKey)
                .contains(
                        "route.hcm.configuration.settings-reference.page",
                        "route.hcm.configuration.configurations.data",
                        "route.hcm.configuration.configuration-version-publish.action");
        assertThat(registry.governsPathFamily(
                "/v1/admin/hris/configurations/c1/versions/2/publish")).isTrue();
        assertThat(registry.governsPathFamily("/v1/admin/users")).isFalse();
        assertThat(registry.governsPathFamily("/v1/admin/hris/other")).isFalse();
    }

    @Test
    void exactWorkbenchReadPassesWithoutGenericTenantAdminRole() throws Exception {
        MockHttpServletResponse response = invoke(
                "GET",
                "/v1/hris/workbench/settings-reference",
                "route.hcm.configuration.settings-reference.page",
                "CONFIG.HRIS_WORKBENCH:VIEW",
                false,
                false);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void genericRoleOrNearbyAdminPathCannotBypassExactRegistry() throws Exception {
        MockHttpServletResponse missing = invoke(
                "GET",
                "/v1/admin/hris/configurations",
                "route.hcm.configuration.configurations.data",
                "APP.HCM:VIEW",
                false,
                false);
        assertThat(missing.getStatus()).isEqualTo(403);

        MockHttpServletRequest broadAdmin = base(
                "GET", "/v1/admin/hris/configurations");
        broadAdmin.addHeader(PlatformSecurityFilter.ROLES_HEADER, "ADMIN");
        broadAdmin.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.HCM:VIEW");
        trustedEvidence(broadAdmin,
                "route.hcm.configuration.configurations.data", false);
        MockHttpServletResponse broadAdminResponse = new MockHttpServletResponse();
        filter.doFilter(broadAdmin, broadAdminResponse, new MockFilterChain());
        assertThat(broadAdminResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest nearby = base(
                "GET", "/v1/admin/hris/configuration-secrets");
        nearby.addHeader(PlatformSecurityFilter.ROLES_HEADER, "HRIS_CONFIG_AUTHOR");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(nearby, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void publishRequiresExactCapabilityRevisionAndElevatedMode() throws Exception {
        String path = "/v1/admin/hris/configurations/c1/versions/2/publish";
        String route = "route.hcm.configuration.configuration-version-publish.action";
        assertThat(invoke(
                "POST", path, route,
                "ACTION.HRIS_POLICY_PUBLISH:PUBLISH", true, false).getStatus())
                .isEqualTo(403);
        assertThat(invoke(
                "POST", path, route,
                "ACTION.HRIS_POLICY_PUBLISH:PUBLISH", true, true).getStatus())
                .isEqualTo(200);
    }

    @Test
    void encodedSeparatorAndWrongTrustedRouteFailClosed() throws Exception {
        assertThat(invoke(
                "GET",
                "/v1/admin/hris/configurations%2fc1",
                "route.hcm.configuration.configurations.data",
                "CONFIG.HRIS_POLICY:UPDATE",
                false,
                false).getStatus()).isEqualTo(403);
        assertThat(invoke(
                "GET",
                "/v1/admin/hris/configurations",
                "route.hcm.configuration.settings-reference.page",
                "CONFIG.HRIS_POLICY:UPDATE",
                false,
                false).getStatus()).isEqualTo(403);
    }

    private MockHttpServletResponse invoke(
            String method,
            String path,
            String route,
            String permissions,
            boolean stateChanging,
            boolean elevated) throws Exception {
        MockHttpServletRequest request = base(method, path);
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "HRIS_CONFIG_AUTHOR");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, permissions);
        trustedEvidence(request, route, stateChanging);
        if (elevated) request.addHeader("X-DWP-Active-Access-Mode", "ELEVATED");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletRequest base(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "101");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "7");
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        return request;
    }

    private void trustedEvidence(
            MockHttpServletRequest request,
            String route,
            boolean stateChanging) {
        request.addHeader(PlatformSecurityFilter.ROLLOUT_STATE_HEADER, "100");
        request.addHeader(PlatformSecurityFilter.ROLLOUT_REVISION_HEADER,
                "rollout-" + "0123456789abcdef".repeat(4));
        request.addHeader(PlatformSecurityFilter.ROLLOUT_COHORT_HEADER, "baseline");
        String revision = "psr-" + "0123456789abcdef".repeat(4);
        request.addHeader(PlatformSecurityFilter.ROUTE_CONTRACT_HEADER, route);
        request.addHeader(PlatformSecurityFilter.CURRENT_DECISION_REVISION_HEADER,
                revision);
        request.addHeader(PlatformSecurityFilter.CURRENT_REVALIDATE_AT_HEADER,
                "2030-01-01T00:00:00Z");
        request.addHeader(PlatformSecurityFilter.CONTEXT_HEADER, "hcm.configuration");
        request.addHeader(PlatformSecurityFilter.SCOPE_HEADER, "tenant-7");
        if (stateChanging) {
            request.addHeader(
                    PlatformSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                    revision);
        }
    }
}
