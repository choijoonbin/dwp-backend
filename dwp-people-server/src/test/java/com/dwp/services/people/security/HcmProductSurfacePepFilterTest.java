package com.dwp.services.people.security;

import com.dwp.services.people.hr.HrController;
import com.dwp.services.people.hr.HrService;
import com.dwp.services.people.workforce.WorkforceCandidateController;
import com.dwp.services.people.workforce.WorkforceCandidateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HcmProductSurfacePepFilterTest {

    private static final String ROLLOUT_REVISION = "rollout-" + "a".repeat(64);
    private static final String DECISION_REVISION = "psr-" + "b".repeat(64);

    private final HrService service = mock(HrService.class);
    private final WorkforceCandidateService candidates = mock(WorkforceCandidateService.class);
    private final HcmScopeSelectionValidator validator = mock(HcmScopeSelectionValidator.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service, candidates, validator);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HcmV3PepRegistry registry = new HcmV3PepRegistry(mapper);
        mvc = MockMvcBuilders.standaloneSetup(
                        new HrController(service), new WorkforceCandidateController(candidates))
                .addFilters(
                        new PeopleSecurityFilter("people-token", mapper),
                        new HcmProductSurfacePepFilter(true, registry, validator, mapper))
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
                                    "psc-context")
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
        var builder = identity(request, permissions)
                .header(HcmProductSurfacePepFilter.ROLLOUT_STATE_HEADER, "110")
                .header(HcmProductSurfacePepFilter.ROLLOUT_REVISION_HEADER, ROLLOUT_REVISION)
                .header(HcmProductSurfacePepFilter.ROLLOUT_COHORT_HEADER, "full")
                .header(HcmProductSurfacePepFilter.ROUTE_CONTRACT_HEADER, route)
                .header(HcmProductSurfacePepFilter.CURRENT_CONTEXT_HEADER, "psc-context")
                .header(HcmProductSurfacePepFilter.CURRENT_SCOPE_HEADER, "hcm-scope-current")
                .header(HcmProductSurfacePepFilter.CURRENT_DECISION_REVISION_HEADER,
                        DECISION_REVISION)
                .header(HcmProductSurfacePepFilter.CURRENT_DECISION_REVALIDATE_AT_HEADER,
                        "2099-01-01T00:00:00Z");
        if (resourceRoles != null) {
            builder.header(HcmProductSurfacePepFilter.RESOURCE_ROLES_HEADER, resourceRoles);
        }
        return builder;
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
}
