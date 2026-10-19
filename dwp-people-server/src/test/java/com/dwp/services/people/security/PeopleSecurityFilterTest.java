package com.dwp.services.people.security;

import com.dwp.services.people.integration.LocalSyntheticPeopleWorkforceBootstrapPaths;
import com.dwp.services.people.hris.contracts.hrm011.Hrm011WorkloadRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PeopleSecurityFilterTest {

    private static final String PERSON =
            "00000000-0000-4000-8000-000000000017";
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void rawHrm011PathsHeadersAndArbitraryAttributesCannotBypassTheGenericFilter()
            throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("", objectMapper);
        for (String path : new String[] {
                "/internal/hris-people/v1/compensation-basis-snapshots",
                "/internal/hris-people/v1/payroll-worker-fact-snapshots"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.addHeader("Authorization", "Bearer attacker-token");
            request.addHeader("X-Correlation-ID",
                    "10000000-0000-4000-8000-000000000001");
            request.addHeader("X-DWP-Workload-SPIFFE-ID",
                    "spiffe://dwp/service/dwp-payroll-server");
            request.addHeader("X-DWP-TLS-Protocol", "TLSv1.3");
            request.setAttribute(
                    Hrm011WorkloadRequestContext.class.getName() + ".verified",
                    Boolean.TRUE);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).as(path).isEqualTo(502);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void delegatesOnlyAnAuthorizedExactSyntheticPeoplePostToItsPurposeBuiltBoundary()
            throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("", objectMapper);
        MockHttpServletRequest exact = new MockHttpServletRequest(
                "POST", LocalSyntheticPeopleWorkforceBootstrapPaths.PATH);
        exact.setAttribute(
                LocalSyntheticPeopleWorkforceBootstrapPaths.AUTHORIZED_REQUEST_ATTRIBUTE,
                Boolean.TRUE);
        MockFilterChain exactChain = new MockFilterChain();

        filter.doFilter(exact, new MockHttpServletResponse(), exactChain);

        assertThat(exactChain.getRequest()).isSameAs(exact);

        for (MockHttpServletRequest rejected : new MockHttpServletRequest[] {
                new MockHttpServletRequest(
                        "POST", LocalSyntheticPeopleWorkforceBootstrapPaths.PATH),
                new MockHttpServletRequest(
                        "GET", LocalSyntheticPeopleWorkforceBootstrapPaths.PATH),
                new MockHttpServletRequest(
                        "POST", LocalSyntheticPeopleWorkforceBootstrapPaths.PATH + "/extra")}) {
            rejected.setAttribute(
                    LocalSyntheticPeopleWorkforceBootstrapPaths.AUTHORIZED_REQUEST_ATTRIBUTE,
                    Boolean.TRUE);
            if ("POST".equals(rejected.getMethod())
                    && LocalSyntheticPeopleWorkforceBootstrapPaths.PATH.equals(
                            rejected.getRequestURI())) {
                rejected.removeAttribute(
                        LocalSyntheticPeopleWorkforceBootstrapPaths.AUTHORIZED_REQUEST_ATTRIBUTE);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(rejected, response, chain);
            assertThat(response.getStatus()).isEqualTo(502);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void rejectsProviderWorkforceReadsUntilTheSafeProjectionIsImplemented() throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("trusted", objectMapper);
        for (String path : new String[] {
                "/v1/people",
                "/v1/people/00000000-0000-0000-0000-000000000001",
                "/v1/org-chart",
                "/v1/workforce/people",
                "/v1/workforce/organization/chart",
                "/v1/workforce/data-operations/hris/connectors",
                "/v1/workforce/exports",
                "/v1/workforce/reference-data",
                "/v1/admin/workforce/access-policies"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("GET", path), response, new MockFilterChain());

            assertThat(response.getStatus()).as(path).isEqualTo(403);
        }
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

    @Test
    void acceptsCanonicalAndLegacyHcmApplicationPermissions() throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("trusted", objectMapper);

        for (String resource : new String[] {"APP.HCM:VIEW", "APP.HRIS:VIEW"}) {
            MockHttpServletRequest request = regularRequest(
                    "GET", "/v1/hr/home", "WORKSPACE_MEMBER");
            request.addHeader(PeopleSecurityFilter.PERMISSIONS_HEADER, resource);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void rejectsHcmRoleFallbackWhenTheVerifiedPermissionModelIsNotLegacy() throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("trusted", objectMapper);

        for (String permissions : new String[] {"", "APP.MAIL:VIEW"}) {
            MockHttpServletRequest request = regularRequest(
                    "GET", "/v1/hr/home", "WORKSPACE_MEMBER");
            request.removeHeader(PeopleSecurityFilter.LEGACY_ROLE_FALLBACK_HEADER);
            request.addHeader(PeopleSecurityFilter.LEGACY_ROLE_FALLBACK_HEADER, "false");
            if (!permissions.isBlank()) {
                request.addHeader(PeopleSecurityFilter.PERMISSIONS_HEADER, permissions);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    @Test
    void permitsAnExplicitlyVerifiedGenuineLegacyHcmRole() throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = regularRequest(
                "GET", "/v1/hr/home", "WORKSPACE_MEMBER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void protectsProductEligibilityAsAnInternalServiceContract() throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("trusted", objectMapper);
        for (String identity : new String[] {"dwp-gateway", "dwp-platform-server"}) {
            MockHttpServletRequest request = eligibilityRequest(identity);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<PeopleRequestContext.Actor> actor = new AtomicReference<>();

            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                    actor.set(PeopleRequestContext.require()));

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(actor.get().userId()).isEqualTo(17L);
            assertThat(actor.get().tenantId()).isEqualTo(42L);
            assertThat(actor.get().personPublicId()).isEqualTo(UUID.fromString(PERSON));
        }

        java.util.List<MockHttpServletRequest> rejected = new java.util.ArrayList<>();
        rejected.add(eligibilityRequest(null));
        rejected.add(eligibilityRequest("another-service"));

        MockHttpServletRequest duplicateIdentity = eligibilityRequest("dwp-gateway");
        duplicateIdentity.addHeader(PeopleSecurityFilter.SERVICE_IDENTITY_HEADER,
                "dwp-platform-server");
        rejected.add(duplicateIdentity);
        MockHttpServletRequest commaIdentity = eligibilityRequest("dwp-gateway,dwp-platform-server");
        rejected.add(commaIdentity);
        MockHttpServletRequest paddedIdentity = eligibilityRequest(" dwp-gateway");
        rejected.add(paddedIdentity);
        MockHttpServletRequest newlineIdentity = eligibilityRequest("dwp-gateway\r\nforged");
        rejected.add(newlineIdentity);

        MockHttpServletRequest missingPerson = eligibilityRequest("dwp-gateway");
        missingPerson.removeHeader(PeopleSecurityFilter.PERSON_PUBLIC_ID_HEADER);
        rejected.add(missingPerson);
        MockHttpServletRequest malformedPerson = eligibilityRequest("dwp-gateway");
        malformedPerson.removeHeader(PeopleSecurityFilter.PERSON_PUBLIC_ID_HEADER);
        malformedPerson.addHeader(PeopleSecurityFilter.PERSON_PUBLIC_ID_HEADER, "not-a-uuid");
        rejected.add(malformedPerson);

        for (String malformed : new String[] {"+17", "017", "0", "-1"}) {
            MockHttpServletRequest actor = eligibilityRequest("dwp-platform-server");
            actor.removeHeader(PeopleSecurityFilter.USER_HEADER);
            actor.addHeader(PeopleSecurityFilter.USER_HEADER, malformed);
            rejected.add(actor);
        }
        MockHttpServletRequest duplicateTenant = eligibilityRequest("dwp-platform-server");
        duplicateTenant.addHeader(PeopleSecurityFilter.TENANT_HEADER, "43");
        rejected.add(duplicateTenant);
        MockHttpServletRequest paddedToken = eligibilityRequest("dwp-platform-server");
        paddedToken.removeHeader(PeopleSecurityFilter.SERVICE_TOKEN_HEADER);
        paddedToken.addHeader(PeopleSecurityFilter.SERVICE_TOKEN_HEADER, " trusted");
        rejected.add(paddedToken);

        for (MockHttpServletRequest request : rejected) {
            MockHttpServletResponse denied = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, denied, chain);
            assertThat(denied.getStatus()).as(request.toString()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }

    private MockHttpServletRequest eligibilityRequest(String identity) {
        MockHttpServletRequest request = regularRequest(
                "POST", "/internal/people/v1/product-surface-eligibility/evaluate",
                "WORKSPACE_MEMBER");
        request.addHeader(PeopleSecurityFilter.PERSON_PUBLIC_ID_HEADER, PERSON);
        if (identity != null) {
            request.addHeader(PeopleSecurityFilter.SERVICE_IDENTITY_HEADER, identity);
        }
        return request;
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
        request.addHeader(PeopleSecurityFilter.LEGACY_ROLE_FALLBACK_HEADER, "true");
        return request;
    }
}
