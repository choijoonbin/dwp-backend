package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class NavigationPolicyControllerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-14T04:00:00Z");
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID POLICY = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID REVISION = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID RECEIPT = UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-000000000501");

    @Test
    void exposesSealedDraftRouteAndRequiresCommandHeaders() throws Exception {
        NavigationPolicyService service = mock(NavigationPolicyService.class);
        when(service.createDraft(any(), any(), any())).thenReturn(new CommandReceipt(
                RECEIPT, "CREATE_DRAFT", POLICY, REVISION, 1,
                LifecycleState.DRAFT, 0, "a".repeat(64), CORRELATION, NOW));
        MockMvc mvc = mvc(service);
        PolicyDocument policy = NavigationPolicyCanonicalTest.policy(ScopeKind.TENANT, null, true);
        DraftCommand body = new DraftCommand(
                ScopeKind.TENANT, null, null, RolloutMode.ENFORCED,
                NavigationPolicyCanonical.WORKBENCH_ID, policy.groups(), 0L,
                "a".repeat(64));

        mvc.perform(post("/v1/admin/hris/navigation-policies/drafts")
                        .headers(adminHeaders())
                        .requestAttr(
                                HcmPlatformPepRegistry.class.getName() + ".authority",
                                routeAuthority())
                        .header("Idempotency-Key", "create-navigation-policy")
                        .header("X-Correlation-ID", CORRELATION)
                        .contentType("application/json")
                        .content(mapper().writeValueAsBytes(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.revisionId").value(REVISION.toString()))
                .andExpect(jsonPath("$.data.objectVersion").value(0));

        ArgumentCaptor<NavigationPolicyService.CommandContext> command =
                ArgumentCaptor.forClass(NavigationPolicyService.CommandContext.class);
        verify(service).createDraft(any(), command.capture(), eq(body));
        assertThat(command.getValue().populationScopeDigest())
                .isEqualTo("b".repeat(64));
        assertThat(command.getValue().originRouteContractKey())
                .isEqualTo("route.hcm.configuration.navigation-policy-draft-create.action");
    }

    @Test
    void rejectsMissingDuplicateAndMalformedTrustedPopulationDigest() throws Exception {
        NavigationPolicyService service = mock(NavigationPolicyService.class);
        MockMvc mvc = mvc(service);
        PolicyDocument policy = NavigationPolicyCanonicalTest.policy(
                ScopeKind.TENANT, null, true);
        DraftCommand body = new DraftCommand(
                ScopeKind.TENANT, null, null, RolloutMode.ENFORCED,
                NavigationPolicyCanonical.WORKBENCH_ID, policy.groups(), 0L,
                "a".repeat(64));

        org.springframework.http.HttpHeaders missing = adminHeaders();
        missing.remove("X-DWP-Population-Scope-Digest");
        assertThatThrownBy(() -> mvc.perform(
                commandRequest(missing, body)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        org.springframework.http.HttpHeaders duplicate = adminHeaders();
        duplicate.add("X-DWP-Population-Scope-Digest", "c".repeat(64));
        assertThatThrownBy(() -> mvc.perform(
                commandRequest(duplicate, body)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        org.springframework.http.HttpHeaders malformed = adminHeaders();
        malformed.set("X-DWP-Population-Scope-Digest", "not-a-digest");
        assertThatThrownBy(() -> mvc.perform(
                commandRequest(malformed, body)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runtimeProjectionUsesOnlyTrustedInstalledAndPageCapabilityHeaders() throws Exception {
        NavigationPolicyService service = mock(NavigationPolicyService.class);
        EffectiveProjection projection = new EffectiveProjection(
                NavigationPolicyCanonical.WORKBENCH_ID, EnforcementState.ENFORCED,
                true, POLICY, 1L, null, "nav-" + "a".repeat(40),
                List.of(new EffectiveGroup(
                        "HROPS-01", 0, "BASE:hr-operations-home",
                        List.of(new EffectiveNode("BASE:hr-operations-home", 0)))),
                null, NOW);
        when(service.projection(any(), any())).thenReturn(projection);
        MockMvc mvc = mvc(service);

        mvc.perform(get("/v1/hris/navigation/projection")
                        .queryParam("workbenchId", NavigationPolicyCanonical.WORKBENCH_ID)
                        .header("X-DWP-Tenant-ID", "42")
                        .header("X-DWP-User-ID", "101")
                        .header("X-DWP-Person-Public-ID", SUBJECT)
                        .header("X-DWP-Permissions", "APP.HCM:VIEW")
                        .header("X-DWP-Context-Scope-Key", scopeKey())
                        .header("X-DWP-Current-Revalidate-At", NOW.plusHours(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyEnforced").value(true))
                .andExpect(jsonPath("$.data.groups[0].nodes[0].nodeId")
                        .value("BASE:hr-operations-home"));

        verify(service).projection(
                any(), eq(NavigationPolicyCanonical.WORKBENCH_ID));
    }

    private static org.springframework.http.HttpHeaders adminHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("X-DWP-Tenant-ID", "42");
        headers.add("X-DWP-User-ID", "101");
        headers.add("X-DWP-Subject-Principal-Public-ID", SUBJECT.toString());
        headers.add("X-DWP-Permissions", "APP.HCM:VIEW,CONFIG.HRIS_NAVIGATION_POLICY:UPDATE");
        headers.add("X-DWP-Roles", "TENANT_ADMIN");
        headers.add("X-DWP-Context-Key", "hcm-management-context");
        headers.add("X-DWP-Context-Scope-Key", scopeKey());
        headers.add("X-DWP-Current-Decision-Revision", "decision-7");
        headers.add("X-DWP-Current-Revalidate-At", NOW.plusHours(1).toString());
        headers.add("X-DWP-Population-Scope-Digest", "b".repeat(64));
        headers.add("X-DWP-Field-Policy-Revision", "7");
        headers.add("X-DWP-Authorization-Revision", "11");
        headers.add("X-Purpose-Code", NavigationPolicyService.PURPOSE);
        return headers;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            commandRequest(
            org.springframework.http.HttpHeaders headers, DraftCommand body)
            throws Exception {
        return post("/v1/admin/hris/navigation-policies/drafts")
                .headers(headers)
                .requestAttr(
                        HcmPlatformPepRegistry.class.getName() + ".authority",
                        routeAuthority())
                .header("Idempotency-Key", "create-navigation-policy")
                .header("X-Correlation-ID", CORRELATION)
                .contentType("application/json")
                .content(mapper().writeValueAsBytes(body));
    }

    private static HcmPlatformPepRegistry.RouteAuthority routeAuthority() {
        return new HcmPlatformPepRegistry.RouteAuthority(
                "route.hcm.configuration.navigation-policy-draft-create.action",
                "ACTION", "POST", "/v1/admin/hris/navigation-policies/drafts",
                "test-profile", false, "test-capability", "ENABLED", null);
    }

    private static MockMvc mvc(NavigationPolicyService service) {
        return standaloneSetup(new NavigationPolicyController(service))
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
}
