package com.dwp.services.people.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HrisEmployeeChangeSecurityBridgeTest {

    private static final String REQUEST_ID =
            "00000000-0000-0000-0000-000000000101";
    private static final String CALLBACK =
            "/internal/hris-people/v1/employee-change-requests/"
                    + REQUEST_ID + "/approval-decisions";
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void recognizesOnlyTheFourGeneratedV36BrowserBindings() {
        assertThat(HrisEmployeeChangeSecurityBridge.browserRouteContract(
                "GET", "/v1/hris/workbench/operations/employee/change/requests"))
                .isEqualTo(HrisEmployeeChangeSecurityBridge.REQUESTS_PAGE_ROUTE);
        assertThat(HrisEmployeeChangeSecurityBridge.browserRouteContract(
                "POST", "/v1/hris/employee-change-requests"))
                .isEqualTo(HrisEmployeeChangeSecurityBridge.CREATE_ROUTE);
        assertThat(HrisEmployeeChangeSecurityBridge.browserRouteContract(
                "POST", "/v1/hris/employee-change-requests/" + REQUEST_ID + "/submit"))
                .isEqualTo(HrisEmployeeChangeSecurityBridge.SUBMIT_ROUTE);
        assertThat(HrisEmployeeChangeSecurityBridge.browserRouteContract(
                "POST", "/v1/hris/employee-change-requests/" + REQUEST_ID + "/apply"))
                .isEqualTo(HrisEmployeeChangeSecurityBridge.APPLY_ROUTE);

        for (BrowserBinding rejected : List.of(
                new BrowserBinding(
                        "POST", "/v1/hris/workbench/operations/employee/change/requests"),
                new BrowserBinding("GET", "/v1/hris/employee-change-requests"),
                new BrowserBinding(
                        "POST", "/v1/hris/employee-change-requests/" + REQUEST_ID + "/delete"),
                new BrowserBinding(
                        "POST", "/v1/hris/employee-change-requests/" + REQUEST_ID
                                + "/submit/extra"),
                new BrowserBinding(
                        "POST", "/v1/hris/employee-change-requests%2f" + REQUEST_ID
                                + "%2fsubmit"))) {
            assertThat(HrisEmployeeChangeSecurityBridge.browserRouteContract(
                    rejected.method(), rejected.path())).as(rejected.path()).isNull();
        }
    }

    @Test
    void recognitionTableIsClosedOverTheGeneratedV36PeoplePep() {
        var bridgeRoutes = List.of(
                HrisEmployeeChangeSecurityBridge.REQUESTS_PAGE_ROUTE,
                HrisEmployeeChangeSecurityBridge.CREATE_ROUTE,
                HrisEmployeeChangeSecurityBridge.SUBMIT_ROUTE,
                HrisEmployeeChangeSecurityBridge.APPLY_ROUTE);
        var generated = new HcmV3PepRegistry(objectMapper).bindingContracts().stream()
                .filter(binding -> bridgeRoutes.contains(binding.routeContractKey()))
                .map(binding -> new BrowserAuthority(
                        binding.method(), binding.servicePath(), binding.routeContractKey()))
                .toList();

        assertThat(generated).containsExactlyInAnyOrder(
                new BrowserAuthority(
                        "GET", "/v1/hris/workbench/operations/employee/change/requests",
                        HrisEmployeeChangeSecurityBridge.REQUESTS_PAGE_ROUTE),
                new BrowserAuthority(
                        "POST", "/v1/hris/employee-change-requests",
                        HrisEmployeeChangeSecurityBridge.CREATE_ROUTE),
                new BrowserAuthority(
                        "POST", "/v1/hris/employee-change-requests/{requestPublicId}/submit",
                        HrisEmployeeChangeSecurityBridge.SUBMIT_ROUTE),
                new BrowserAuthority(
                        "POST", "/v1/hris/employee-change-requests/{requestPublicId}/apply",
                        HrisEmployeeChangeSecurityBridge.APPLY_ROUTE));
    }

    @Test
    void employeeChangeFamilyLookalikesAreClaimedForFailClosedHandling() {
        for (String path : List.of(
                "/v1/hris/workbench/operations/employee/change/requests/extra",
                "/v1/hris/workbench/operations/employee/change/requests;x=y",
                "/v1/hris/employee-change-requests/" + REQUEST_ID + "/delete",
                "/v1/hris/employee-change-requests%2f" + REQUEST_ID + "%2fsubmit")) {
            assertThat(HrisEmployeeChangeSecurityBridge.claimsBrowserFamily(path))
                    .as(path).isTrue();
        }
        assertThat(HrisEmployeeChangeSecurityBridge.claimsBrowserFamily(
                "/v1/hris/employee-change-requests-admin")).isFalse();
        assertThat(HrisEmployeeChangeSecurityBridge.claimsBrowserFamily(
                "/v1/admin/employee-change-requests")).isFalse();
    }

    @Test
    void browserOwnerRoutesRequireTheMatchingSingleV36AuthorityHeader() throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("trusted", objectMapper);
        List<BrowserAuthority> bindings = List.of(
                new BrowserAuthority(
                        "GET", "/v1/hris/workbench/operations/employee/change/requests",
                        HrisEmployeeChangeSecurityBridge.REQUESTS_PAGE_ROUTE),
                new BrowserAuthority(
                        "POST", "/v1/hris/employee-change-requests",
                        HrisEmployeeChangeSecurityBridge.CREATE_ROUTE),
                new BrowserAuthority(
                        "POST", "/v1/hris/employee-change-requests/" + REQUEST_ID + "/submit",
                        HrisEmployeeChangeSecurityBridge.SUBMIT_ROUTE),
                new BrowserAuthority(
                        "POST", "/v1/hris/employee-change-requests/" + REQUEST_ID + "/apply",
                        HrisEmployeeChangeSecurityBridge.APPLY_ROUTE));

        for (BrowserAuthority binding : bindings) {
            MockHttpServletRequest accepted = browserRequest(binding.method(), binding.path());
            accepted.addHeader(PeopleSecurityHeaders.ROLLOUT_STATE, "110");
            accepted.addHeader(PeopleSecurityHeaders.ROUTE_CONTRACT, binding.route());
            MockFilterChain acceptedChain = new MockFilterChain();

            filter.doFilter(accepted, new MockHttpServletResponse(), acceptedChain);

            assertThat(acceptedChain.getRequest()).as(binding.path()).isSameAs(accepted);

            MockHttpServletRequest noAuthority = browserRequest(
                    binding.method(), binding.path());
            noAuthority.addHeader(PeopleSecurityFilter.ROLES_HEADER, "PEOPLE_ADMIN");
            MockHttpServletResponse denied = new MockHttpServletResponse();
            MockFilterChain deniedChain = new MockFilterChain();

            filter.doFilter(noAuthority, denied, deniedChain);

            assertThat(denied.getStatus()).as(binding.path()).isEqualTo(503);
            assertThat(deniedChain.getRequest()).isNull();
        }

        MockHttpServletRequest mismatched = browserRequest(
                "GET", "/v1/hris/workbench/operations/employee/change/requests");
        mismatched.addHeader(PeopleSecurityHeaders.ROLLOUT_STATE, "110");
        mismatched.addHeader(PeopleSecurityHeaders.ROUTE_CONTRACT,
                HrisEmployeeChangeSecurityBridge.CREATE_ROUTE);
        MockHttpServletResponse mismatchResponse = new MockHttpServletResponse();
        filter.doFilter(mismatched, mismatchResponse, new MockFilterChain());
        assertThat(mismatchResponse.getStatus()).isEqualTo(503);
    }

    @Test
    void duplicateOrNonEnforcingAuthorityEvidenceFailsClosed() throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest duplicate = browserRequest(
                "GET", "/v1/hris/workbench/operations/employee/change/requests");
        duplicate.addHeader(PeopleSecurityHeaders.ROLLOUT_STATE, "110");
        duplicate.addHeader(PeopleSecurityHeaders.ROUTE_CONTRACT,
                HrisEmployeeChangeSecurityBridge.REQUESTS_PAGE_ROUTE);
        duplicate.addHeader(PeopleSecurityHeaders.ROUTE_CONTRACT,
                HrisEmployeeChangeSecurityBridge.CREATE_ROUTE);
        MockHttpServletResponse duplicateResponse = new MockHttpServletResponse();
        filter.doFilter(duplicate, duplicateResponse, new MockFilterChain());
        assertThat(duplicateResponse.getStatus()).isEqualTo(503);

        MockHttpServletRequest baseline = browserRequest(
                "GET", "/v1/hris/workbench/operations/employee/change/requests");
        baseline.addHeader(PeopleSecurityHeaders.ROLLOUT_STATE, "100");
        baseline.addHeader(PeopleSecurityHeaders.ROUTE_CONTRACT,
                HrisEmployeeChangeSecurityBridge.REQUESTS_PAGE_ROUTE);
        MockHttpServletResponse baselineResponse = new MockHttpServletResponse();
        filter.doFilter(baseline, baselineResponse, new MockFilterChain());
        assertThat(baselineResponse.getStatus()).isEqualTo(503);
    }

    @Test
    void defaultOffProductPepStillFailsClosedAfterTheSecurityBridgeRecognizesV36()
            throws Exception {
        PeopleSecurityFilter browserIdentity =
                new PeopleSecurityFilter("trusted", objectMapper);
        HcmProductSurfacePepFilter defaultOffPep = new HcmProductSurfacePepFilter(
                false,
                new HcmV3PepRegistry(objectMapper),
                mock(HcmScopeSelectionValidator.class),
                objectMapper);
        MockHttpServletRequest request = browserRequest(
                "GET", "/v1/hris/workbench/operations/employee/change/requests");
        request.addHeader(PeopleSecurityHeaders.ROLLOUT_STATE, "110");
        request.addHeader(HcmProductSurfacePepFilter.ROLLOUT_REVISION_HEADER,
                "rollout-" + "a".repeat(64));
        request.addHeader(HcmProductSurfacePepFilter.ROLLOUT_COHORT_HEADER, "full");
        request.addHeader(PeopleSecurityHeaders.ROUTE_CONTRACT,
                HrisEmployeeChangeSecurityBridge.REQUESTS_PAGE_ROUTE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain terminal = new MockFilterChain();

        browserIdentity.doFilter(request, response,
                (currentRequest, currentResponse) -> defaultOffPep.doFilter(
                        currentRequest, currentResponse, terminal));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(terminal.getRequest()).isNull();
    }

    @Test
    void approvalCallbackCanBypassBrowserAuthOnlyAfterWorkloadVerification()
            throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest browser = browserRequest("POST", CALLBACK);
        browser.addHeader(PeopleSecurityFilter.ROLES_HEADER, "PEOPLE_ADMIN");
        MockHttpServletResponse browserResponse = new MockHttpServletResponse();
        MockFilterChain browserChain = new MockFilterChain();

        filter.doFilter(browser, browserResponse, browserChain);

        assertThat(browserResponse.getStatus()).isEqualTo(401);
        assertThat(browserChain.getRequest()).isNull();

        MockHttpServletRequest workload = new MockHttpServletRequest("POST", CALLBACK);
        workload.addHeader("X-DWP-Service-Identity", "dwp-approval-server");
        workload.addHeader("X-DWP-Workload-Proof", "verified-by-prior-filter");
        HrisEmployeeChangeSecurityBridge.markApprovalWorkloadVerified(workload);
        MockFilterChain workloadChain = new MockFilterChain();

        filter.doFilter(workload, new MockHttpServletResponse(), workloadChain);

        assertThat(workloadChain.getRequest()).isSameAs(workload);
    }

    @Test
    void workloadMarkerCannotBlessWrongMethodOrNearbyCallbackPath() throws Exception {
        PeopleSecurityFilter filter = new PeopleSecurityFilter("", objectMapper);
        for (MockHttpServletRequest request : List.of(
                new MockHttpServletRequest("GET", CALLBACK),
                new MockHttpServletRequest("POST", CALLBACK + "/extra"),
                new MockHttpServletRequest("POST", CALLBACK.replace(
                        REQUEST_ID, "not-a-uuid")))) {
            HrisEmployeeChangeSecurityBridge.markApprovalWorkloadVerified(request);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).as(request.getRequestURI()).isEqualTo(502);
            assertThat(chain.getRequest()).isNull();
        }
    }

    private MockHttpServletRequest browserRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(PeopleSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PeopleSecurityFilter.USER_HEADER, "17");
        request.addHeader(PeopleSecurityFilter.TENANT_HEADER, "42");
        return request;
    }

    private record BrowserBinding(String method, String path) {
    }

    private record BrowserAuthority(String method, String path, String route) {
    }
}
