package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformSecurityFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void rejectsDirectRequestsWithoutGatewayServiceIdentity() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/reference-sets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("E2000");
    }

    @Test
    void requiresAnAdministratorRoleForTheAdminSurface() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/reference-sets");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "EMPLOYEE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("E2001");
    }

    @Test
    void acceptsVerifiedTenantAdministratorsAndClearsTheActorContext() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/reference-sets");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "EMPLOYEE,TENANT_ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(RequestActorContext.current()).isEmpty();
    }

    @Test
    void enforcesSeparatedCommunicationsEditorAndPublisherAuthorities() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest editorCreate = new MockHttpServletRequest(
                "POST", "/v1/admin/announcements");
        editorCreate.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        editorCreate.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        editorCreate.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        editorCreate.addHeader(PlatformSecurityFilter.ROLES_HEADER, "COMMUNICATIONS_EDITOR");
        editorCreate.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.COMMUNICATIONS:CREATE");
        MockHttpServletResponse editorCreateResponse = new MockHttpServletResponse();

        filter.doFilter(editorCreate, editorCreateResponse, new MockFilterChain());

        assertThat(editorCreateResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest editorPublish = new MockHttpServletRequest(
                "POST", "/v1/admin/announcements/91/publish");
        editorPublish.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        editorPublish.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        editorPublish.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        editorPublish.addHeader(PlatformSecurityFilter.ROLES_HEADER, "COMMUNICATIONS_EDITOR");
        editorPublish.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.COMMUNICATIONS:CREATE,ADMIN.COMMUNICATIONS:UPDATE");
        MockHttpServletResponse editorPublishResponse = new MockHttpServletResponse();

        filter.doFilter(editorPublish, editorPublishResponse, new MockFilterChain());

        assertThat(editorPublishResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest publisherPublish = new MockHttpServletRequest(
                "POST", "/v1/admin/announcements/91/publish");
        publisherPublish.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        publisherPublish.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
        publisherPublish.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        publisherPublish.addHeader(
                PlatformSecurityFilter.ROLES_HEADER, "COMMUNICATIONS_PUBLISHER");
        publisherPublish.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.COMMUNICATIONS:APPROVE");
        MockHttpServletResponse publisherPublishResponse = new MockHttpServletResponse();

        filter.doFilter(publisherPublish, publisherPublishResponse, new MockFilterChain());

        assertThat(publisherPublishResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void requiresTheCommunicationsApplicationEntitlementForTheReaderApi() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest denied = request("/v1/communications");
        denied.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        denied.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        denied.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        denied.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

        filter.doFilter(denied, deniedResponse, new MockFilterChain());

        assertThat(deniedResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest allowed = request("/v1/communications");
        allowed.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        allowed.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        allowed.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        allowed.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        allowed.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.COMMUNICATIONS:VIEW");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        filter.doFilter(allowed, allowedResponse, new MockFilterChain());

        assertThat(allowedResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void separatesCalendarReadAndCreatePermissions() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest read = request("/v1/calendar/events");
        read.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        read.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        read.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        read.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        read.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.CALENDAR:VIEW");
        MockHttpServletResponse readResponse = new MockHttpServletResponse();

        filter.doFilter(read, readResponse, new MockFilterChain());

        assertThat(readResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest create = new MockHttpServletRequest("POST", "/v1/calendar/events");
        create.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        create.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        create.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        create.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        create.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.CALENDAR:VIEW");
        MockHttpServletResponse createResponse = new MockHttpServletResponse();

        filter.doFilter(create, createResponse, new MockFilterChain());

        assertThat(createResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void requiresCalendarManagePermissionForBookingDecisions() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest denied = new MockHttpServletRequest(
                "POST", "/v1/admin/calendar/bookings/a/decision");
        denied.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        denied.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        denied.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        denied.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        denied.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.CALENDAR:VIEW");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

        filter.doFilter(denied, deniedResponse, new MockFilterChain());

        assertThat(deniedResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest allowed = new MockHttpServletRequest(
                "POST", "/v1/admin/calendar/bookings/a/decision");
        allowed.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        allowed.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
        allowed.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        allowed.addHeader(PlatformSecurityFilter.ROLES_HEADER, "CALENDAR_ADMIN");
        allowed.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.CALENDAR:MANAGE");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        filter.doFilter(allowed, allowedResponse, new MockFilterChain());

        assertThat(allowedResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void isolatesRoomAvailabilityBehindItsApplicationPermission() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest denied = request("/v1/rooms/availability");
        denied.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        denied.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        denied.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        denied.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        denied.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.CALENDAR:VIEW");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

        filter.doFilter(denied, deniedResponse, new MockFilterChain());

        assertThat(deniedResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest allowed = request("/v1/rooms/availability");
        allowed.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        allowed.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        allowed.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        allowed.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        allowed.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.ROOMS:VIEW");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        filter.doFilter(allowed, allowedResponse, new MockFilterChain());

        assertThat(allowedResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void separatesRoomBookingCreationFromBookingUpdates() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest deniedUpdate = new MockHttpServletRequest(
                "POST", "/v1/rooms/bookings/event-1/cancel");
        deniedUpdate.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        deniedUpdate.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        deniedUpdate.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        deniedUpdate.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        deniedUpdate.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.ROOMS:CREATE");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

        filter.doFilter(deniedUpdate, deniedResponse, new MockFilterChain());

        assertThat(deniedResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest allowedUpdate = new MockHttpServletRequest(
                "POST", "/v1/rooms/bookings/event-1/cancel");
        allowedUpdate.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        allowedUpdate.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        allowedUpdate.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        allowedUpdate.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        allowedUpdate.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.ROOMS:UPDATE");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        filter.doFilter(allowedUpdate, allowedResponse, new MockFilterChain());

        assertThat(allowedResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void isolatesRoomAdministrationFromCalendarAdministration() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest denied = request("/v1/admin/rooms/overview");
        denied.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        denied.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        denied.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        denied.addHeader(PlatformSecurityFilter.ROLES_HEADER, "CALENDAR_ADMIN");
        denied.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.CALENDAR:VIEW");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

        filter.doFilter(denied, deniedResponse, new MockFilterChain());

        assertThat(deniedResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest allowed = request("/v1/admin/rooms/overview");
        allowed.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        allowed.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
        allowed.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        allowed.addHeader(PlatformSecurityFilter.ROLES_HEADER, "CALENDAR_ADMIN");
        allowed.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.ROOMS:VIEW");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        filter.doFilter(allowed, allowedResponse, new MockFilterChain());

        assertThat(allowedResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void separatesMailUseFromDelegatedMailAdministration() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest inbox = request("/v1/mail/threads");
        inbox.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        inbox.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        inbox.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        inbox.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        inbox.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.MAIL:VIEW");
        MockHttpServletResponse inboxResponse = new MockHttpServletResponse();

        filter.doFilter(inbox, inboxResponse, new MockFilterChain());

        assertThat(inboxResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest deniedPolicy = new MockHttpServletRequest(
                "PUT", "/v1/admin/mail/policy");
        deniedPolicy.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        deniedPolicy.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        deniedPolicy.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        deniedPolicy.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        deniedPolicy.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.MAIL:VIEW");
        MockHttpServletResponse deniedPolicyResponse = new MockHttpServletResponse();

        filter.doFilter(deniedPolicy, deniedPolicyResponse, new MockFilterChain());

        assertThat(deniedPolicyResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest allowedPolicy = new MockHttpServletRequest(
                "PUT", "/v1/admin/mail/policy");
        allowedPolicy.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        allowedPolicy.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
        allowedPolicy.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        allowedPolicy.addHeader(PlatformSecurityFilter.ROLES_HEADER, "MAIL_ADMIN");
        allowedPolicy.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.MAIL:MANAGE");
        MockHttpServletResponse allowedPolicyResponse = new MockHttpServletResponse();

        filter.doFilter(allowedPolicy, allowedPolicyResponse, new MockFilterChain());

        assertThat(allowedPolicyResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsScopedAppApproversOnlyOnTheAppAccessRequestSurface() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest allowed = request("/v1/admin/app-access-requests");
        allowed.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        allowed.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        allowed.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        allowed.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        allowed.addHeader(
                PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                "APP_ACCESS_APPROVER@APP.MAIL");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        filter.doFilter(allowed, allowedResponse, new MockFilterChain());

        assertThat(allowedResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest denied = request("/v1/admin/reference-sets");
        denied.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        denied.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        denied.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        denied.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        denied.addHeader(
                PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                "APP_ACCESS_APPROVER@APP.MAIL");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

        filter.doFilter(denied, deniedResponse, new MockFilterChain());

        assertThat(deniedResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void separatesAppAccessDecisionAndFulfillmentResponsibilities() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest managerFulfillment = new MockHttpServletRequest(
                "POST", "/v1/admin/app-access-requests/abc/fulfillment");
        managerFulfillment.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        managerFulfillment.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        managerFulfillment.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        managerFulfillment.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        managerFulfillment.addHeader(
                PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                "APP_ACCESS_MANAGER@APP.MAIL");
        MockHttpServletResponse managerResponse = new MockHttpServletResponse();

        filter.doFilter(managerFulfillment, managerResponse, new MockFilterChain());

        assertThat(managerResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest approverFulfillment = new MockHttpServletRequest(
                "POST", "/v1/admin/app-access-requests/abc/fulfillment");
        approverFulfillment.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        approverFulfillment.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
        approverFulfillment.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        approverFulfillment.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        approverFulfillment.addHeader(
                PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                "APP_ACCESS_APPROVER@APP.MAIL");
        MockHttpServletResponse approverResponse = new MockHttpServletResponse();

        filter.doFilter(approverFulfillment, approverResponse, new MockFilterChain());

        assertThat(approverResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest managerDecision = new MockHttpServletRequest(
                "POST", "/v1/admin/app-access-requests/abc/decision");
        managerDecision.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        managerDecision.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        managerDecision.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        managerDecision.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        managerDecision.addHeader(
                PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                "APP_ACCESS_MANAGER@APP.MAIL");
        MockHttpServletResponse managerDecisionResponse = new MockHttpServletResponse();

        filter.doFilter(managerDecision, managerDecisionResponse, new MockFilterChain());

        assertThat(managerDecisionResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void keepsCatalogAdministratorsReadOnlyOnTheAppAccessQueue() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest queue = request("/v1/admin/app-access-requests");
        queue.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        queue.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        queue.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        queue.addHeader(PlatformSecurityFilter.ROLES_HEADER, "APP_CATALOG_ADMIN");
        MockHttpServletResponse queueResponse = new MockHttpServletResponse();

        filter.doFilter(queue, queueResponse, new MockFilterChain());

        assertThat(queueResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest decision = new MockHttpServletRequest(
                "POST", "/v1/admin/app-access-requests/abc/decision");
        decision.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        decision.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        decision.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        decision.addHeader(PlatformSecurityFilter.ROLES_HEADER, "APP_CATALOG_ADMIN");
        MockHttpServletResponse decisionResponse = new MockHttpServletResponse();

        filter.doFilter(decision, decisionResponse, new MockFilterChain());

        assertThat(decisionResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void doesNotTreatTenantAdministrationAsApplicationAccessResponsibility() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest queue = request("/v1/admin/app-access-requests");
        queue.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        queue.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        queue.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        queue.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(queue, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void acceptsAuditAccessFromScopedPermissionInsteadOfBuiltInRoleName() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/audit-control/overview");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "CUSTOM_AUDITOR");
        request.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.AUDIT_VIEW:VIEW");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(RequestActorContext.current()).isEmpty();
    }

    @Test
    void rejectsAuditAccessWithoutAResolvedPermission() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/audit-control/overview");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "AUDITOR");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void rejectsWorkspaceAccessWithoutAResolvedPermission() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/workspace/work-items");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "PROVIDER_ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Workspace permission is required.");
    }

    @Test
    void acceptsWorkspaceAccessWithAResolvedPermission() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/workspace/work-items");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.WORK:VIEW");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void requiresAResolvedSavedViewCustodyPermissionInsteadOfAnAdministratorLabel()
            throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest denied = request("/v1/admin/saved-view-ownership/orphaned");
        denied.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        denied.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        denied.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        denied.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

        filter.doFilter(denied, deniedResponse, new MockFilterChain());

        assertThat(deniedResponse.getStatus()).isEqualTo(403);
        assertThat(deniedResponse.getContentAsString())
                .contains("Saved view custody permission is required.");

        MockHttpServletRequest allowed = request("/v1/admin/saved-view-ownership/orphaned");
        allowed.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        allowed.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        allowed.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        allowed.addHeader(PlatformSecurityFilter.ROLES_HEADER, "CUSTOM_CUSTODIAN");
        allowed.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.SAVED_VIEW_CUSTODY:VIEW");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        filter.doFilter(allowed, allowedResponse, new MockFilterChain());

        assertThat(allowedResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void acceptsTheRestrictedRuntimeIdentityOnlyForCatalogReferenceAndScopedWorkspaceReads()
            throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest catalogRequest = request("/v1/catalog/registry-entries/AGENT/PLANNER");
        catalogRequest.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        catalogRequest.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        catalogRequest.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        MockHttpServletResponse catalogResponse = new MockHttpServletResponse();

        filter.doFilter(catalogRequest, catalogResponse, new MockFilterChain());

        assertThat(catalogResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest workspaceRequest = request("/v1/workspace/work-items");
        workspaceRequest.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        workspaceRequest.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        workspaceRequest.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        workspaceRequest.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        workspaceRequest.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.WORK:VIEW");
        MockHttpServletResponse workspaceResponse = new MockHttpServletResponse();

        filter.doFilter(workspaceRequest, workspaceResponse, new MockFilterChain());

        assertThat(workspaceResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest mailRequest = request("/v1/mail/threads");
        mailRequest.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        mailRequest.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        mailRequest.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        mailRequest.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        mailRequest.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.MAIL:VIEW");
        MockHttpServletResponse mailResponse = new MockHttpServletResponse();

        filter.doFilter(mailRequest, mailResponse, new MockFilterChain());

        assertThat(mailResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest workspaceWriteRequest = new MockHttpServletRequest(
                "PATCH", "/v1/workspace/work-items/1/status");
        workspaceWriteRequest.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        workspaceWriteRequest.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        workspaceWriteRequest.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        workspaceWriteRequest.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        workspaceWriteRequest.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.WORK:UPDATE");
        MockHttpServletResponse workspaceWriteResponse = new MockHttpServletResponse();

        filter.doFilter(workspaceWriteRequest, workspaceWriteResponse, new MockFilterChain());

        assertThat(workspaceWriteResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest unrelatedWorkspaceRequest = request(
                "/v1/workspace/future-sensitive-resource");
        unrelatedWorkspaceRequest.addHeader(
                PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        unrelatedWorkspaceRequest.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        unrelatedWorkspaceRequest.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        unrelatedWorkspaceRequest.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.WORK:VIEW");
        MockHttpServletResponse unrelatedWorkspaceResponse = new MockHttpServletResponse();

        filter.doFilter(
                unrelatedWorkspaceRequest,
                unrelatedWorkspaceResponse,
                new MockFilterChain());

        assertThat(unrelatedWorkspaceResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest adminRequest = request("/v1/admin/registry-entries");
        adminRequest.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        adminRequest.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        adminRequest.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        adminRequest.addHeader(PlatformSecurityFilter.ROLES_HEADER, "ADMIN");
        MockHttpServletResponse adminResponse = new MockHttpServletResponse();

        filter.doFilter(adminRequest, adminResponse, new MockFilterChain());

        assertThat(adminResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void acceptsOnlyThePlatformResourcesGrantedByAResolvedSupportSession() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/tenant-branding");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "PROVIDER_SUPPORT");
        request.addHeader(PlatformSecurityFilter.SUPPORT_SESSION_HEADER, "session-1");
        request.addHeader(PlatformSecurityFilter.SUPPORT_SCOPES_HEADER, "TENANT_CONFIGURATION_READ");
        request.addHeader(PlatformSecurityFilter.ACTOR_TENANT_HEADER, "3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void preventsReadOnlySupportSessionsFromChangingTenantConfiguration() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/v1/admin/tenant-branding");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "ADMIN,PROVIDER_ADMIN");
        request.addHeader(PlatformSecurityFilter.SUPPORT_SESSION_HEADER, "session-1");
        request.addHeader(PlatformSecurityFilter.SUPPORT_SCOPES_HEADER, "TENANT_CONFIGURATION_READ");
        request.addHeader(PlatformSecurityFilter.ACTOR_TENANT_HEADER, "3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void preventsSupportSessionsFromEnteringUnrelatedAdminSurfaces() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest request = request("/v1/admin/reference-sets");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "ADMIN,PROVIDER_ADMIN");
        request.addHeader(PlatformSecurityFilter.SUPPORT_SESSION_HEADER, "session-1");
        request.addHeader(PlatformSecurityFilter.SUPPORT_SCOPES_HEADER, "TENANT_CONFIGURATION_WRITE");
        request.addHeader(PlatformSecurityFilter.ACTOR_TENANT_HEADER, "3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void requiresEmployeeServicesEntitlementForTheServiceCenter() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest denied = request("/v1/services/catalog");
        denied.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        denied.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        denied.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        denied.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

        filter.doFilter(denied, deniedResponse, new MockFilterChain());

        assertThat(deniedResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest allowed = request("/v1/services/catalog");
        allowed.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        allowed.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        allowed.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        allowed.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        allowed.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "APP.EMPLOYEE_SERVICES:VIEW");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        filter.doFilter(allowed, allowedResponse, new MockFilterChain());

        assertThat(allowedResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void separatesServiceCatalogDesignFromRequestOperations() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest catalogWrite = new MockHttpServletRequest(
                "PUT", "/v1/admin/services/catalog/technology.account-help");
        catalogWrite.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        catalogWrite.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        catalogWrite.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        catalogWrite.addHeader(PlatformSecurityFilter.ROLES_HEADER, "SERVICE_CATALOG_MANAGER");
        catalogWrite.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.SERVICE_CATALOG:UPDATE");
        MockHttpServletResponse catalogResponse = new MockHttpServletResponse();

        filter.doFilter(catalogWrite, catalogResponse, new MockFilterChain());

        assertThat(catalogResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest catalogOnOperations = new MockHttpServletRequest(
                "POST", "/v1/admin/services/requests/abc/transition");
        catalogOnOperations.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        catalogOnOperations.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        catalogOnOperations.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        catalogOnOperations.addHeader(
                PlatformSecurityFilter.ROLES_HEADER,
                "SERVICE_CATALOG_MANAGER");
        catalogOnOperations.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.SERVICE_CATALOG:UPDATE");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

        filter.doFilter(catalogOnOperations, deniedResponse, new MockFilterChain());

        assertThat(deniedResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest agentTransition = new MockHttpServletRequest(
                "POST", "/v1/admin/services/requests/abc/transition");
        agentTransition.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        agentTransition.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
        agentTransition.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        agentTransition.addHeader(PlatformSecurityFilter.ROLES_HEADER, "SERVICE_AGENT");
        agentTransition.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.SERVICE_OPERATIONS:MANAGE");
        MockHttpServletResponse agentResponse = new MockHttpServletResponse();

        filter.doFilter(agentTransition, agentResponse, new MockFilterChain());

        assertThat(agentResponse.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }
}
