package com.dwp.services.platform.communication;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.platform.security.PlatformApprovalsPepRegistry;
import com.dwp.services.platform.security.PlatformCanaryPepRegistry;
import com.dwp.services.platform.security.PlatformSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Owner-service evidence for the Communications Product Surface.
 *
 * <p>Every request executes the production Communications owner PEP, the immutable Platform v1
 * compatibility PEP, and the real controller chain before it can reach the mocked owner service.</p>
 */
class CommunicationProductSurfacePepEvidenceTest {

    private static final long TENANT_ID = 7L;
    private static final long ACTOR_ID = 101L;
    private static final String CURRENT_REVISION =
            "psr-" + "0123456789abcdef".repeat(4);
    private static final String ROLLOUT_REVISION =
            "rollout-" + "0123456789abcdef".repeat(4);
    private static final String CONTEXT_KEY = "psc-" + "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final CommunicationService service = mock(CommunicationService.class);
    private final CommunicationProductSurfacePepFilter ownerPep =
            new CommunicationProductSurfacePepFilter(true, objectMapper);
    private final AtomicInteger downstreamInvocations = new AtomicInteger();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service);
        downstreamInvocations.set(0);
        PlatformSecurityFilter platformSecurity = new PlatformSecurityFilter(
                "trusted",
                "runtime",
                false,
                objectMapper,
                new PlatformCanaryPepRegistry(objectMapper),
                new PlatformApprovalsPepRegistry(objectMapper));
        Filter downstreamProbe = (request, response, chain) -> {
            downstreamInvocations.incrementAndGet();
            chain.doFilter(request, response);
        };
        mvc = MockMvcBuilders.standaloneSetup(new CommunicationController(service))
                .addFilters(ownerPep, downstreamProbe, platformSecurity)
                .build();
    }

    @Test
    void rejectsCrossTenantOpaqueScopeAtCommunicationsOwnerServicePep() throws Exception {
        mvc.perform(page(scope(TENANT_ID + 1, ACTOR_ID, "communications.work", "SELF", "SELF")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsCanonicalOpaqueScopeEscapeAtCommunicationsOwnerServicePep() throws Exception {
        String escaped = scope(
                TENANT_ID,
                ACTOR_ID,
                "communications.management",
                "RS_COMMUNICATIONS",
                "RESOURCE_SET");

        mvc.perform(page(escaped))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsStaleAuthorityRevisionAtCommunicationsOwnerServicePep() throws Exception {
        MockHttpServletRequestBuilder request = exact(
                post("/v1/communications/91/acknowledgement"),
                CommunicationProductSurfacePepFilter.ACKNOWLEDGEMENT_ACTION,
                scope());
        request.header("X-DWP-Expected-Decision-Revision", "psr-" + "f".repeat(64));

        mvc.perform(request)
                .andExpect(status().isConflict());

        verify(service, never()).acknowledge(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void rejectsNormalAndSupportConfusedDeputyAtCommunicationsOwnerServicePep()
            throws Exception {
        MockHttpServletRequestBuilder providerBorrowingNormal = page(scope());
        providerBorrowingNormal.with(request -> replaceHeader(
                request, "X-DWP-Roles", "PROVIDER_SUPPORT"));

        mvc.perform(providerBorrowingNormal)
                .andExpect(status().isForbidden());

        MockHttpServletRequestBuilder normalBorrowingSupport = page(scope());
        normalBorrowingSupport.with(request -> replaceHeader(
                request, "X-DWP-Active-Access-Mode", "PROVIDER_SUPPORT"));

        mvc.perform(normalBorrowingSupport)
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsInternalAuthorityHeaderSpoofAtCommunicationsOwnerServicePep()
            throws Exception {
        MockHttpServletRequestBuilder request = page(scope());
        request.with(mockRequest -> replaceHeader(
                mockRequest, "X-DWP-Service-Token", "spoofed"));

        mvc.perform(request)
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsMissingRouteContractHeaderForEveryCommunicationsConsumerBeforeDownstream()
            throws Exception {
        assertRejectedRouteHeader(null);
    }

    @Test
    void rejectsUnknownRouteContractHeaderForEveryCommunicationsConsumerBeforeDownstream()
            throws Exception {
        assertRejectedRouteHeader("route.communications.work.unknown.page");
    }

    @Test
    void executesPageDataAndActionConsumersThroughCommunicationsOwnerChain()
            throws Exception {
        mvc.perform(page(scope()))
                .andExpect(status().isOk());

        mvc.perform(exact(
                        get("/v1/communications/91").param("view", "detail"),
                        CommunicationProductSurfacePepFilter.STORY_DETAIL_DATA,
                        scope()))
                .andExpect(status().isOk());

        mvc.perform(exact(
                        post("/v1/communications/91/acknowledgement"),
                        CommunicationProductSurfacePepFilter.ACKNOWLEDGEMENT_ACTION,
                        scope())
                        .header("X-DWP-Expected-Decision-Revision", CURRENT_REVISION))
                .andExpect(status().isOk());

        verify(service).feed(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), anyInt());
        verify(service).detail(anyLong(), anyLong(), any(), any(), anyLong());
        verify(service).acknowledge(anyLong(), anyLong(), any(), anyLong());

        assertThat(ownerPep.bindingContracts())
                .extracting(
                        CommunicationProductSurfacePepFilter.Binding::routeContractKey,
                        CommunicationProductSurfacePepFilter.Binding::routeKind,
                        CommunicationProductSurfacePepFilter.Binding::method,
                        CommunicationProductSurfacePepFilter.Binding::gatewayPath,
                        CommunicationProductSurfacePepFilter.Binding::servicePath,
                        CommunicationProductSurfacePepFilter.Binding::fixedQuery)
                .containsExactlyInAnyOrder(
                        tuple(
                                "route.communications.work.home.page",
                                CommunicationProductSurfacePepFilter.RouteKind.PAGE,
                                "GET",
                                "/api/platform/v1/communications",
                                "/v1/communications",
                                Map.of()),
                        tuple(
                                "route.communications.work.story-detail.data",
                                CommunicationProductSurfacePepFilter.RouteKind.DATA,
                                "GET",
                                "/api/platform/v1/communications/{communicationId}",
                                "/v1/communications/{communicationId}",
                                Map.of("view", "detail")),
                        tuple(
                                "route.communications.work.acknowledgement.action",
                                CommunicationProductSurfacePepFilter.RouteKind.ACTION,
                                "POST",
                                "/api/platform/v1/communications/{communicationId}/acknowledgement",
                                "/v1/communications/{communicationId}/acknowledgement",
                                Map.of()));
    }

    private MockHttpServletRequestBuilder page(String scope) {
        return exact(
                get("/v1/communications"),
                CommunicationProductSurfacePepFilter.HOME_PAGE,
                scope);
    }

    private MockHttpServletRequestBuilder exact(
            MockHttpServletRequestBuilder request,
            CommunicationProductSurfacePepFilter.Binding binding,
            String scope) {
        return request
                .header("X-DWP-Service-Token", "trusted")
                .header("X-DWP-User-ID", Long.toString(ACTOR_ID))
                .header("X-DWP-Tenant-ID", Long.toString(TENANT_ID))
                .header("X-DWP-Roles", "WORKSPACE_MEMBER")
                .header("X-DWP-Permissions", "APP.COMMUNICATIONS:VIEW")
                .header("X-DWP-Rollout-State", "110")
                .header("X-DWP-Rollout-Revision", ROLLOUT_REVISION)
                .header("X-DWP-Rollout-Cohort", "full")
                .header("X-DWP-Route-Contract-Key", binding.routeContractKey())
                .header("X-DWP-Active-Access-Mode", "NORMAL")
                .header("X-DWP-Current-Decision-Revision", CURRENT_REVISION)
                .header("X-DWP-Current-Revalidate-At", "2099-01-01T00:00:00Z")
                .header("X-DWP-Context-Key", CONTEXT_KEY)
                .header("X-DWP-Context-Scope-Key", scope);
    }

    private String scope() {
        return scope(TENANT_ID, ACTOR_ID, "communications.work", "SELF", "SELF");
    }

    private String scope(
            long tenantId,
            long actorId,
            String surfaceKey,
            String source,
            String kind) {
        return ProductSurfaceScopeKey.key(
                tenantId, actorId, "communications", surfaceKey, source, kind);
    }

    private MockHttpServletRequest replaceHeader(
            MockHttpServletRequest request, String name, String value) {
        request.removeHeader(name);
        request.addHeader(name, value);
        return request;
    }

    private void assertRejectedRouteHeader(String replacement) throws Exception {
        List<MockHttpServletRequestBuilder> candidates = List.of(
                page(scope()),
                exact(
                        get("/v1/communications/91").param("view", "detail"),
                        CommunicationProductSurfacePepFilter.STORY_DETAIL_DATA,
                        scope()),
                exact(
                        post("/v1/communications/91/acknowledgement"),
                        CommunicationProductSurfacePepFilter.ACKNOWLEDGEMENT_ACTION,
                        scope()));
        for (MockHttpServletRequestBuilder candidate : candidates) {
            candidate.with(request -> {
                request.removeHeader("X-DWP-Route-Contract-Key");
                if (replacement != null) {
                    request.addHeader("X-DWP-Route-Contract-Key", replacement);
                }
                return request;
            });
            mvc.perform(candidate)
                    .andExpect(status().isForbidden());
        }

        assertThat(downstreamInvocations.get()).isZero();
        verifyNoInteractions(service);
    }

}
