package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV3Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAuthorityDecisionProvider.AccessMode;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.EnforcementState;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.ScopeKind;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.RuntimeContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class NavigationPolicyAggregateV3ControllerTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-17T00:00:00Z");
    private static final String CONTEXT_SCOPE = "hcm-scope-" + "a".repeat(40);
    private static final String AUTHORITY_ATTRIBUTE =
            HcmPlatformPepRegistry.class.getName() + ".authority";

    @Test
    void exactTenantProjectionReturnsInactiveStaticCarrierAndRetainsRouteAliases()
            throws Exception {
        NavigationPolicyAggregateV3Service service =
                mock(NavigationPolicyAggregateV3Service.class);
        when(service.projection(any(), eq(AccessMode.NORMAL)))
                .thenReturn(inactiveProjection());
        MockMvc mvc = mvc(service);

        mvc.perform(get(NavigationPolicyAggregateV3Controller.PROJECTION_PATH)
                        .queryParam("navigationScope", "TENANT")
                        .requestAttr(AUTHORITY_ATTRIBUTE, projectionAuthority())
                        .headers(trustedHeaders()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.data.schemaVersion").value("3"))
                .andExpect(jsonPath("$.data.authorizationRegistryVersion").value(46))
                .andExpect(jsonPath("$.data.authorizationRegistryChecksum").value(
                        NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM))
                .andExpect(jsonPath("$.data.enforcementState").value("OFF"))
                .andExpect(jsonPath("$.data.navigationContextScopeKey")
                        .value(CONTEXT_SCOPE))
                .andExpect(jsonPath("$.data.authoritySelfScopeKey").doesNotExist())
                .andExpect(jsonPath("$.data.revisionPin").doesNotExist())
                .andExpect(jsonPath("$.data.authorityDecisionCount").value(0))
                .andExpect(jsonPath("$.data.admittedPolicyRoutes").isEmpty())
                .andExpect(jsonPath(
                        "$.data.workbenches[0].groups[0].nodes[0].routeAliases[0]")
                        .value("/hr/me/employment-documents"));

        ArgumentCaptor<RuntimeContext> context =
                ArgumentCaptor.forClass(RuntimeContext.class);
        verify(service).projection(context.capture(), eq(AccessMode.NORMAL));
        assertThat(context.getValue().tenantId()).isEqualTo(42);
        assertThat(context.getValue().subjectId()).isEqualTo(101);
        assertThat(context.getValue().contextScopeKey()).isEqualTo(CONTEXT_SCOPE);
        assertThat(context.getValue().requestedLegalEntityPublicId()).isNull();
    }

    @Test
    void exactDeepLinkRoutePreservesRequestedAdmittedAndIaTargets() throws Exception {
        NavigationPolicyAggregateV3Service service =
                mock(NavigationPolicyAggregateV3Service.class);
        String pin = "nav-v3-" + "b".repeat(64);
        when(service.deepLinkDecision(
                any(), eq(AccessMode.NORMAL), eq("BASE:my-hr-profile"),
                eq("/hr/me"), eq(pin)))
                .thenReturn(new DeepLinkDecisionV3(
                        true, "BASE:my-hr-profile", "/hr/me", "/hr/me",
                        "/hr/me/profile", pin, null));

        mvc(service).perform(get(NavigationPolicyAggregateV3Controller.DEEP_LINK_PATH)
                        .queryParam("navigationScope", "TENANT")
                        .queryParam("nodeId", "BASE:my-hr-profile")
                        .queryParam("requestedPath", "/hr/me")
                        .queryParam("revisionPin", pin)
                        .requestAttr(AUTHORITY_ATTRIBUTE, deepLinkAuthority())
                        .headers(trustedHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedPath").value("/hr/me"))
                .andExpect(jsonPath("$.data.admittedTarget").value("/hr/me"))
                .andExpect(jsonPath("$.data.iaCanonicalRoute")
                        .value("/hr/me/profile"))
                .andExpect(jsonPath("$.data.revisionPin").value(pin));
    }

    @Test
    void rejectsLegalEntityExternalSelfScopeAndWrongTrustedRouteBeforeService() {
        NavigationPolicyAggregateV3Service service =
                mock(NavigationPolicyAggregateV3Service.class);
        NavigationPolicyAggregateV3Controller controller =
                new NavigationPolicyAggregateV3Controller(service);
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockHttpServletRequest legal = trustedRequest();
        legal.setAttribute(AUTHORITY_ATTRIBUTE, projectionAuthority());
        legal.addParameter("legalEntityPublicId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> controller.projection(
                legal, ScopeKind.TENANT, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant SELF");

        MockHttpServletRequest selectedScope = trustedRequest();
        selectedScope.setAttribute(AUTHORITY_ATTRIBUTE, projectionAuthority());
        selectedScope.addHeader("X-DWP-Selected-Scope-Key",
                "scope-" + "f".repeat(32));
        assertThatThrownBy(() -> controller.projection(
                selectedScope, ScopeKind.TENANT, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X-DWP-Selected-Scope-Key");

        MockHttpServletRequest wrongRoute = trustedRequest();
        wrongRoute.setAttribute(AUTHORITY_ATTRIBUTE, deepLinkAuthority());
        assertThatThrownBy(() -> controller.projection(
                wrongRoute, ScopeKind.TENANT, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Exact trusted aggregate-v3 route authority");

        verify(service, org.mockito.Mockito.never()).projection(any(), any());
    }

    private static MockMvc mvc(NavigationPolicyAggregateV3Service service) {
        return standaloneSetup(new NavigationPolicyAggregateV3Controller(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper()))
                .build();
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static org.springframework.http.HttpHeaders trustedHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("X-DWP-Tenant-ID", "42");
        headers.add("X-DWP-User-ID", "101");
        headers.add("X-DWP-Person-Public-ID",
                "00000000-0000-4000-8000-000000000101");
        headers.add("X-DWP-Permissions", "APP.HCM:VIEW");
        headers.add("X-DWP-Roles", "HRIS_EMPLOYEE");
        headers.add("X-DWP-Context-Scope-Key", CONTEXT_SCOPE);
        headers.add("X-DWP-Current-Revalidate-At", NOW.plusMinutes(10).toString());
        headers.add("X-DWP-Active-Access-Mode", "NORMAL");
        return headers;
    }

    private static MockHttpServletRequest trustedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        trustedHeaders().forEach((name, values) ->
                values.forEach(value -> request.addHeader(name, value)));
        return request;
    }

    private static HcmPlatformPepRegistry.RouteAuthority projectionAuthority() {
        return authority(
                NavigationPolicyAggregateV3Controller.PROJECTION_ROUTE,
                NavigationPolicyAggregateV3Controller.PROJECTION_PATH);
    }

    private static HcmPlatformPepRegistry.RouteAuthority deepLinkAuthority() {
        return authority(
                NavigationPolicyAggregateV3Controller.DEEP_LINK_ROUTE,
                NavigationPolicyAggregateV3Controller.DEEP_LINK_PATH);
    }

    private static HcmPlatformPepRegistry.RouteAuthority authority(
            String route,
            String path) {
        return new HcmPlatformPepRegistry.RouteAuthority(
                route, "DATA", "GET", path,
                NavigationPolicyAggregateV3Controller.RUNTIME_PROFILE, false,
                "hcm.app.shell.view", "ENABLED", null);
    }

    private static EffectiveNavigationV3 inactiveProjection() {
        return new EffectiveNavigationV3(
                NavigationPolicyAggregateV3Dtos.SCHEMA_VERSION,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256,
                NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.SHELL_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256,
                NavigationPolicyAggregateV3Catalog.REGISTRY_VERSION,
                NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM,
                EnforcementState.OFF, false, null, null, null, null,
                CONTEXT_SCOPE, null, null, null, null, null, null, null,
                0, List.of(), List.of(new EffectiveWorkbench(
                "HRIS-WB-MY-HR", 1, "BASE:my-employment-documents",
                List.of(new EffectiveGroup(
                        "MY-02", 1, "BASE:my-employment-documents",
                        List.of(new EffectiveNode(
                                "BASE:my-employment-documents", 0,
                                List.of("/hr/me/employment-documents"))))))),
                null, NOW);
    }
}
