package com.dwp.services.platform.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.platform.workplace.WorkplaceController;
import com.dwp.services.platform.workplace.WorkplaceDtos;
import com.dwp.services.platform.workplace.WorkplaceOperationsController;
import com.dwp.services.platform.workplace.WorkplaceOperationsService;
import com.dwp.services.platform.workplace.WorkplaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkplaceProductSurfacePepContractTest {

    private static final long TENANT = 7L;
    private static final long ACTOR = 101L;
    private static final UUID RESOURCE =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FLOOR =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CURRENT_REVISION =
            "psr-" + "0123456789abcdef".repeat(4);
    private static final String ROLLOUT_REVISION =
            "rollout-" + "0123456789abcdef".repeat(4);
    private static final String CONTEXT = "psc-" + "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final WorkplaceService service = mock(WorkplaceService.class);
    private final WorkplaceOperationsService operations =
            mock(WorkplaceOperationsService.class);
    private final PlatformWorkplaceProductPepRegistry registry =
            new PlatformWorkplaceProductPepRegistry(objectMapper);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service, operations);
        PlatformSecurityFilter platformSecurity = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);
        PlatformWorkplaceProductPepFilter workplacePep =
                new PlatformWorkplaceProductPepFilter(true, registry, objectMapper);
        mvc = MockMvcBuilders.standaloneSetup(
                        new WorkplaceController(service),
                        new WorkplaceOperationsController(operations))
                .addFilters(platformSecurity, workplacePep)
                .build();
    }

    @Test
    void crossTenantOpaqueScopeFailsClosedAtWorkplaceOwnerPep() throws Exception {
        MockHttpServletRequestBuilder request = exactExplore();
        replaceHeader(request, PlatformSecurityFilter.SCOPE_HEADER,
                ProductSurfaceScopeKey.key(
                        TENANT + 1, ACTOR, "workplace", "workplace.work",
                        "SELF", "SELF"));

        mvc.perform(request).andExpect(status().isForbidden());

        verifyNoInteractions(service, operations);
    }

    @Test
    void canonicalOpaqueScopeEscapeFailsClosedAtWorkplaceOwnerPep() throws Exception {
        MockHttpServletRequestBuilder request = exactExplore();
        replaceHeader(request, PlatformSecurityFilter.SCOPE_HEADER,
                ProductSurfaceScopeKey.key(
                        TENANT, ACTOR, "workplace", "workplace.work",
                        "APP_WORKPLACE", "RESOURCE_SET"));

        mvc.perform(request).andExpect(status().isForbidden());

        verifyNoInteractions(service, operations);
    }

    @Test
    void staleAuthorityRevisionFailsClosedBeforeWorkplaceAction() throws Exception {
        MockHttpServletRequestBuilder request = exactCreateBooking();
        replaceHeader(request, PlatformSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                "psr-" + "f".repeat(64));

        mvc.perform(request).andExpect(status().isConflict());

        verify(operations, never()).createBooking(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void normalAndSupportModesCannotConfuseWorkplaceOwnerPep() throws Exception {
        MockHttpServletRequestBuilder providerAsNormal = exactExplore();
        replaceHeader(providerAsNormal, PlatformSecurityFilter.ROLES_HEADER,
                "PROVIDER_SUPPORT");
        mvc.perform(providerAsNormal).andExpect(status().isForbidden());

        MockHttpServletRequestBuilder supportAsNormal = exactExplore()
                .header(PlatformSecurityFilter.SUPPORT_SESSION_HEADER, "support-1")
                .header(PlatformSecurityFilter.SUPPORT_SCOPES_HEADER,
                        "TENANT_CONFIGURATION_READ")
                .header(PlatformSecurityFilter.ACTOR_TENANT_HEADER, "3");
        mvc.perform(supportAsNormal).andExpect(status().isForbidden());

        MockHttpServletRequestBuilder normalAsSupport = exactExplore();
        replaceHeader(normalAsSupport,
                PlatformWorkplaceProductPepFilter.ACTIVE_ACCESS_MODE_HEADER,
                "PROVIDER_SUPPORT");
        mvc.perform(normalAsSupport).andExpect(status().isForbidden());

        verifyNoInteractions(service, operations);
    }

    @Test
    void spoofedInternalAuthorityHeadersCannotBypassPlatformServiceIdentity()
            throws Exception {
        MockHttpServletRequestBuilder request = exactExplore();
        replaceHeader(request, PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "spoofed");

        mvc.perform(request).andExpect(status().isUnauthorized());

        verifyNoInteractions(service, operations);
    }

    @Test
    void v4DraftPageDataAndActionBindingsReachActualWorkplaceRoutes()
            throws Exception {
        mvc.perform(exactExplore())
                .andExpect(status().isOk())
                .andExpect(header().string(
                        PlatformSecurityFilter.RESPONSE_DECISION_REVISION_HEADER,
                        CURRENT_REVISION));
        verify(service).explore(
                eq(TENANT), eq(ACTOR), eq(null), eq(null),
                any(), any(), eq(null), eq(null));

        when(service.floorBackground(TENANT, ACTOR, null, FLOOR)).thenReturn(
                new WorkplaceService.FloorBackground(
                        new ByteArrayResource(new byte[] {1}),
                        "image/png", 1L, "floor-v1"));
        MockHttpServletRequestBuilder elevatedData = exact(
                get("/v1/workplace/floors/{floorId}/background", FLOOR),
                "route.workplace.work.floor-background.data",
                "APP.WORKPLACE:VIEW");
        replaceHeader(elevatedData,
                PlatformWorkplaceProductPepFilter.ACTIVE_ACCESS_MODE_HEADER,
                "ELEVATED");
        mvc.perform(elevatedData)
                .andExpect(status().isOk());
        verify(service).floorBackground(TENANT, ACTOR, null, FLOOR);

        mvc.perform(exactCreateBooking()).andExpect(status().isOk());
        verify(operations).createBooking(
                eq(TENANT), eq(ACTOR), eq(null), eq(null), eq(null), eq(null),
                eq("workplace-contract-test"), eq(null),
                any(WorkplaceDtos.BookingRequest.class));

        assertThat(registry.bindingContracts())
                .allSatisfy(binding -> {
                    assertThat(binding.policyId()).isEqualTo("P-WORKPLACE");
                    assertThat(binding.productId()).isEqualTo("workplace");
                    assertThat(binding.ownerService()).isEqualTo("dwp-platform-server");
                    assertThat(binding.serviceKey()).isEqualTo("platform");
                    assertThat(binding.publicPath()).startsWith("/api/platform/v1/");
                    assertThat(binding.servicePath()).startsWith("/v1/");
                    assertThat(binding.resolvedAuthorities()).isNotEmpty();
                });
        assertThat(registry.bindingContracts()).hasSize(3);
        assertThat(registry.bindingContracts())
                .extracting(
                        PlatformWorkplaceProductPepRegistry.BindingContract::routeContractKey,
                        PlatformWorkplaceProductPepRegistry.BindingContract::routeKind,
                        PlatformWorkplaceProductPepRegistry.BindingContract::method,
                        PlatformWorkplaceProductPepRegistry.BindingContract::publicPath,
                        PlatformWorkplaceProductPepRegistry.BindingContract::servicePath)
                .contains(
                        tuple(
                                "route.workplace.work.explore.page", "PAGE", "GET",
                                "/api/platform/v1/workplace/explore",
                                "/v1/workplace/explore"),
                        tuple(
                                "route.workplace.work.floor-background.data", "DATA", "GET",
                                "/api/platform/v1/workplace/floors/{floorId}/background",
                                "/v1/workplace/floors/{floorId}/background"),
                        tuple(
                                "route.workplace.work.booking-create.action", "ACTION", "POST",
                                "/api/platform/v1/workplace/bookings",
                                "/v1/workplace/bookings"));
        assertThat(registry.bindingContracts())
                .extracting(
                        PlatformWorkplaceProductPepRegistry.BindingContract::routeContractKey,
                        PlatformWorkplaceProductPepRegistry.BindingContract::authorityType,
                        PlatformWorkplaceProductPepRegistry.BindingContract::authorityKey)
                .containsExactlyInAnyOrder(
                        tuple("route.workplace.work.explore.page",
                                "POLICY", "workplace.work-access.v1"),
                        tuple("route.workplace.work.floor-background.data",
                                "POLICY", "workplace.work-access.v1"),
                        tuple("route.workplace.work.booking-create.action",
                                "CAPABILITY", "workplace.space.create"));
        assertThat(registry.bindingContracts().stream()
                .map(PlatformWorkplaceProductPepRegistry.BindingContract::routeKind)
                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of("PAGE", "DATA", "ACTION"));
    }

    @Test
    void unmodeledWorkplaceSiblingPassesThroughWhileGovernedCandidateRejectsRouteDrift()
            throws Exception {
        mvc.perform(exact(
                        get("/v1/workplace/bookings")
                                .param("from", "2026-08-28T09:00:00+09:00")
                                .param("to", "2026-08-28T18:00:00+09:00"),
                        "route.workplace.work.unmodeled.data",
                        "APP.WORKPLACE:VIEW"))
                .andExpect(status().isOk());
        verify(service).myBookings(
                eq(TENANT), eq(ACTOR), any(), any(), eq(null), eq(null));

        MockHttpServletRequestBuilder unknown = exactExplore();
        replaceHeader(unknown, PlatformSecurityFilter.ROUTE_CONTRACT_HEADER,
                "route.workplace.work.unknown.page");
        mvc.perform(unknown).andExpect(status().isForbidden());

        MockHttpServletRequestBuilder missing = exactExplore();
        missing.with(raw -> {
            raw.removeHeader(PlatformSecurityFilter.ROUTE_CONTRACT_HEADER);
            return raw;
        });
        mvc.perform(missing).andExpect(status().isForbidden());

        verify(service, never()).explore(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(operations);
    }

    private MockHttpServletRequestBuilder exactExplore() {
        return exact(
                get("/v1/workplace/explore")
                        .param("from", "2026-08-28T09:00:00+09:00")
                        .param("to", "2026-08-28T10:00:00+09:00"),
                "route.workplace.work.explore.page",
                "APP.WORKPLACE:VIEW");
    }

    private MockHttpServletRequestBuilder exactCreateBooking() {
        return exact(
                post("/v1/workplace/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "workplace-contract-test")
                        .content("""
                                {
                                  "resourceId": "%s",
                                  "startsAt": "2026-08-29T09:00:00+09:00",
                                  "endsAt": "2026-08-29T10:00:00+09:00",
                                  "purpose": "Contract verification",
                                  "visibleToColleagues": false
                                }
                                """.formatted(RESOURCE)),
                "route.workplace.work.booking-create.action",
                "APP.WORKPLACE:CREATE");
    }

    private MockHttpServletRequestBuilder exact(
            MockHttpServletRequestBuilder request,
            String route,
            String permissions) {
        request.header(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted")
                .header(PlatformSecurityFilter.USER_HEADER, Long.toString(ACTOR))
                .header(PlatformSecurityFilter.TENANT_HEADER, Long.toString(TENANT))
                .header(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER")
                .header(PlatformSecurityFilter.PERMISSIONS_HEADER, permissions)
                .header(PlatformSecurityFilter.ROLLOUT_STATE_HEADER, "110")
                .header(PlatformSecurityFilter.ROLLOUT_REVISION_HEADER, ROLLOUT_REVISION)
                .header(PlatformSecurityFilter.ROLLOUT_COHORT_HEADER, "full")
                .header(PlatformWorkplaceProductPepFilter.ACTIVE_ACCESS_MODE_HEADER, "NORMAL")
                .header(PlatformSecurityFilter.ROUTE_CONTRACT_HEADER, route)
                .header(PlatformSecurityFilter.CURRENT_DECISION_REVISION_HEADER,
                        CURRENT_REVISION)
                .header(PlatformSecurityFilter.CURRENT_REVALIDATE_AT_HEADER,
                        "2099-01-01T00:00:00Z")
                .header(PlatformSecurityFilter.CONTEXT_HEADER, CONTEXT)
                .header(PlatformSecurityFilter.SCOPE_HEADER,
                        ProductSurfaceScopeKey.key(
                                TENANT, ACTOR, "workplace", "workplace.work",
                                "SELF", "SELF"));
        if (route.endsWith(".action")) {
            request.header(PlatformSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                    CURRENT_REVISION);
        }
        return request;
    }

    private void replaceHeader(
            MockHttpServletRequestBuilder request,
            String name,
            String value) {
        request.with(raw -> {
            raw.removeHeader(name);
            raw.addHeader(name, value);
            return raw;
        });
    }
}
