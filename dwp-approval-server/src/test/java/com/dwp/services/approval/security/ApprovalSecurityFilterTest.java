package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalSecurityFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void requiresAnExplicitApplicationPermissionEvenForReads() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = request("GET", "/v1/home", "APPROVAL_OPERATOR", "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void permitsAWorkspaceMemberWithVerifiedApplicationAccess() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = request(
                "GET", "/v1/home", "WORKSPACE_MEMBER", "APP.APPROVALS:VIEW");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void requiresDomainPermissionsForUserDataCollections() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletResponse tasksDenied = new MockHttpServletResponse();
        filter.doFilter(request(
                "GET", "/v1/tasks", "APPROVAL_DESIGNER", "APP.APPROVALS:VIEW"),
                tasksDenied, new MockFilterChain());

        MockHttpServletResponse requestsPermitted = new MockHttpServletResponse();
        filter.doFilter(request(
                "GET", "/v1/requests", "WORKSPACE_MEMBER",
                "APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:VIEW"),
                requestsPermitted, new MockFilterChain());

        assertThat(tasksDenied.getStatus()).isEqualTo(403);
        assertThat(requestsPermitted.getStatus()).isEqualTo(200);
    }

    @Test
    void permitsRequesterToResumeAnInformationRequestWithUpdatePermission() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST",
                "/v1/requests/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/information-response",
                "WORKSPACE_MEMBER",
                "APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:UPDATE"),
                response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doesNotLetARoleOrReadPermissionAuthorizeADecision() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = request(
                "POST",
                "/v1/tasks/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/decisions",
                "APPROVAL_OPERATOR",
                "APP.APPROVALS:VIEW");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void separatesWorkflowDesignFromWorkflowPublishing() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest request = request(
                "POST",
                "/v1/admin/workflows/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/publish",
                "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_DESIGN:VIEW,ADMIN.APPROVAL_DESIGN:UPDATE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void distinguishesWorkflowCreationAndDraftUpdates() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletResponse createDenied = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST", "/v1/admin/workflows", "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_DESIGN:VIEW,ADMIN.APPROVAL_DESIGN:UPDATE"),
                createDenied, new MockFilterChain());

        MockHttpServletResponse createPermitted = new MockHttpServletResponse();
        filter.doFilter(request(
                "POST", "/v1/admin/workflows", "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_DESIGN:VIEW,ADMIN.APPROVAL_DESIGN:CREATE"),
                createPermitted, new MockFilterChain());

        MockHttpServletResponse updatePermitted = new MockHttpServletResponse();
        filter.doFilter(request(
                "PUT", "/v1/admin/workflows/0fd4362f-1a3f-40b9-8f5a-2791e08e02eb/draft",
                "APPROVAL_DESIGNER", "ADMIN.APPROVAL_DESIGN:UPDATE"),
                updatePermitted, new MockFilterChain());

        assertThat(createDenied.getStatus()).isEqualTo(403);
        assertThat(createPermitted.getStatus()).isEqualTo(200);
        assertThat(updatePermitted.getStatus()).isEqualTo(200);
    }

    @Test
    void reservesTheAdministrationOverviewForApprovalOperations() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter("trusted", objectMapper);
        MockHttpServletRequest designer = request(
                "GET",
                "/v1/admin/overview",
                "APPROVAL_DESIGNER",
                "ADMIN.APPROVAL_DESIGN:VIEW");
        MockHttpServletResponse denied = new MockHttpServletResponse();

        filter.doFilter(designer, denied, new MockFilterChain());

        MockHttpServletRequest operator = request(
                "GET",
                "/v1/admin/overview",
                "APPROVAL_OPERATOR",
                "ADMIN.APPROVAL_OPERATIONS:VIEW");
        MockHttpServletResponse permitted = new MockHttpServletResponse();

        filter.doFilter(operator, permitted, new MockFilterChain());

        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(permitted.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest request(
            String method,
            String path,
            String roles,
            String permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(ApprovalSecurityFilter.USER_HEADER, "17");
        request.addHeader(ApprovalSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(ApprovalSecurityFilter.ROLES_HEADER, roles);
        request.addHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER, permissions);
        return request;
    }
}
