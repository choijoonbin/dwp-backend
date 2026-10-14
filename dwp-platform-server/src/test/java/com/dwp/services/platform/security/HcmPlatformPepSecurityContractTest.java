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
    void loadsOnlyTheTwentyThreeExactSysHandoffBindings() {
        assertThat(registry.bindingContracts()).hasSize(23)
                .extracting(HcmPlatformPepRegistry.BindingContract::routeContractKey)
                .contains(
                        "route.hcm.configuration.settings-reference.page",
                        "route.hcm.configuration.configurations.data",
                        "route.hcm.configuration.configuration-version-publish.action",
                        "route.hcm.configuration.navigation-policy-workspace.page",
                        "route.hcm.configuration.navigation-policy-revision-publish.action",
                        "route.hcm.operations.navigation-projection.data",
                        "route.hcm.audit.evidence.page",
                        "route.hcm.audit.evidence-detail.data");
        assertThat(registry.governsPathFamily(
                "/v1/admin/hris/configurations/c1/versions/2/publish")).isTrue();
        assertThat(registry.governsPathFamily("/v1/admin/users")).isFalse();
        assertThat(registry.governsPathFamily("/v1/admin/hris/other")).isFalse();
        assertThat(registry.governsPathFamily(
                "/v1/hris/workbench/settings-reference/extra")).isFalse();
        assertThat(registry.governsPathFamily(
                "/v1/admin/hris/configurations-admin")).isFalse();
        assertThat(registry.governsPathFamily(
                "/v1/admin/hris/configurations%2fc1")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v1/admin/hris/navigation-policies/workspace")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v1/hris/navigation/projection")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v1/admin/audit-control/events")).isTrue();
        assertThat(registry.governsPathFamily(
                "/v1/admin/audit-control/events/10000000-0000-4000-8000-000000000001"))
                .isTrue();
        assertThat(registry.governsPathFamily(
                "/v1/admin/audit-control/cases")).isFalse();
    }

    @Test
    void auditReadKeepsHighRiskActivationWithoutCommandBinding() {
        HcmPlatformPepRegistry.Decision decision = registry.authorize(
                new HcmPlatformPepRegistry.RequestEvidence(
                        "GET",
                        "/v1/admin/audit-control/events",
                        java.util.Set.of("ADMIN.AUDIT_VIEW:VIEW"),
                        "route.hcm.audit.evidence.page"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.authority().routeKind()).isEqualTo("PAGE");
        assertThat(decision.authority().highRisk()).isTrue();
        assertThat(decision.authority().capabilityContractKey())
                .isEqualTo("hcm.audit.evidence.view");
        assertThat(decision.authority().activationPolicy())
                .isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertThat(decision.authority().stepUpCommandBinding()).isNull();
    }

    @Test
    void sharedAuditPathKeepsGenericCompatibilityButFencesHrisAuditors() throws Exception {
        MockHttpServletRequest generic = base(
                "GET", "/v1/admin/audit-control/events");
        generic.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.AUDIT_VIEW:VIEW");
        MockHttpServletResponse genericResponse = new MockHttpServletResponse();
        filter.doFilter(generic, genericResponse, new MockFilterChain());
        assertThat(genericResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest auditor = base(
                "GET", "/v1/admin/audit-control/events");
        auditor.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.AUDIT_VIEW:VIEW");
        auditor.addHeader(PlatformSecurityFilter.ROLES_HEADER, "HRIS_AUDITOR");
        MockHttpServletResponse auditorResponse = new MockHttpServletResponse();
        filter.doFilter(auditor, auditorResponse, new MockFilterChain());
        assertThat(auditorResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void exactHcmAuditReadRequiresTheWholeElevatedIntersection() throws Exception {
        MockHttpServletRequest allowed = hcmAuditRequest();
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        filter.doFilter(allowed, allowedResponse, new MockFilterChain());
        assertThat(allowedResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest productOnly = hcmAuditRequest();
        productOnly.removeHeader(PlatformSecurityFilter.RESOURCE_ROLES_HEADER);
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        filter.doFilter(productOnly, deniedResponse, new MockFilterChain());
        assertThat(deniedResponse.getStatus()).isEqualTo(403);
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
    void browserHeadersCannotReplaceTheTrustedGatewayServiceToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/hris/workbench/settings-reference");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "101");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "7");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "CONFIG.HRIS_WORKBENCH:VIEW");
        trustedEvidence(request,
                "route.hcm.configuration.settings-reference.page", false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
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
    void navigationProjectionUsesHrOperationsAuthorityNotConfigurationAdmin() throws Exception {
        String path = "/v1/hris/navigation/projection";
        String route = "route.hcm.operations.navigation-projection.data";
        assertThat(invoke(
                "GET", path, route,
                "DATA.WORKFORCE:VIEW", false, false).getStatus()).isEqualTo(200);
        assertThat(invoke(
                "GET", path, route,
                "CONFIG.HRIS_NAVIGATION_POLICY:VIEW", false, false).getStatus())
                .isEqualTo(403);
    }

    @Test
    void navigationPublishExposesExactStepUpOwnerContract() throws Exception {
        String revisionId = "10000000-0000-4000-8000-000000000040";
        String path = "/v1/admin/hris/navigation-policies/revisions/"
                + revisionId + "/publish";
        String route =
                "route.hcm.configuration.navigation-policy-revision-publish.action";

        HcmPlatformPepRegistry.Decision decision = registry.authorize(
                new HcmPlatformPepRegistry.RequestEvidence(
                        "POST", path, java.util.Set.of(
                        "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH"), route));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.authority().highRisk()).isTrue();
        assertThat(decision.authority().capabilityContractKey())
                .isEqualTo("hcm.configuration.navigation-policy.publish");
        assertThat(decision.authority().activationPolicy())
                .isEqualTo("STEPUP-MGMT-HIGH-V1");
        assertThat(decision.authority().stepUpCommandBinding())
                .satisfies(binding -> {
                    assertThat(binding.targetType())
                            .isEqualTo("HRIS_NAVIGATION_POLICY_REVISION");
                    assertThat(binding.targetIdPathParameter()).isEqualTo("revisionId");
                    assertThat(binding.expectedObjectVersionSource()).isEqualTo("COMMAND_BODY");
                    assertThat(binding.expectedObjectVersionName()).isEqualTo("expectedVersion");
                    assertThat(binding.ownerServiceKey()).isEqualTo("platform");
                    assertThat(binding.audience()).isEqualTo("dwp-platform-server");
                });
        assertThat(invoke(
                "POST", path, route,
                "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH", true, false).getStatus())
                .isEqualTo(403);
        assertThat(invoke(
                "POST", path, route,
                "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH", true, true).getStatus())
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

    private MockHttpServletRequest hcmAuditRequest() {
        MockHttpServletRequest request = base(
                "GET", "/v1/admin/audit-control/events");
        request.setParameter("product", "HCM");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "HRIS_AUDITOR");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "APP.HCM:VIEW,ADMIN.AUDIT_VIEW:VIEW");
        request.addHeader(PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                "HRIS_DUTY_AUDITOR@RS_HCM_CONFIG");
        request.addHeader("X-DWP-Active-Access-Mode", "ELEVATED");
        request.addHeader("X-Purpose-Code", "HRIS_AUDIT_EVIDENCE");
        request.addHeader("X-DWP-Subject-Principal-Public-ID",
                "10000000-0000-4000-8000-000000000001");
        request.addHeader("X-DWP-Field-Policy-Revision", "17");
        request.addHeader("X-DWP-Authorization-Revision", "29");
        trustedEvidence(request, "route.hcm.audit.evidence.page", false);
        request.removeHeader(PlatformSecurityFilter.CONTEXT_HEADER);
        request.addHeader(PlatformSecurityFilter.CONTEXT_HEADER, "hcm.management");
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
