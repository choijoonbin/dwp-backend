package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.EnforcementState;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.ScopeKind;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class NavigationPolicyAggregateV2ControllerTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-17T00:00:00Z");

    @Test
    void exactGlobalProjectionRouteReturnsPinnedAggregateAndRejectsBrowserEligibility() throws Exception {
        NavigationPolicyAggregateV2Service runtime =
                mock(NavigationPolicyAggregateV2Service.class);
        NavigationPolicyAggregateV2LifecycleService lifecycle =
                mock(NavigationPolicyAggregateV2LifecycleService.class);
        when(runtime.projection(any())).thenReturn(new EffectiveNavigation(
                SCHEMA_VERSION, NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256,
                NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.SHELL_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256,
                EnforcementState.ENFORCED, true, null, null, null,
                null, null, "nav-v2-" + "a".repeat(64), List.of(), null, NOW));
        MockMvc mvc = mvc(runtime, lifecycle);

        mvc.perform(get("/v2/hris/navigation/projection")
                        .queryParam("navigationScope", "TENANT")
                        .requestAttr(authorityAttribute(), tenantProjectionAuthority())
                        .header("X-DWP-Tenant-ID", "42")
                        .header("X-DWP-User-ID", "101")
                        .header("X-DWP-Person-Public-ID",
                                "00000000-0000-0000-0000-000000000101")
                        .header("X-DWP-Permissions", "APP.HCM:VIEW")
                        .header("X-DWP-Context-Scope-Key", scopeKey())
                        .header("X-DWP-Current-Revalidate-At", NOW.plusHours(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topologyRevision").value(
                        NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION))
                .andExpect(jsonPath("$.data.generatedIaSha256").value(
                        NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256));
        verify(runtime).projection(any());

        assertThatThrownBy(() -> mvc.perform(get("/v2/hris/navigation/projection")
                        .queryParam("navigationScope", "TENANT")
                        .requestAttr(authorityAttribute(), tenantProjectionAuthority())
                        .header("X-DWP-HRIS-Deployed-Nodes", "BASE:my-hr-profile")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runtimeScopeAndTrustedQuerySplitAuthorityMustMatchExactly() {
        NavigationPolicyAggregateV2Service runtime =
                mock(NavigationPolicyAggregateV2Service.class);
        NavigationPolicyAggregateV2LifecycleService lifecycle =
                mock(NavigationPolicyAggregateV2LifecycleService.class);
        NavigationPolicyAggregateV2Controller controller =
                new NavigationPolicyAggregateV2Controller(runtime, lifecycle);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setMethod("GET");
        request.addHeader("X-DWP-Tenant-ID", "42");
        request.addHeader("X-DWP-User-ID", "101");
        request.addHeader("X-DWP-Person-Public-ID",
                "00000000-0000-0000-0000-000000000101");
        request.addHeader("X-DWP-Permissions", "APP.HCM:VIEW");
        request.addHeader("X-DWP-Context-Scope-Key", scopeKey());
        request.addHeader("X-DWP-Current-Revalidate-At", NOW.plusHours(1).toString());
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        UUID legalEntity = UUID.fromString(
                "00000000-0000-0000-0000-000000000501");

        request.setAttribute(authorityAttribute(), tenantProjectionAuthority());
        assertThatThrownBy(() -> controller.projection(
                request, ScopeKind.TENANT, legalEntity, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbids legalEntityPublicId");

        request.setAttribute(authorityAttribute(), legalProjectionAuthority());
        assertThatThrownBy(() -> controller.projection(
                request, ScopeKind.LEGAL_ENTITY, null, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires legalEntityPublicId");

        request.setAttribute(authorityAttribute(), tenantProjectionAuthority());
        assertThatThrownBy(() -> controller.projection(
                request, ScopeKind.LEGAL_ENTITY, legalEntity, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted route authority");

        request.setAttribute(authorityAttribute(), new com.dwp.services.platform.security
                .HcmPlatformPepRegistry.RouteAuthority(
                "route.hcm.shell.navigation-aggregate-legal-entity.data", "DATA",
                "GET", "/v2/hris/navigation/projection", "management-reader", false,
                "hcm.app.shell.view", "ENABLED", null));
        assertThatThrownBy(() -> controller.projection(
                request, ScopeKind.LEGAL_ENTITY, legalEntity, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted route authority");

        request.setAttribute(authorityAttribute(), tenantProjectionAuthority());
        assertThatThrownBy(() -> controller.deepLinkDecision(
                request, "BASE:my-hr-profile", "/hr/profile", "nav-v2-" + "a".repeat(64),
                ScopeKind.TENANT, null, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted route authority");
    }

    private static MockMvc mvc(
            NavigationPolicyAggregateV2Service runtime,
            NavigationPolicyAggregateV2LifecycleService lifecycle) {
        return standaloneSetup(new NavigationPolicyAggregateV2Controller(runtime, lifecycle))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper()))
                .build();
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static String scopeKey() {
        return "hcm-scope-" + "a".repeat(40);
    }

    private static String authorityAttribute() {
        return com.dwp.services.platform.security.HcmPlatformPepRegistry.class.getName()
                + ".authority";
    }

    private static com.dwp.services.platform.security.HcmPlatformPepRegistry.RouteAuthority
            tenantProjectionAuthority() {
        return new com.dwp.services.platform.security.HcmPlatformPepRegistry.RouteAuthority(
                "route.hcm.shell.navigation-aggregate.data", "DATA", "GET",
                "/v2/hris/navigation/projection", "navigation-shell-reader", false,
                "hcm.app.shell.view", "ENABLED", null);
    }

    private static com.dwp.services.platform.security.HcmPlatformPepRegistry.RouteAuthority
            legalProjectionAuthority() {
        return new com.dwp.services.platform.security.HcmPlatformPepRegistry.RouteAuthority(
                "route.hcm.shell.navigation-aggregate-legal-entity.data", "DATA", "GET",
                "/v2/hris/navigation/projection", "hr-operations-reader", false,
                "hcm.operations.workforce.read", "ENABLED", null);
    }
}
