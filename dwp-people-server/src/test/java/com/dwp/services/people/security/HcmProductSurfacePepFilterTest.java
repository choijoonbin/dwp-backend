package com.dwp.services.people.security;

import com.dwp.services.people.hr.HrController;
import com.dwp.services.people.hr.HrService;
import com.dwp.services.people.workforce.WorkforceCandidateController;
import com.dwp.services.people.workforce.WorkforceCandidateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HcmProductSurfacePepFilterTest {

    private static final String ROLLOUT_REVISION = "rollout-" + "a".repeat(64);
    private static final String DECISION_REVISION = "psr-" + "b".repeat(64);
    private static final String CONTEXT_KEY = "psc-" + "d".repeat(64);

    private final HrService service = mock(HrService.class);
    private final WorkforceCandidateService candidates = mock(WorkforceCandidateService.class);
    private final HcmScopeSelectionValidator validator = mock(HcmScopeSelectionValidator.class);
    private final Runnable organizationGraphService = mock(Runnable.class);
    private final Runnable organizationGraphRepository = mock(Runnable.class);
    private final Runnable organizationGraphOutbox = mock(Runnable.class);
    private final AtomicInteger organizationGraphControllerCalls = new AtomicInteger();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service, candidates, validator, organizationGraphService,
                organizationGraphRepository, organizationGraphOutbox);
        organizationGraphControllerCalls.set(0);
        mvc = mvcWithOrganizationFeature(true, "3");
    }

    private MockMvc mvcWithOrganizationFeature(
            boolean organizationPositionsEnabled,
            String enrolledTenants) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HcmV3PepRegistry registry = new HcmV3PepRegistry(mapper);
        return MockMvcBuilders.standaloneSetup(
                        new HrController(service), new WorkforceCandidateController(candidates),
                        new OrganizationGraphProbeController(
                                organizationGraphControllerCalls, organizationGraphService,
                                organizationGraphRepository, organizationGraphOutbox))
                .addFilters(
                        new PeopleSecurityFilter("people-token", mapper),
                        new HcmProductSurfacePepFilter(
                                true, registry, validator, mapper,
                                organizationPositionsEnabled, enrolledTenants))
                .build();
    }

    @Test
    void springMatrixPathMatchesControllerInBaselineButExactPepDeniesIt() throws Exception {
        mvc.perform(identity(get("/v1/hr/home;x=y"))
                        .header(HcmProductSurfacePepFilter.ROLLOUT_STATE_HEADER, "100")
                        .header(HcmProductSurfacePepFilter.ROLLOUT_REVISION_HEADER,
                                ROLLOUT_REVISION)
                        .header(HcmProductSurfacePepFilter.ROLLOUT_COHORT_HEADER, "full"))
                .andExpect(status().isOk());
        verify(service).home();

        reset(service);
        mvc.perform(exact(get("/v1/hr/home;x=y"),
                        "route.hcm.personal.home.page"))
                .andExpect(status().isForbidden());
        verify(service, never()).home();
        verify(validator, never()).validate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void encodedPrefixRepeatedSlashAndDotSegmentsNeverReachTheController() throws Exception {
        for (String path : java.util.List.of(
                "/%76%31/%68%72/home",
                "/v1/hr//home",
                "/v1/hr/./home",
                "/v1/hr/team/../home")) {
            reset(service);
            mvc.perform(exact(get(URI.create(path)), "route.hcm.personal.home.page"))
                    .andExpect(status().isForbidden());
            verify(service, never()).home();
        }
    }

    @Test
    void wrongMethodOnAClaimedHcmPathIsDeniedBeforeSpring405() throws Exception {
        mvc.perform(exact(delete("/v1/hr/team"), "route.hcm.team.home.page"))
                .andExpect(status().isForbidden());
        verify(service, never()).team();
    }

    @Test
    void staleAuthorityRevisionFailsClosedBeforeTheHcmMutation() throws Exception {
        String cardId = "11111111-1111-1111-1111-111111111111";
        var request = exact(
                post("/v1/hr/time/" + cardId + "/submit").queryParam("version", "1"),
                "route.hcm.personal.time-submit.action");
        request.header(HcmProductSurfacePepFilter.EXPECTED_DECISION_REVISION_HEADER,
                "psr-" + "c".repeat(64));

        mvc.perform(request)
                .andExpect(status().isConflict());
        verify(service, never()).submitTimeCard(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void nonAllowlistedQueryOnClaimedWorkforcePathIsDeniedBeforeControllerMapping()
            throws Exception {
        mvc.perform(exact(get("/v1/workforce/people").queryParam("view", "other"),
                        "route.hcm.operations.people.page")
                        .header(PeopleSecurityFilter.PERMISSIONS_HEADER,
                                "DATA.WORKFORCE:VIEW"))
                .andExpect(status().isForbidden());
    }

    @Test
    void organizationCandidatesRequireExactCapabilityAndResourceResponsibility()
            throws Exception {
        mvc.perform(exact(
                        get("/v1/workforce/organization/candidates"),
                        "route.hcm.management.org-design.page",
                        "ACTION.WORKFORCE_ORG_DESIGN:VIEW", null))
                .andExpect(status().isForbidden());
        verify(candidates, never()).list();

        mvc.perform(exact(
                        get("/v1/workforce/organization/candidates"),
                        "route.hcm.management.org-design.page",
                        "ACTION.WORKFORCE_ORG_DESIGN:VIEW",
                        "APP_CONFIG_ADMIN@RS_HCM_CONFIG"))
                .andExpect(status().isOk());
        verify(candidates).list();
    }

    @Test
    void ownerResolverOutageReturns503AndNeverReachesController() throws Exception {
        when(service.team()).thenThrow(new AssertionError("controller must not run"));
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(validator).validate(org.mockito.ArgumentMatchers.any());

        mvc.perform(exact(get("/v1/hr/team"), "route.hcm.team.home.page")
                        .header(PeopleSecurityFilter.PERMISSIONS_HEADER, "APP.HCM:VIEW"))
                .andExpect(status().isServiceUnavailable());
        verify(service, never()).team();
    }

    @Test
    void legacyHrisAliasPassesTheExactHcmOwnerPep() throws Exception {
        mvc.perform(exact(
                        get("/v1/hr/home"),
                        "route.hcm.personal.home.page",
                        "APP.HRIS:VIEW",
                        null))
                .andExpect(status().isOk());

        verify(service).home();
    }

    @Test
    void preservesTheTrustedActiveAccessModeForOwnerRevalidation() throws Exception {
        AtomicReference<String> observed = new AtomicReference<>();
        doAnswer(invocation -> {
            observed.set(HcmPepContext.require().activeAccessMode());
            return null;
        }).when(validator).validate(org.mockito.ArgumentMatchers.any());

        mvc.perform(exact(
                        get("/v1/hr/home"),
                        "route.hcm.personal.home.page",
                        "APP.HCM:VIEW", null, "ELEVATED"))
                .andExpect(status().isOk());

        assertThat(observed.get()).isEqualTo("ELEVATED");
        assertThat(HcmPepContext.current()).isNull();
    }

    @Test
    void organizationPageAndDataUseTheirExactTrustedRouteOnTheSharedGetPath()
            throws Exception {
        List<String> observedRoutes = new ArrayList<>();
        doAnswer(invocation -> {
            HcmV3PepRegistry.RouteAuthority authority = invocation.getArgument(0);
            observedRoutes.add(authority.routeContractKey());
            return null;
        }).when(validator).validate(org.mockito.ArgumentMatchers.any());

        for (String route : List.of(
                "route.hcm.operations.organization-positions.page",
                "route.hcm.operations.organization-graph.data")) {
            mvc.perform(organizationExact(
                            get("/v1/hris/organizations/graph"), route))
                    .andExpect(status().isOk());
        }

        assertThat(observedRoutes).containsExactly(
                "route.hcm.operations.organization-positions.page",
                "route.hcm.operations.organization-graph.data");
        assertThat(organizationGraphControllerCalls.get()).isEqualTo(2);
        verify(organizationGraphService, org.mockito.Mockito.times(2)).run();
        verify(organizationGraphRepository, org.mockito.Mockito.times(2)).run();
        verify(organizationGraphOutbox, org.mockito.Mockito.times(2)).run();
    }

    @Test
    void organizationFeatureAbsentFalseOrTenantUnenrolledHasOneOpaqueZeroCallDenial()
            throws Exception {
        List<OrganizationGateCase> gateCases = List.of(
                new OrganizationGateCase("flag absent defaults false", false, ""),
                new OrganizationGateCase("flag explicitly false", false, "3"),
                new OrganizationGateCase("tenant unenrolled", true, "99"));
        List<String> denials = new ArrayList<>();

        for (OrganizationGateCase gateCase : gateCases) {
            reset(validator, organizationGraphService, organizationGraphRepository,
                    organizationGraphOutbox);
            organizationGraphControllerCalls.set(0);
            MockMvc gated = mvcWithOrganizationFeature(
                    gateCase.enabled(), gateCase.enrolledTenants());

            String denial = gated.perform(organizationExact(
                            get("/v1/hris/organizations/graph"),
                            "route.hcm.operations.organization-graph.data"))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString();

            var denialPayload = new ObjectMapper().readTree(denial);
            denials.add(denialPayload.path("errorCode").asText()
                    + '|' + denialPayload.path("message").asText());
            assertThat(organizationGraphControllerCalls.get())
                    .as(gateCase.name()).isZero();
            verifyNoInteractions(organizationGraphService, organizationGraphRepository,
                    organizationGraphOutbox);
            verify(validator, never()).validate(org.mockito.ArgumentMatchers.any());
        }

        assertThat(denials).allMatch(denials.getFirst()::equals);
    }

    @Test
    void malformedOrganizationAuthorityNeverReachesTheControllerOrRepository()
            throws Exception {
        mvc.perform(organizationExact(
                        get("/v1/hris/organizations/graph"),
                        "route.hcm.operations.home.page"))
                .andExpect(status().isForbidden());
        mvc.perform(organizationExact(
                        get("/v1/hris/organizations/graph/"),
                        "route.hcm.operations.organization-graph.data"))
                .andExpect(status().isForbidden());
        mvc.perform(organizationExact(
                        get(URI.create("/v1/hris/organizations/%67raph")),
                        "route.hcm.operations.organization-graph.data"))
                .andExpect(status().isForbidden());
        mvc.perform(exact(
                        get("/v1/hris/organizations/graph"),
                        "route.hcm.operations.organization-graph.data",
                        "APP.HCM:VIEW,DATA.HRIS_ORGANIZATION:VIEW",
                        "HRIS_DUTY_ORGANIZATION_VIEWER@ORGANIZATION_TARGET_POPULATION")
                        .header(HcmProductSurfacePepFilter.PURPOSE_HEADER, "HR_ADMIN")
                        .header(HcmProductSurfacePepFilter.POPULATION_KIND_HEADER,
                                "TARGET_POPULATION"))
                .andExpect(status().isForbidden());
        mvc.perform(exact(
                        get("/v1/hris/organizations/graph"),
                        "route.hcm.operations.organization-graph.data",
                        "APP.HCM:VIEW,DATA.HRIS_ORGANIZATION:VIEW",
                        "HRIS_DUTY_ORGANIZATION_VIEWER@OTHER_POPULATION")
                        .header(HcmProductSurfacePepFilter.PURPOSE_HEADER, "HR_OPERATIONS")
                        .header(HcmProductSurfacePepFilter.POPULATION_KIND_HEADER,
                                "TARGET_POPULATION"))
                .andExpect(status().isForbidden());
        mvc.perform(organizationExact(
                        get("/v1/hris/organizations/graph"),
                        "route.hcm.operations.organization-graph.data")
                        .header(HcmProductSurfacePepFilter.PURPOSE_HEADER, "HR_OPERATIONS"))
                .andExpect(status().isForbidden());
        mvc.perform(exact(
                        get("/v1/hris/organizations/graph"),
                        "route.hcm.operations.organization-graph.data",
                        "APP.HCM:VIEW,DATA.HRIS_ORGANIZATION:VIEW",
                        "HRIS_DUTY_ORGANIZATION_VIEWER@ORGANIZATION_TARGET_POPULATION")
                        .header(HcmProductSurfacePepFilter.PURPOSE_HEADER, "HR_OPERATIONS")
                        .header(HcmProductSurfacePepFilter.POPULATION_KIND_HEADER, "SELF"))
                .andExpect(status().isForbidden());
        mvc.perform(organizationExact(
                        get("/v1/hris/organizations/graph"),
                        "route.hcm.operations.organization-graph.data")
                        .header(HcmProductSurfacePepFilter.ROUTE_CONTRACT_HEADER,
                                "route.hcm.operations.organization-graph.data"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(organizationExact(
                        get("/v1/hris/organizations/graph"),
                        "route.hcm.operations.organization-graph.data")
                        .header(HcmProductSurfacePepFilter.CURRENT_SCOPE_HEADER,
                                "stale-scope"))
                .andExpect(status().isServiceUnavailable());

        assertThat(organizationGraphControllerCalls.get()).isZero();
        verifyNoInteractions(organizationGraphService, organizationGraphRepository,
                organizationGraphOutbox);
        verify(validator, never()).validate(org.mockito.ArgumentMatchers.any());

        reset(validator);
        org.mockito.Mockito.doThrow(new IllegalStateException("stale owner scope"))
                .when(validator).validate(org.mockito.ArgumentMatchers.any());
        mvc.perform(organizationExact(
                        get("/v1/hris/organizations/graph"),
                        "route.hcm.operations.organization-graph.data"))
                .andExpect(status().isServiceUnavailable());

        assertThat(organizationGraphControllerCalls.get()).isZero();
        verifyNoInteractions(organizationGraphService, organizationGraphRepository,
                organizationGraphOutbox);
    }

    @Test
    void providerPeopleBindingsRemainForbiddenAfterSupportScopeRetirement()
            throws Exception {
        List<SupportBinding> bindings = List.of(
                new SupportBinding(
                        "/v1/workforce/operations/overview",
                        "route.hcm.operations.overview.page"),
                new SupportBinding(
                        "/v1/workforce/people",
                        "route.hcm.operations.people.page"),
                new SupportBinding(
                        "/v1/workforce/people?view=assignments",
                        "route.hcm.operations.assignments.page"),
                new SupportBinding(
                        "/v1/workforce/organization/chart",
                        "route.hcm.operations.people.page"));

        for (SupportBinding binding : bindings) {
            mvc.perform(providerSupportIdentity(get(URI.create(binding.uri())))
                            .header(HcmProductSurfacePepFilter.ROLLOUT_STATE_HEADER, "110")
                            .header(HcmProductSurfacePepFilter.ROLLOUT_REVISION_HEADER,
                                    ROLLOUT_REVISION)
                            .header(HcmProductSurfacePepFilter.ROLLOUT_COHORT_HEADER, "full")
                            .header(HcmProductSurfacePepFilter.ROUTE_CONTRACT_HEADER,
                                    binding.route())
                            .header(HcmProductSurfacePepFilter.CURRENT_CONTEXT_HEADER,
                                    CONTEXT_KEY)
                            .header(HcmProductSurfacePepFilter.CURRENT_SCOPE_HEADER,
                                    "support-session-scope")
                            .header(PeopleSecurityFilter.SUPPORT_SESSION_HEADER, "support-1")
                            .header(PeopleSecurityFilter.SUPPORT_SCOPES_HEADER,
                                    "WORKFORCE_READ")
                            .header(PeopleSecurityFilter.ACTOR_TENANT_HEADER, "9"))
                    .andExpect(status().isForbidden());
        }

        verify(service, never()).operationsOverview();
        verify(service, never()).team();
        verify(validator, never()).validate(org.mockito.ArgumentMatchers.any());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder exact(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String route) {
        return exact(request, route, "APP.HCM:VIEW", null);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder exact(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String route,
            String permissions,
            String resourceRoles) {
        return exact(request, route, permissions, resourceRoles, "NORMAL");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder exact(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String route,
            String permissions,
            String resourceRoles,
            String activeAccessMode) {
        var builder = identity(request, permissions)
                .header(HcmProductSurfacePepFilter.ROLLOUT_STATE_HEADER, "110")
                .header(HcmProductSurfacePepFilter.ROLLOUT_REVISION_HEADER, ROLLOUT_REVISION)
                .header(HcmProductSurfacePepFilter.ROLLOUT_COHORT_HEADER, "full")
                .header(HcmProductSurfacePepFilter.ROUTE_CONTRACT_HEADER, route)
                .header(HcmProductSurfacePepFilter.CURRENT_CONTEXT_HEADER, CONTEXT_KEY)
                .header(HcmProductSurfacePepFilter.CURRENT_SCOPE_HEADER, "hcm-scope-current")
                .header(HcmProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER, activeAccessMode)
                .header(HcmProductSurfacePepFilter.CURRENT_DECISION_REVISION_HEADER,
                        DECISION_REVISION)
                .header(HcmProductSurfacePepFilter.CURRENT_DECISION_REVALIDATE_AT_HEADER,
                        "2099-01-01T00:00:00Z");
        if (resourceRoles != null) {
            builder.header(HcmProductSurfacePepFilter.RESOURCE_ROLES_HEADER, resourceRoles);
        }
        return builder;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            organizationExact(
                    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                            request,
                    String route) {
        return exact(
                request,
                route,
                "APP.HCM:VIEW,DATA.HRIS_ORGANIZATION:VIEW",
                "HRIS_DUTY_ORGANIZATION_VIEWER@ORGANIZATION_TARGET_POPULATION")
                .header(HcmProductSurfacePepFilter.PURPOSE_HEADER, "HR_OPERATIONS")
                .header(HcmProductSurfacePepFilter.POPULATION_KIND_HEADER,
                        "TARGET_POPULATION");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder identity(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return identity(request, "APP.HCM:VIEW");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder identity(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String permissions) {
        return identity(request, "WORKSPACE_MEMBER", permissions);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            providerSupportIdentity(
                    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                            request) {
        return identity(request, "PROVIDER_SUPPORT", "");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder identity(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String roles,
            String permissions) {
        return request
                .header(PeopleSecurityFilter.SERVICE_TOKEN_HEADER, "people-token")
                .header(PeopleSecurityFilter.USER_HEADER, "17")
                .header(PeopleSecurityFilter.TENANT_HEADER, "3")
                .header(PeopleSecurityFilter.ROLES_HEADER, roles)
                .header(PeopleSecurityFilter.PERMISSIONS_HEADER, permissions);
    }

    private record SupportBinding(String uri, String route) {
    }

    private record OrganizationGateCase(
            String name,
            boolean enabled,
            String enrolledTenants) {
    }

    @RestController
    private static final class OrganizationGraphProbeController {

        private final AtomicInteger controllerCalls;
        private final Runnable service;
        private final Runnable repository;
        private final Runnable outbox;

        private OrganizationGraphProbeController(
                AtomicInteger controllerCalls,
                Runnable service,
                Runnable repository,
                Runnable outbox) {
            this.controllerCalls = controllerCalls;
            this.service = service;
            this.repository = repository;
            this.outbox = outbox;
        }

        @GetMapping("/v1/hris/organizations/graph")
        public Map<String, Boolean> graph() {
            controllerCalls.incrementAndGet();
            service.run();
            repository.run();
            outbox.run();
            return Map.of("ok", true);
        }
    }
}
