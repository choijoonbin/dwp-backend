package com.dwp.services.people.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.people.directory.PeopleDirectoryController;
import com.dwp.services.people.directory.PeopleDirectoryService;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hr.HrController;
import com.dwp.services.people.hr.HrService;
import com.dwp.services.people.workforce.WorkforceAccessPolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

class HcmOwnerPepExecutionTest {

    private static final long ACTOR_ID = 17L;
    private static final long TENANT_ID = 3L;
    private static final long FOREIGN_TENANT_ID = 4L;
    private static final UUID ACTOR_PERSON_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000017");
    private static final UUID DIRECTORY_PERSON_ID = ACTOR_PERSON_ID;
    private static final UUID TIME_CARD_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String ROLLOUT_REVISION = "rollout-" + "a".repeat(64);
    private static final String DECISION_REVISION = "psr-" + "b".repeat(64);
    private static final String STALE_DECISION_REVISION = "psr-" + "c".repeat(64);
    private static final String CONTEXT_KEY = "psc-" + "d".repeat(64);
    private static final HcmPopulationRepository.ActorWorkforce ACTOR_WORKFORCE =
            new HcmPopulationRepository.ActorWorkforce(
                    41L,
                    ACTOR_PERSON_ID,
                    "HCM actor",
                    "A-HCM-ACTOR",
                    "Engineer",
                    "Platform",
                    2L,
                    4L,
                    7L);

    private final HrService hrService = mock(HrService.class);
    private final PeopleDirectoryService directoryService = mock(PeopleDirectoryService.class);
    private final HcmPopulationRepository populationRepository =
            mock(HcmPopulationRepository.class);
    private final WorkforceAccessPolicyService accessPolicies =
            mock(WorkforceAccessPolicyService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(hrService, directoryService, populationRepository, accessPolicies);
        when(populationRepository.actor(TENANT_ID, ACTOR_PERSON_ID))
                .thenReturn(Optional.of(ACTOR_WORKFORCE));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HcmScopeSelectionValidator scopeValidator = new HcmScopeSelectionValidator(
                new HcmPopulationScopeService(populationRepository, accessPolicies));
        mvc = MockMvcBuilders.standaloneSetup(
                        new HrController(hrService),
                        new PeopleDirectoryController(directoryService))
                .addFilters(
                        new PeopleSecurityFilter("people-token", mapper),
                        new HcmProductSurfacePepFilter(
                                true, new HcmV3PepRegistry(mapper), scopeValidator, mapper))
                .build();
    }

    @Test
    void crossTenantOpaqueScopeNeverReachesThePublicHcmController() throws Exception {
        mvc.perform(exact(
                        get("/v1/hr/home"),
                        "route.hcm.personal.home.page",
                        "WORKSPACE_MEMBER",
                        "APP.HCM:VIEW",
                        selfScope(FOREIGN_TENANT_ID, ACTOR_ID)))
                .andExpect(status().isForbidden());

        verify(hrService, never()).home();
    }

    @Test
    void canonicalOpaqueScopeEscapeNeverReachesThePublicHcmController() throws Exception {
        String teamSource = ProductSurfaceScopeKey.key(
                TENANT_ID,
                ACTOR_ID,
                "hcm",
                "hcm.team",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                "TARGET_POPULATION");
        String scopeIssuedForAnotherResolver = HcmEligibilityScopeKeys.derived(
                TENANT_ID,
                ACTOR_ID,
                "hcm.team",
                teamSource,
                ACTOR_WORKFORCE.revision(),
                "team-population:9");

        mvc.perform(exact(
                        get("/v1/hr/home"),
                        "route.hcm.personal.home.page",
                        "WORKSPACE_MEMBER",
                        "APP.HCM:VIEW",
                        scopeIssuedForAnotherResolver))
                .andExpect(status().isForbidden());

        verify(hrService, never()).home();
    }

    @Test
    void staleAuthorityRevisionNeverReachesThePublicHcmAction() throws Exception {
        MockHttpServletRequestBuilder request = exact(
                post("/v1/hr/time/{cardId}/submit", TIME_CARD_ID).queryParam("version", "7"),
                "route.hcm.personal.time-submit.action",
                "WORKSPACE_MEMBER",
                "APP.HCM:VIEW",
                selfScope(TENANT_ID, ACTOR_ID));
        request.header(
                HcmProductSurfacePepFilter.EXPECTED_DECISION_REVISION_HEADER,
                STALE_DECISION_REVISION);

        mvc.perform(request)
                .andExpect(status().isConflict());

        verify(hrService, never()).submitTimeCard(TIME_CARD_ID, 7L, null);
    }

    @Test
    void normalAndSupportAuthorityCannotBeMixedAcrossThePublicHcmBoundary()
            throws Exception {
        MockHttpServletRequestBuilder normalWithSupport = exact(
                get("/v1/hr/home"),
                "route.hcm.personal.home.page",
                "WORKSPACE_MEMBER",
                "APP.HCM:VIEW",
                selfScope(TENANT_ID, ACTOR_ID));
        normalWithSupport
                .header(PeopleSecurityFilter.SUPPORT_SESSION_HEADER, "support-session-1")
                .header(PeopleSecurityFilter.SUPPORT_SCOPES_HEADER, "WORKFORCE_READ")
                .header(PeopleSecurityFilter.ACTOR_TENANT_HEADER, String.valueOf(TENANT_ID));

        mvc.perform(normalWithSupport)
                .andExpect(status().isForbidden());

        mvc.perform(exact(
                        get("/v1/hr/home"),
                        "route.hcm.personal.home.page",
                        "PROVIDER_SUPPORT",
                        "APP.HCM:VIEW",
                        selfScope(TENANT_ID, ACTOR_ID)))
                .andExpect(status().isForbidden());

        mvc.perform(exact(
                        get("/v1/hr/home"),
                        "route.hcm.personal.home.page",
                        "WORKSPACE_MEMBER",
                        "APP.HCM:VIEW",
                        selfScope(TENANT_ID, ACTOR_ID),
                        CONTEXT_KEY,
                        "PROVIDER_SUPPORT"))
                .andExpect(status().isForbidden());

        verify(hrService, never()).home();
    }

    @Test
    void spoofedInternalHeadersCannotBypassPeopleIdentityOnAPublicHcmRoute()
            throws Exception {
        MockHttpServletRequestBuilder spoofed = get("/v1/hr/home")
                .header(PeopleSecurityFilter.SERVICE_TOKEN_HEADER, "attacker-token")
                .header(PeopleSecurityFilter.SERVICE_IDENTITY_HEADER, "dwp-gateway")
                .header(PeopleSecurityFilter.USER_HEADER, String.valueOf(ACTOR_ID))
                .header(PeopleSecurityFilter.TENANT_HEADER, String.valueOf(TENANT_ID))
                .header(PeopleSecurityFilter.PERSON_PUBLIC_ID_HEADER, ACTOR_PERSON_ID.toString())
                .header(PeopleSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER")
                .header(PeopleSecurityFilter.PERMISSIONS_HEADER, "APP.HCM:VIEW")
                .header(HcmProductSurfacePepFilter.ROLLOUT_STATE_HEADER, "110")
                .header(HcmProductSurfacePepFilter.ROLLOUT_REVISION_HEADER, ROLLOUT_REVISION)
                .header(HcmProductSurfacePepFilter.ROLLOUT_COHORT_HEADER, "full")
                .header(
                        HcmProductSurfacePepFilter.ROUTE_CONTRACT_HEADER,
                        "route.hcm.personal.home.page")
                .header(HcmProductSurfacePepFilter.CURRENT_CONTEXT_HEADER, CONTEXT_KEY)
                .header(HcmProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER, "NORMAL")
                .header(
                        HcmProductSurfacePepFilter.CURRENT_SCOPE_HEADER,
                        selfScope(TENANT_ID, ACTOR_ID))
                .header(
                        HcmProductSurfacePepFilter.CURRENT_DECISION_REVISION_HEADER,
                        DECISION_REVISION)
                .header(
                        HcmProductSurfacePepFilter.CURRENT_DECISION_REVALIDATE_AT_HEADER,
                        "2099-01-01T00:00:00Z");

        mvc.perform(spoofed)
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(hrService);
    }

    @Test
    void duplicateCanonicalHeaderNeverReachesThePublicHcmController() throws Exception {
        MockHttpServletRequestBuilder request = exact(
                get("/v1/hr/home"),
                "route.hcm.personal.home.page",
                "WORKSPACE_MEMBER",
                "APP.HCM:VIEW",
                selfScope(TENANT_ID, ACTOR_ID));
        request.header(HcmProductSurfacePepFilter.CURRENT_CONTEXT_HEADER, CONTEXT_KEY);

        mvc.perform(request)
                .andExpect(status().isServiceUnavailable());

        verify(hrService, never()).home();
    }

    @Test
    void whitespacePaddedCanonicalHeaderNeverReachesThePublicHcmController()
            throws Exception {
        String scope = selfScope(TENANT_ID, ACTOR_ID);
        for (String whitespacePaddedScope : List.of(" " + scope, scope + " ")) {
            mvc.perform(exact(
                            get("/v1/hr/home"),
                            "route.hcm.personal.home.page",
                            "WORKSPACE_MEMBER",
                            "APP.HCM:VIEW",
                            whitespacePaddedScope))
                    .andExpect(status().isServiceUnavailable());
        }

        verify(hrService, never()).home();
    }

    @Test
    void nonCanonicalContextKeyNeverReachesThePublicHcmController() throws Exception {
        mvc.perform(exact(
                        get("/v1/hr/home"),
                        "route.hcm.personal.home.page",
                        "WORKSPACE_MEMBER",
                        "APP.HCM:VIEW",
                        selfScope(TENANT_ID, ACTOR_ID),
                        "psc-context"))
                .andExpect(status().isServiceUnavailable());

        verify(hrService, never()).home();
    }

    @Test
    void pHcmPageDataAndActionBindingsReachTheirActualPublicControllers() throws Exception {
        String scope = selfScope(TENANT_ID, ACTOR_ID);

        mvc.perform(exact(
                        get("/v1/hr/home"),
                        "route.hcm.personal.home.page",
                        "WORKSPACE_MEMBER",
                        "APP.HCM:VIEW",
                        scope))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HcmProductSurfacePepFilter.RESPONSE_DECISION_REVISION_HEADER,
                        DECISION_REVISION));

        mvc.perform(exact(
                        get("/v1/people/{publicId}", DIRECTORY_PERSON_ID)
                                .queryParam("view", "directory"),
                        "route.hcm.personal.directory-person-detail.data",
                        "WORKSPACE_MEMBER",
                        "APP.PEOPLE_DIRECTORY:VIEW",
                        scope))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HcmProductSurfacePepFilter.RESPONSE_DECISION_REVISION_HEADER,
                        DECISION_REVISION));

        MockHttpServletRequestBuilder action = exact(
                post("/v1/hr/time/{cardId}/submit", TIME_CARD_ID).queryParam("version", "7"),
                "route.hcm.personal.time-submit.action",
                "WORKSPACE_MEMBER",
                "APP.HCM:VIEW",
                scope,
                CONTEXT_KEY,
                "ELEVATED");
        action.header(
                HcmProductSurfacePepFilter.EXPECTED_DECISION_REVISION_HEADER,
                DECISION_REVISION);
        mvc.perform(action)
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HcmProductSurfacePepFilter.RESPONSE_DECISION_REVISION_HEADER,
                        DECISION_REVISION));

        verify(hrService).home();
        verify(directoryService).get(DIRECTORY_PERSON_ID, null);
        verify(hrService).submitTimeCard(TIME_CARD_ID, 7L, null);
    }

    private MockHttpServletRequestBuilder exact(
            MockHttpServletRequestBuilder request,
            String route,
            String roles,
            String permissions,
            String scope) {
        return exact(request, route, roles, permissions, scope, CONTEXT_KEY, "NORMAL");
    }

    private MockHttpServletRequestBuilder exact(
            MockHttpServletRequestBuilder request,
            String route,
            String roles,
            String permissions,
            String scope,
            String context) {
        return exact(request, route, roles, permissions, scope, context, "NORMAL");
    }

    private MockHttpServletRequestBuilder exact(
            MockHttpServletRequestBuilder request,
            String route,
            String roles,
            String permissions,
            String scope,
            String context,
            String activeAccessMode) {
        return request
                .header(PeopleSecurityFilter.SERVICE_TOKEN_HEADER, "people-token")
                .header(PeopleSecurityFilter.USER_HEADER, String.valueOf(ACTOR_ID))
                .header(PeopleSecurityFilter.TENANT_HEADER, String.valueOf(TENANT_ID))
                .header(PeopleSecurityFilter.PERSON_PUBLIC_ID_HEADER, ACTOR_PERSON_ID.toString())
                .header(PeopleSecurityFilter.ROLES_HEADER, roles)
                .header(PeopleSecurityFilter.PERMISSIONS_HEADER, permissions)
                .header(HcmProductSurfacePepFilter.ROLLOUT_STATE_HEADER, "110")
                .header(HcmProductSurfacePepFilter.ROLLOUT_REVISION_HEADER, ROLLOUT_REVISION)
                .header(HcmProductSurfacePepFilter.ROLLOUT_COHORT_HEADER, "full")
                .header(HcmProductSurfacePepFilter.ROUTE_CONTRACT_HEADER, route)
                .header(HcmProductSurfacePepFilter.CURRENT_CONTEXT_HEADER, context)
                .header(HcmProductSurfacePepFilter.CURRENT_SCOPE_HEADER, scope)
                .header(HcmProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER, activeAccessMode)
                .header(
                        HcmProductSurfacePepFilter.CURRENT_DECISION_REVISION_HEADER,
                        DECISION_REVISION)
                .header(
                        HcmProductSurfacePepFilter.CURRENT_DECISION_REVALIDATE_AT_HEADER,
                        "2099-01-01T00:00:00Z");
    }

    private String selfScope(long tenantId, long actorId) {
        String source = ProductSurfaceScopeKey.key(
                tenantId, actorId, "hcm", "hcm.personal", "SELF", "SELF");
        String targetRevision =
                "self:" + ACTOR_WORKFORCE.personId() + ':' + ACTOR_WORKFORCE.revision();
        return HcmEligibilityScopeKeys.derived(
                tenantId,
                actorId,
                "hcm.personal",
                source,
                ACTOR_WORKFORCE.revision(),
                targetRevision);
    }
}
