package com.dwp.services.platform.servicecenter;

import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.platform.security.PlatformApprovalsPepRegistry;
import com.dwp.services.platform.security.PlatformCanaryPepRegistry;
import com.dwp.services.platform.security.PlatformSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
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
 * Owner-service evidence for the Employee Services Product Surface.
 *
 * <p>Every request executes the production Services owner PEP, the immutable Platform v1
 * compatibility PEP, and the real controller chain before it can reach the mocked owner service.</p>
 */
class ServicesProductSurfacePepEvidenceTest {

    private static final long TENANT_ID = 7L;
    private static final long ACTOR_ID = 101L;
    private static final UUID REQUEST_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String CURRENT_REVISION =
            "psr-" + "0123456789abcdef".repeat(4);
    private static final String ROLLOUT_REVISION =
            "rollout-" + "0123456789abcdef".repeat(4);
    private static final String CONTEXT_KEY = "psc-" + "b".repeat(64);
    private static final String HCM_SOURCE_SCOPE = ProductSurfaceScopeKey.key(
            TENANT_ID, ACTOR_ID, "hcm", "hcm.personal", "SELF", "SELF");
    private static final String HCM_DERIVED_SCOPE = HcmEligibilityScopeKey.derived(
            TENANT_ID,
            ACTOR_ID,
            "hcm.personal",
            HCM_SOURCE_SCOPE,
            "worker-revision-7",
            "self:101:worker-revision-7");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ServiceCenterService service = mock(ServiceCenterService.class);
    private final AtomicInteger downstreamInvocations = new AtomicInteger();
    private final List<DownstreamEvidence> downstreamEvidence =
            new CopyOnWriteArrayList<>();
    private ServicesProductSurfacePepFilter ownerPep;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service);
        downstreamInvocations.set(0);
        downstreamEvidence.clear();
        PepChainFixture fixture = ownerChain(true);
        ownerPep = fixture.ownerPep();
        mvc = fixture.mvc();
    }

    private PepChainFixture ownerChain(boolean enabled) {
        ServicesProductSurfacePepFilter ownerPep =
                new ServicesProductSurfacePepFilter(enabled, objectMapper);
        PlatformSecurityFilter platformSecurity = new PlatformSecurityFilter(
                "trusted",
                "runtime",
                false,
                objectMapper,
                new PlatformCanaryPepRegistry(objectMapper),
                new PlatformApprovalsPepRegistry(objectMapper));
        Filter downstreamProbe = (request, response, chain) -> {
            downstreamInvocations.incrementAndGet();
            HttpServletRequest http = (HttpServletRequest) request;
            String[] surfaceValues = http.getParameterValues("surface");
            downstreamEvidence.add(new DownstreamEvidence(
                    http.getHeader("X-DWP-Route-Contract-Key"),
                    http.getQueryString(),
                    http.getParameter("surface"),
                    surfaceValues == null ? List.of() : Arrays.asList(surfaceValues),
                    Set.copyOf(http.getParameterMap().keySet()),
                    Collections.list(http.getParameterNames()),
                    http.getParameter("status")));
            chain.doFilter(request, response);
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ServiceCenterController(service))
                .addFilters(ownerPep, downstreamProbe, platformSecurity)
                .build();
        return new PepChainFixture(ownerPep, mvc);
    }

    @Test
    void rejectsCrossTenantOpaqueScopeAtServicesOwnerServicePep() throws Exception {
        mvc.perform(page(scope(TENANT_ID + 1, ACTOR_ID, "services.work", "SELF", "SELF")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsCanonicalOpaqueScopeEscapeAtServicesOwnerServicePep() throws Exception {
        String escaped = scope(
                TENANT_ID,
                ACTOR_ID,
                "services.management",
                "RS_SERVICES",
                "RESOURCE_SET");

        mvc.perform(page(escaped))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsStaleAuthorityRevisionAtServicesOwnerServicePep() throws Exception {
        MockHttpServletRequestBuilder request = exact(
                post("/v1/services/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody()),
                ServicesProductSurfacePepFilter.REQUEST_CREATE_ACTION,
                scope());
        request.header("X-DWP-Expected-Decision-Revision", "psr-" + "f".repeat(64));

        mvc.perform(request)
                .andExpect(status().isConflict());

        verify(service, never()).createRequest(anyLong(), anyLong(), any(), any());
    }

    @Test
    void rejectsNormalAndSupportConfusedDeputyAtServicesOwnerServicePep()
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
    void rejectsInternalAuthorityHeaderSpoofAtServicesOwnerServicePep()
            throws Exception {
        MockHttpServletRequestBuilder request = page(scope());
        request.with(mockRequest -> replaceHeader(
                mockRequest, "X-DWP-Service-Token", "spoofed"));

        mvc.perform(request)
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
        assertThat(downstreamInvocations).hasValue(0);
    }

    @Test
    void rejectsMissingRouteContractHeaderForEveryServicesConsumerBeforeDownstream()
            throws Exception {
        assertRejectedRouteHeader(null);
    }

    @Test
    void rejectsUnknownRouteContractHeaderForEveryServicesConsumerBeforeDownstream()
            throws Exception {
        assertRejectedRouteHeader("route.services.work.unknown.page");
    }

    @Test
    void executesBothHcmPersonalServicesReadsThroughThePlatformOwnerChain()
            throws Exception {
        mvc.perform(hcmPage(
                        ServicesProductSurfacePepFilter.HCM_CATALOG_PAGE,
                        "APP.HCM:VIEW,APP.EMPLOYEE_SERVICES:VIEW"))
                .andExpect(status().isOk());

        mvc.perform(hcmPage(
                        ServicesProductSurfacePepFilter.HCM_REQUESTS_PAGE,
                        "APP.HRIS:VIEW,APP.EMPLOYEE_SERVICES:VIEW"))
                .andExpect(status().isOk());

        verify(service).catalog(anyLong(), any());
        verify(service).myRequests(anyLong(), anyLong(), any());
        assertThat(downstreamInvocations).hasValue(2);
    }

    @Test
    void rejectsThePreEligibilityAuthScopeAndMalformedHcmDerivedScopes()
            throws Exception {
        for (String scope : List.of(
                HCM_SOURCE_SCOPE,
                "hcm-scope-" + "A".repeat(40),
                "hcm-scope-" + "a".repeat(39),
                "hcm-scope-" + "a".repeat(41))) {
            MockHttpServletRequestBuilder request = hcmPage(
                    ServicesProductSurfacePepFilter.HCM_CATALOG_PAGE,
                    "APP.HCM:VIEW,APP.EMPLOYEE_SERVICES:VIEW");
            request.with(mockRequest -> replaceHeader(
                    mockRequest, "X-DWP-Context-Scope-Key", scope));

            mvc.perform(request).andExpect(status().isForbidden());
        }

        verifyNoInteractions(service);
        assertThat(downstreamInvocations).hasValue(0);
    }

    @Test
    void hcmCatalogBridgeRemovesSurfaceFromEveryDownstreamQueryView()
            throws Exception {
        mvc.perform(hcmPage(
                        ServicesProductSurfacePepFilter.HCM_CATALOG_PAGE,
                        "APP.HCM:VIEW,APP.EMPLOYEE_SERVICES:VIEW"))
                .andExpect(status().isOk());

        assertThat(downstreamEvidence).singleElement().satisfies(evidence -> {
            assertThat(evidence.routeContractKey())
                    .isEqualTo(ServicesProductSurfacePepFilter.HOME_PAGE_ROUTE);
            assertThat(evidence.queryString()).isNull();
            assertThat(evidence.surface()).isNull();
            assertThat(evidence.surfaceValues()).isEmpty();
            assertThat(evidence.parameterMapKeys()).doesNotContain("surface");
            assertThat(evidence.parameterNames()).doesNotContain("surface");
        });
        verify(service).catalog(TENANT_ID, null);
    }

    @Test
    void hcmRequestsBridgeRemovesSurfaceButPreservesStatusForTheController()
            throws Exception {
        MockHttpServletRequestBuilder request = exact(
                get("/v1/services/requests?surface=hcm&status=SUBMITTED"),
                ServicesProductSurfacePepFilter.HCM_REQUESTS_PAGE,
                HCM_DERIVED_SCOPE);
        request.with(mockRequest -> replaceHeader(
                mockRequest,
                "X-DWP-Permissions",
                "APP.HCM:VIEW,APP.EMPLOYEE_SERVICES:VIEW"));

        mvc.perform(request).andExpect(status().isOk());

        assertThat(downstreamEvidence).singleElement().satisfies(evidence -> {
            assertThat(evidence.routeContractKey())
                    .isEqualTo(ServicesProductSurfacePepFilter.HOME_PAGE_ROUTE);
            assertThat(evidence.queryString()).isEqualTo("status=SUBMITTED");
            assertThat(evidence.surface()).isNull();
            assertThat(evidence.surfaceValues()).isEmpty();
            assertThat(evidence.parameterMapKeys())
                    .containsExactly("status");
            assertThat(evidence.parameterNames())
                    .containsExactly("status");
            assertThat(evidence.status()).isEqualTo("SUBMITTED");
        });
        verify(service).myRequests(
                TENANT_ID, ACTOR_ID, ServiceCenterTypes.RequestStatus.SUBMITTED);
    }

    @Test
    void activeHcmV3ServicesRouteDoesNotDependOnTheServicesV4ReadinessFlag()
            throws Exception {
        MockMvc disabledV4 = ownerChain(false).mvc();

        disabledV4.perform(hcmPage(
                        ServicesProductSurfacePepFilter.HCM_CATALOG_PAGE,
                        "APP.HCM:VIEW,APP.EMPLOYEE_SERVICES:VIEW"))
                .andExpect(status().isOk());

        verify(service).catalog(anyLong(), any());
        assertThat(downstreamInvocations).hasValue(1);
    }

    @Test
    void hcmServicesBaselineDoesNotRequireExactRouteEvidenceBeforeEnforcement()
            throws Exception {
        MockMvc disabledV4 = ownerChain(false).mvc();
        MockHttpServletRequestBuilder baseline = hcmPage(
                ServicesProductSurfacePepFilter.HCM_CATALOG_PAGE,
                "APP.HCM:VIEW,APP.EMPLOYEE_SERVICES:VIEW");
        baseline.with(request -> {
            request.removeHeader("X-DWP-Route-Contract-Key");
            replaceHeader(request, "X-DWP-Rollout-State", "100");
            return request;
        });

        disabledV4.perform(baseline)
                .andExpect(status().isOk());

        verify(service).catalog(anyLong(), any());
        assertThat(downstreamInvocations).hasValue(1);
    }

    @Test
    void hcmPersonalServicesRequiresBothProductAndEmployeeServiceEntitlements()
            throws Exception {
        for (String permissions : List.of(
                "APP.EMPLOYEE_SERVICES:VIEW",
                "APP.HCM:VIEW")) {
            mvc.perform(hcmPage(
                            ServicesProductSurfacePepFilter.HCM_CATALOG_PAGE,
                            permissions))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(service);
        assertThat(downstreamInvocations).hasValue(0);
    }

    @Test
    void executesPageDataAndActionConsumersThroughServicesOwnerChain()
            throws Exception {
        mvc.perform(page(scope()))
                .andExpect(status().isOk());

        mvc.perform(exact(
                        get("/v1/services/requests/{requestId}", REQUEST_ID)
                                .param("view", "detail"),
                        ServicesProductSurfacePepFilter.REQUEST_DETAIL_DATA,
                        scope()))
                .andExpect(status().isOk());

        mvc.perform(exact(
                        post("/v1/services/requests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createRequestBody()),
                        ServicesProductSurfacePepFilter.REQUEST_CREATE_ACTION,
                        scope())
                        .header("X-DWP-Expected-Decision-Revision", CURRENT_REVISION))
                .andExpect(status().isOk());

        verify(service).catalog(anyLong(), any());
        verify(service).myRequest(anyLong(), anyLong(), any());
        verify(service).createRequest(anyLong(), anyLong(), any(), any());

        assertThat(ownerPep.bindingContracts())
                .extracting(
                        ServicesProductSurfacePepFilter.Binding::routeContractKey,
                        ServicesProductSurfacePepFilter.Binding::routeKind,
                        ServicesProductSurfacePepFilter.Binding::method,
                        ServicesProductSurfacePepFilter.Binding::gatewayPath,
                        ServicesProductSurfacePepFilter.Binding::servicePath,
                        ServicesProductSurfacePepFilter.Binding::fixedQuery)
                .containsExactlyInAnyOrder(
                        tuple(
                                "route.services.work.home.page",
                                ServicesProductSurfacePepFilter.RouteKind.PAGE,
                                "GET",
                                "/api/platform/v1/services/catalog",
                                "/v1/services/catalog",
                                Map.of()),
                        tuple(
                                "route.services.work.request-detail.data",
                                ServicesProductSurfacePepFilter.RouteKind.DATA,
                                "GET",
                                "/api/platform/v1/services/requests/{requestId}",
                                "/v1/services/requests/{requestId}",
                                Map.of("view", "detail")),
                        tuple(
                                "route.services.work.request-create.action",
                                ServicesProductSurfacePepFilter.RouteKind.ACTION,
                                "POST",
                                "/api/platform/v1/services/requests",
                                "/v1/services/requests",
                                Map.of()),
                        tuple(
                                "route.hcm.personal.services.page",
                                ServicesProductSurfacePepFilter.RouteKind.PAGE,
                                "GET",
                                "/api/platform/v1/services/catalog",
                                "/v1/services/catalog",
                                Map.of("surface", "hcm")),
                        tuple(
                                "route.hcm.personal.services.page",
                                ServicesProductSurfacePepFilter.RouteKind.PAGE,
                                "GET",
                                "/api/platform/v1/services/requests",
                                "/v1/services/requests",
                                Map.of("surface", "hcm")));
    }

    private MockHttpServletRequestBuilder page(String scope) {
        return exact(
                get("/v1/services/catalog"),
                ServicesProductSurfacePepFilter.HOME_PAGE,
                scope);
    }

    private MockHttpServletRequestBuilder hcmPage(
            ServicesProductSurfacePepFilter.Binding binding,
            String permissions) {
        MockHttpServletRequestBuilder request = exact(
                get(binding.servicePath()).param("surface", "hcm"),
                binding,
                HCM_DERIVED_SCOPE);
        return request.with(mockRequest -> replaceHeader(
                mockRequest, "X-DWP-Permissions", permissions));
    }

    private MockHttpServletRequestBuilder exact(
            MockHttpServletRequestBuilder request,
            ServicesProductSurfacePepFilter.Binding binding,
            String scope) {
        return request
                .header("X-DWP-Service-Token", "trusted")
                .header("X-DWP-User-ID", Long.toString(ACTOR_ID))
                .header("X-DWP-Tenant-ID", Long.toString(TENANT_ID))
                .header("X-DWP-Roles", "WORKSPACE_MEMBER")
                .header("X-DWP-Permissions", "APP.EMPLOYEE_SERVICES:VIEW")
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
        return scope(TENANT_ID, ACTOR_ID, "services.work", "SELF", "SELF");
    }

    private String scope(
            long tenantId,
            long actorId,
            String surfaceKey,
            String source,
            String kind) {
        return ProductSurfaceScopeKey.key(
                tenantId, actorId, "services", surfaceKey, source, kind);
    }

    private String createRequestBody() {
        return """
                {
                  "serviceKey": "it.support",
                  "summary": "Owner PEP evidence request",
                  "values": {},
                  "idempotencyKey": "11111111-1111-1111-1111-111111111111",
                  "submit": true
                }
                """;
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
                        get("/v1/services/requests/{requestId}", REQUEST_ID)
                                .param("view", "detail"),
                        ServicesProductSurfacePepFilter.REQUEST_DETAIL_DATA,
                        scope()),
                exact(
                        post("/v1/services/requests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createRequestBody()),
                        ServicesProductSurfacePepFilter.REQUEST_CREATE_ACTION,
                        scope()),
                hcmPage(
                        ServicesProductSurfacePepFilter.HCM_CATALOG_PAGE,
                        "APP.HCM:VIEW,APP.EMPLOYEE_SERVICES:VIEW"),
                hcmPage(
                        ServicesProductSurfacePepFilter.HCM_REQUESTS_PAGE,
                        "APP.HCM:VIEW,APP.EMPLOYEE_SERVICES:VIEW"));
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

    private record DownstreamEvidence(
            String routeContractKey,
            String queryString,
            String surface,
            List<String> surfaceValues,
            Set<String> parameterMapKeys,
            List<String> parameterNames,
            String status) {
    }

    private record PepChainFixture(
            ServicesProductSurfacePepFilter ownerPep,
            MockMvc mvc) {
    }

}
