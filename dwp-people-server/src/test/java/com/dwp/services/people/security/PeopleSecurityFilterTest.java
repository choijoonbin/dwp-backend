package com.dwp.services.people.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

class PeopleSecurityFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void permitsReadOnlyWorkforceAccessForAResolvedSupportSession() throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = request("GET", "/v1/org-chart");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsWorkforceMutationsAndUnrelatedPeopleAdminRoutes() throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest mutation = request("POST", "/v1/workforce/organization/scenarios");
        MockHttpServletResponse mutationResponse = new MockHttpServletResponse();

        filter.doFilter(mutation, mutationResponse, new MockFilterChain());

        assertThat(mutationResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest admin = request("GET", "/v1/admin/unrelated");
        MockHttpServletResponse adminResponse = new MockHttpServletResponse();

        filter.doFilter(admin, adminResponse, new MockFilterChain());

        assertThat(adminResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void separatesTenantAdministrationFromWorkforceOperations() throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest tenantAdmin = regularRequest(
                "GET", "/v1/workforce/people", "TENANT_ADMIN");
        MockHttpServletResponse denied = new MockHttpServletResponse();

        filter.doFilter(tenantAdmin, denied, new MockFilterChain());

        assertThat(denied.getStatus()).isEqualTo(403);

        MockHttpServletRequest hrAdmin = regularRequest(
                "GET", "/v1/workforce/people", "HR_ADMIN");
        MockHttpServletResponse permitted = new MockHttpServletResponse();

        filter.doFilter(hrAdmin, permitted, new MockFilterChain());

        assertThat(permitted.getStatus()).isEqualTo(200);
    }

    @Test
    void permitsDelegatedWorkforceGovernanceOnlyForTheVerifiedAction() throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest read = regularRequest(
                "GET", "/v1/admin/workforce/access-policies", "WORKSPACE_MEMBER");
        read.addHeader(PeopleSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.WORKFORCE_ACCESS:VIEW");
        MockHttpServletResponse readResponse = new MockHttpServletResponse();
        AtomicReference<PeopleRequestContext.Actor> actor = new AtomicReference<>();

        filter.doFilter(read, readResponse, (request, response) ->
                actor.set(PeopleRequestContext.require()));

        assertThat(readResponse.getStatus()).isEqualTo(200);
        assertThat(actor.get().permissions()).contains("ADMIN.WORKFORCE_ACCESS:VIEW");

        MockHttpServletRequest write = regularRequest(
                "POST", "/v1/admin/workforce/access-policies", "WORKSPACE_MEMBER");
        write.addHeader(PeopleSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.WORKFORCE_ACCESS:VIEW");
        MockHttpServletResponse denied = new MockHttpServletResponse();

        filter.doFilter(write, denied, new MockFilterChain());

        assertThat(denied.getStatus()).isEqualTo(403);
    }

    @Test
    void doesNotLetARoleOverrideAVerifiedReadOnlyWorkforcePermission() throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest write = regularRequest(
                "POST", "/v1/workforce/exports", "PEOPLE_ADMIN");
        write.addHeader(PeopleSecurityFilter.PERMISSIONS_HEADER, "DATA.WORKFORCE:VIEW");
        MockHttpServletResponse denied = new MockHttpServletResponse();

        filter.doFilter(write, denied, new MockFilterChain());

        assertThat(denied.getStatus()).isEqualTo(403);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(PeopleSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PeopleSecurityFilter.USER_HEADER, "17");
        request.addHeader(PeopleSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(PeopleSecurityFilter.ROLES_HEADER, "ADMIN,PROVIDER_SUPPORT");
        request.addHeader(PeopleSecurityFilter.SUPPORT_SESSION_HEADER, "session-1");
        request.addHeader(PeopleSecurityFilter.SUPPORT_SCOPES_HEADER, "WORKFORCE_READ");
        request.addHeader(PeopleSecurityFilter.ACTOR_TENANT_HEADER, "3");
        return request;
    }

    private MockHttpServletRequest regularRequest(String method, String path, String roles) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(PeopleSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PeopleSecurityFilter.USER_HEADER, "17");
        request.addHeader(PeopleSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(PeopleSecurityFilter.ROLES_HEADER, roles);
        return request;
    }
}
