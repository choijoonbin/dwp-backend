package com.dwp.services.space.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.space.api.SpaceController;
import com.dwp.services.space.domain.SpaceDtos;
import com.dwp.services.space.domain.SpaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpaceProductSurfacePepContractTest {

    private static final long TENANT = 42L;
    private static final long ACTOR = 17L;
    private static final String CURRENT_REVISION =
            "psr-" + "0123456789abcdef".repeat(4);
    private static final String ROLLOUT_REVISION =
            "rollout-" + "0123456789abcdef".repeat(4);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final SpaceService service = mock(SpaceService.class);
    private SpaceProductSurfacePepRegistry registry;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service);
        registry = new SpaceProductSurfacePepRegistry(objectMapper);
        mvc = MockMvcBuilders.standaloneSetup(new SpaceController(service))
                .addFilters(
                        new SpaceSecurityFilter("space-token", objectMapper),
                        new SpaceProductSurfacePepFilter(true, registry, objectMapper))
                .build();
    }

    @Test
    void crossTenantOpaqueScopeFailsClosedAtSpacesOwnerPep() throws Exception {
        var request = exact(
                get("/v1/home"), "route.spaces.work.home.page", "APP.SPACES:VIEW");
        request.with(raw -> {
            raw.removeHeader(SpaceProductSurfacePepFilter.SCOPE_HEADER);
            raw.addHeader(SpaceProductSurfacePepFilter.SCOPE_HEADER,
                    ProductSurfaceScopeKey.key(
                            TENANT + 1, ACTOR, "spaces", "spaces.work", "SELF", "SELF"));
            return raw;
        });

        mvc.perform(request).andExpect(status().isForbidden());

        verify(service, never()).home();
    }

    @Test
    void canonicalOpaqueScopeEscapeFailsClosedAtSpacesOwnerPep() throws Exception {
        var request = exact(
                get("/v1/home"), "route.spaces.work.home.page", "APP.SPACES:VIEW");
        request.with(raw -> {
            raw.removeHeader(SpaceProductSurfacePepFilter.SCOPE_HEADER);
            raw.addHeader(SpaceProductSurfacePepFilter.SCOPE_HEADER,
                    ProductSurfaceScopeKey.key(
                            TENANT, ACTOR, "spaces", "spaces.work", "OTHER", "SELF"));
            return raw;
        });

        mvc.perform(request).andExpect(status().isForbidden());

        verify(service, never()).home();
    }

    @Test
    void staleAuthorityRevisionFailsClosedBeforeSpacesAction() throws Exception {
        var request = exact(
                post("/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody()),
                "route.spaces.work.request-create.action",
                "ACTION.SPACE_REQUEST:CREATE");
        request.with(raw -> {
            raw.removeHeader(SpaceProductSurfacePepFilter.EXPECTED_DECISION_REVISION_HEADER);
            raw.addHeader(SpaceProductSurfacePepFilter.EXPECTED_DECISION_REVISION_HEADER,
                    "psr-" + "fedcba9876543210".repeat(4));
            return raw;
        });

        mvc.perform(request).andExpect(status().isConflict());

        verify(service, never()).createRequest(any(), any());
    }

    @Test
    void normalAndSupportModesCannotConfuseSpacesOwnerPep() throws Exception {
        var providerAsNormal = exact(
                get("/v1/home"), "route.spaces.work.home.page", "APP.SPACES:VIEW");
        providerAsNormal.with(raw -> {
            raw.removeHeader(SpaceSecurityFilter.ROLES_HEADER);
            raw.addHeader(SpaceSecurityFilter.ROLES_HEADER, "PROVIDER_SUPPORT");
            return raw;
        });
        mvc.perform(providerAsNormal).andExpect(status().isForbidden());

        var supportAsNormal = exact(
                get("/v1/home"), "route.spaces.work.home.page", "APP.SPACES:VIEW");
        supportAsNormal.header(SpaceProductSurfacePepFilter.SUPPORT_SESSION_HEADER, "support-1");
        mvc.perform(supportAsNormal).andExpect(status().isForbidden());

        var normalAsSupport = exact(
                get("/v1/home"), "route.spaces.work.home.page", "APP.SPACES:VIEW");
        normalAsSupport.with(raw -> {
            raw.removeHeader(SpaceProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER);
            raw.addHeader(SpaceProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER,
                    "PROVIDER_SUPPORT");
            return raw;
        });
        mvc.perform(normalAsSupport).andExpect(status().isForbidden());

        verify(service, never()).home();
    }

    @Test
    void spoofedInternalAuthorityHeadersCannotBypassSpaceServiceIdentity()
            throws Exception {
        var request = exact(
                get("/v1/home"), "route.spaces.work.home.page", "APP.SPACES:VIEW");
        request.with(raw -> {
            raw.removeHeader(SpaceSecurityFilter.SERVICE_TOKEN_HEADER);
            raw.addHeader(SpaceSecurityFilter.SERVICE_TOKEN_HEADER, "spoofed-token");
            return raw;
        });

        mvc.perform(request).andExpect(status().isUnauthorized());

        verify(service, never()).home();
    }

    @Test
    void v4DraftPageDataAndActionBindingsReachActualSpaceRoutes() throws Exception {
        mvc.perform(exact(
                        get("/v1/home"),
                        "route.spaces.work.home.page", "APP.SPACES:VIEW"))
                .andExpect(status().isOk());
        verify(service).home();

        var elevatedData = exact(
                get("/v1/spaces/company-square/content"),
                "route.spaces.work.content.data", "APP.SPACES:VIEW");
        elevatedData.with(raw -> {
            raw.removeHeader(SpaceProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER);
            raw.addHeader(SpaceProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER,
                    "ELEVATED");
            return raw;
        });
        mvc.perform(elevatedData)
                .andExpect(status().isOk());
        verify(service).content("company-square");

        mvc.perform(exact(
                        post("/v1/requests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createRequestBody()),
                        "route.spaces.work.request-create.action",
                        "ACTION.SPACE_REQUEST:CREATE"))
                .andExpect(status().isOk());
        verify(service).createRequest(any(SpaceDtos.CreateSpaceRequest.class), eq(null));

        assertThat(registry.bindingContracts())
                .allSatisfy(binding -> {
                    assertThat(binding.policyId()).isEqualTo("P-SPACES");
                    assertThat(binding.productId()).isEqualTo("spaces");
                    assertThat(binding.ownerService()).isEqualTo("dwp-space-server");
                    assertThat(binding.serviceKey()).isEqualTo("space");
                    assertThat(binding.publicPath()).startsWith("/api/spaces/v1/");
                    assertThat(binding.servicePath()).startsWith("/v1/");
                    assertThat(binding.resolvedAuthorities()).isNotEmpty();
                });
        assertThat(registry.bindingContracts()).hasSize(3);
        assertThat(registry.bindingContracts().stream()
                .map(SpaceProductSurfacePepRegistry.BindingContract::routeKind)
                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of("PAGE", "DATA", "ACTION"));
        assertThat(registry.bindingContracts())
                .extracting(
                        SpaceProductSurfacePepRegistry.BindingContract::routeContractKey,
                        SpaceProductSurfacePepRegistry.BindingContract::authorityType,
                        SpaceProductSurfacePepRegistry.BindingContract::authorityKey)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "route.spaces.work.home.page",
                                "POLICY", "spaces.work-access.v1"),
                        org.assertj.core.groups.Tuple.tuple(
                                "route.spaces.work.content.data",
                                "POLICY", "spaces.work-access.v1"),
                        org.assertj.core.groups.Tuple.tuple(
                                "route.spaces.work.request-create.action",
                                "CAPABILITY", "spaces.request.create"));
    }

    @Test
    void unmodeledSpaceSiblingPassesThroughWhileGovernedCandidateRejectsRouteDrift()
            throws Exception {
        mvc.perform(exact(
                        get("/v1/spaces"),
                        "route.spaces.work.unmodeled.page", "APP.SPACES:VIEW"))
                .andExpect(status().isOk());
        verify(service).spaces("MY", "", 50);

        var unknown = exact(
                get("/v1/home"),
                "route.spaces.work.unknown.page", "APP.SPACES:VIEW");
        mvc.perform(unknown).andExpect(status().isForbidden());

        var missing = exact(
                get("/v1/home"),
                "route.spaces.work.home.page", "APP.SPACES:VIEW");
        missing.with(raw -> {
            raw.removeHeader(SpaceProductSurfacePepFilter.ROUTE_CONTRACT_HEADER);
            return raw;
        });
        mvc.perform(missing).andExpect(status().isServiceUnavailable());

        verify(service, never()).home();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder exact(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String route,
            String permissions) {
        boolean action = route.endsWith(".action");
        request.header(SpaceSecurityFilter.SERVICE_TOKEN_HEADER, "space-token")
                .header(SpaceSecurityFilter.USER_HEADER, Long.toString(ACTOR))
                .header(SpaceSecurityFilter.TENANT_HEADER, Long.toString(TENANT))
                .header(SpaceSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER")
                .header(SpaceSecurityFilter.PERMISSIONS_HEADER, permissions)
                .header(SpaceProductSurfacePepFilter.ROLLOUT_STATE_HEADER, "110")
                .header(SpaceProductSurfacePepFilter.ROLLOUT_REVISION_HEADER,
                        ROLLOUT_REVISION)
                .header(SpaceProductSurfacePepFilter.ROLLOUT_COHORT_HEADER, "full")
                .header(SpaceProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER, "NORMAL")
                .header(SpaceProductSurfacePepFilter.ROUTE_CONTRACT_HEADER, route)
                .header(SpaceProductSurfacePepFilter.CURRENT_DECISION_REVISION_HEADER,
                        CURRENT_REVISION)
                .header(SpaceProductSurfacePepFilter.CURRENT_REVALIDATE_AT_HEADER,
                        "2099-01-01T00:00:00Z")
                .header(SpaceProductSurfacePepFilter.CONTEXT_HEADER,
                        "psc-" + "a".repeat(64))
                .header(SpaceProductSurfacePepFilter.SCOPE_HEADER,
                        ProductSurfaceScopeKey.key(
                                TENANT, ACTOR, "spaces", "spaces.work", "SELF", "SELF"));
        if (action) {
            request.header(SpaceProductSurfacePepFilter.EXPECTED_DECISION_REVISION_HEADER,
                    CURRENT_REVISION);
        }
        return request;
    }

    private String createRequestBody() {
        return """
                {
                  "templateId": "11111111-1111-1111-1111-111111111111",
                  "requestedKey": "platform-community",
                  "requestedName": "Platform Community",
                  "requestedSummary": "A governed collaboration space.",
                  "requestedVisibility": "REQUEST",
                  "justification": "Create a shared place for platform engineering work."
                }
                """;
    }
}
