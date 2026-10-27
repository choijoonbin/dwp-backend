package com.dwp.services.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformSecurityFilterTest extends PlatformAdministrationSecurityEvidence {

    @Test
    void separatesDwaionAgentEditingFromPublishing() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest deniedPublish = new MockHttpServletRequest(
                "POST", "/v1/admin/dwaion/agents/DWP_ASSISTANT/revisions/2/activate");
        deniedPublish.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        deniedPublish.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        deniedPublish.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        deniedPublish.addHeader(PlatformSecurityFilter.ROLES_HEADER, "DWAION_ADMIN");
        deniedPublish.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.DWAION_AGENTS:UPDATE");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

        filter.doFilter(deniedPublish, deniedResponse, new MockFilterChain());

        assertThat(deniedResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest allowedPublish = new MockHttpServletRequest(
                "POST", "/v1/admin/dwaion/agents/DWP_ASSISTANT/revisions/2/activate");
        allowedPublish.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        allowedPublish.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
        allowedPublish.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        allowedPublish.addHeader(PlatformSecurityFilter.ROLES_HEADER, "DWAION_ADMIN");
        allowedPublish.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.DWAION_AGENTS:APPROVE");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        filter.doFilter(allowedPublish, allowedResponse, new MockFilterChain());

        assertThat(allowedResponse.getStatus()).isEqualTo(200);
    }

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
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        request.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.REFERENCE_DATA:VIEW");
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
        legacyProduct(editorCreate);
        editorCreate.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        editorCreate.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        editorCreate.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        editorCreate.addHeader(PlatformSecurityFilter.ROLES_HEADER, "COMMUNICATIONS_EDITOR");
        editorCreate.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.COMMUNICATIONS:CREATE");
        editorCreate.addHeader(
                PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                "APP_CONFIG_ADMIN@RS_COMMUNICATIONS");
        MockHttpServletResponse editorCreateResponse = new MockHttpServletResponse();

        filter.doFilter(editorCreate, editorCreateResponse, new MockFilterChain());

        assertThat(editorCreateResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest editorPublish = new MockHttpServletRequest(
                "POST", "/v1/admin/announcements/91/publish");
        legacyProduct(editorPublish);
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
        legacyProduct(publisherPublish);
        publisherPublish.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        publisherPublish.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
        publisherPublish.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        publisherPublish.addHeader(
                PlatformSecurityFilter.ROLES_HEADER, "COMMUNICATIONS_PUBLISHER");
        publisherPublish.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.COMMUNICATIONS:APPROVE");
        publisherPublish.addHeader(
                PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                "APP_CONFIG_ADMIN@RS_COMMUNICATIONS");
        MockHttpServletResponse publisherPublishResponse = new MockHttpServletResponse();

        filter.doFilter(publisherPublish, publisherPublishResponse, new MockFilterChain());

        assertThat(publisherPublishResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void requiresTheCommunicationsApplicationEntitlementForTheReaderApi() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest denied = request("/v1/communications");
        legacyProduct(denied);
        denied.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        denied.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        denied.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        denied.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

        filter.doFilter(denied, deniedResponse, new MockFilterChain());

        assertThat(deniedResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest allowed = request("/v1/communications");
        legacyProduct(allowed);
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
    void limitsTheAgentRuntimeIdentityToTheExactCalendarReadEndpoint() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest allowed = request("/v1/calendar/events");
        allowed.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        allowed.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        allowed.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        allowed.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        allowed.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.CALENDAR:VIEW");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

        filter.doFilter(allowed, allowedResponse, new MockFilterChain());

        assertThat(allowedResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest deniedWrite = new MockHttpServletRequest(
                "POST", "/v1/calendar/events");
        deniedWrite.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        deniedWrite.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        deniedWrite.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        deniedWrite.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.CALENDAR:CREATE");
        MockHttpServletResponse deniedWriteResponse = new MockHttpServletResponse();

        filter.doFilter(deniedWrite, deniedWriteResponse, new MockFilterChain());

        assertThat(deniedWriteResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest deniedBroaderRead = request("/v1/calendar/calendars");
        deniedBroaderRead.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        deniedBroaderRead.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        deniedBroaderRead.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        deniedBroaderRead.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.CALENDAR:VIEW");
        MockHttpServletResponse deniedBroaderReadResponse = new MockHttpServletResponse();

        filter.doFilter(deniedBroaderRead, deniedBroaderReadResponse, new MockFilterChain());

        assertThat(deniedBroaderReadResponse.getStatus()).isEqualTo(401);
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
    void requiresCalendarManagePermissionForCompanyEventTrashAndRestore() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        for (String suffix : new String[]{"trash", "restore"}) {
            String path = "/v1/admin/calendar/company-calendars/calendar-1/events/event-1/" + suffix;
            MockHttpServletRequest denied = new MockHttpServletRequest("POST", path);
            denied.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
            denied.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
            denied.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
            denied.addHeader(PlatformSecurityFilter.ROLES_HEADER, "CALENDAR_ADMIN");
            denied.addHeader(
                    PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.CALENDAR:CREATE");
            MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

            filter.doFilter(denied, deniedResponse, new MockFilterChain());

            assertThat(deniedResponse.getStatus()).isEqualTo(403);

            MockHttpServletRequest allowed = new MockHttpServletRequest("POST", path);
            allowed.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
            allowed.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
            allowed.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
            allowed.addHeader(PlatformSecurityFilter.ROLES_HEADER, "CALENDAR_ADMIN");
            allowed.addHeader(
                    PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.CALENDAR:MANAGE");
            MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

            filter.doFilter(allowed, allowedResponse, new MockFilterChain());

            assertThat(allowedResponse.getStatus()).isEqualTo(200);
        }
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
    void acceptsExactWorkplaceRoomContractsWithWorkplacePermissions() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        List<WorkplaceRoomRequest> requests = List.of(
                new WorkplaceRoomRequest(
                        "GET", "/v1/rooms/policy",
                        "route.workplace.work.room-policy-get.data", "VIEW"),
                new WorkplaceRoomRequest(
                        "GET", "/v1/rooms/availability",
                        "route.workplace.work.room-availability-get.data", "VIEW"),
                new WorkplaceRoomRequest(
                        "GET", "/v1/rooms/bookings",
                        "route.workplace.work.reservations.page", "VIEW"),
                new WorkplaceRoomRequest(
                        "POST", "/v1/rooms/bookings",
                        "route.workplace.work.room-bookings-post.action", "CREATE"),
                new WorkplaceRoomRequest(
                        "PUT", "/v1/rooms/bookings/event-1",
                        "route.workplace.work.room-booking-by-event-id-put.action", "UPDATE"),
                new WorkplaceRoomRequest(
                        "POST", "/v1/rooms/bookings/event-1/cancel",
                        "route.workplace.work.room-booking-cancel-post.action", "UPDATE"),
                new WorkplaceRoomRequest(
                        "POST", "/v1/rooms/bookings/event-1/response",
                        "route.workplace.work.room-booking-response-post.action", "UPDATE"));

        for (WorkplaceRoomRequest requestCase : requests) {
            MockHttpServletRequest allowed = new MockHttpServletRequest(
                    requestCase.method(), requestCase.path());
            allowed.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
            allowed.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
            allowed.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
            allowed.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
            allowed.addHeader(
                    PlatformSecurityFilter.PERMISSIONS_HEADER,
                    "APP.WORKPLACE:" + requestCase.permission());
            allowed.addHeader(
                    PlatformSecurityFilter.ROUTE_CONTRACT_HEADER,
                    requestCase.routeContract());

            MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
            filter.doFilter(allowed, allowedResponse, new MockFilterChain());

            assertThat(allowedResponse.getStatus())
                    .as(requestCase.method() + " " + requestCase.path())
                    .isEqualTo(200);

            MockHttpServletRequest legacyRoomsOnly = new MockHttpServletRequest(
                    requestCase.method(), requestCase.path());
            legacyRoomsOnly.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
            legacyRoomsOnly.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
            legacyRoomsOnly.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
            legacyRoomsOnly.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
            legacyRoomsOnly.addHeader(
                    PlatformSecurityFilter.PERMISSIONS_HEADER,
                    "APP.ROOMS:" + requestCase.permission());
            legacyRoomsOnly.addHeader(
                    PlatformSecurityFilter.ROUTE_CONTRACT_HEADER,
                    requestCase.routeContract());

            MockHttpServletResponse legacyRoomsOnlyResponse = new MockHttpServletResponse();
            filter.doFilter(legacyRoomsOnly, legacyRoomsOnlyResponse, new MockFilterChain());

            assertThat(legacyRoomsOnlyResponse.getStatus())
                    .as("legacy APP.ROOMS-only for "
                            + requestCase.method() + " " + requestCase.path())
                    .isEqualTo(403);

            MockHttpServletRequest coexistence = new MockHttpServletRequest(
                    requestCase.method(), requestCase.path());
            coexistence.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
            coexistence.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
            coexistence.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
            coexistence.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
            coexistence.addHeader(
                    PlatformSecurityFilter.PERMISSIONS_HEADER,
                    "APP.WORKPLACE:" + requestCase.permission()
                            + ",APP.ROOMS:" + requestCase.permission());
            coexistence.addHeader(
                    PlatformSecurityFilter.ROUTE_CONTRACT_HEADER,
                    requestCase.routeContract());

            MockHttpServletResponse coexistenceResponse = new MockHttpServletResponse();
            filter.doFilter(coexistence, coexistenceResponse, new MockFilterChain());

            assertThat(coexistenceResponse.getStatus())
                    .as("coexisting grants for "
                            + requestCase.method() + " " + requestCase.path())
                    .isEqualTo(200);

            MockHttpServletRequest wrongContract = new MockHttpServletRequest(
                    requestCase.method(), requestCase.path());
            wrongContract.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
            wrongContract.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
            wrongContract.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
            wrongContract.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
            wrongContract.addHeader(
                    PlatformSecurityFilter.PERMISSIONS_HEADER,
                    "APP.WORKPLACE:" + requestCase.permission());
            wrongContract.addHeader(
                    PlatformSecurityFilter.ROUTE_CONTRACT_HEADER,
                    requestCase.routeContract() + ".wrong");

            MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
            filter.doFilter(wrongContract, deniedResponse, new MockFilterChain());

            assertThat(deniedResponse.getStatus())
                    .as("wrong contract for " + requestCase.method() + " " + requestCase.path())
                    .isEqualTo(403);
        }
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

    private record WorkplaceRoomRequest(
            String method,
            String path,
            String routeContract,
            String permission) {
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
    void separatesWorkplaceUseFromWorkplaceAdministration() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        MockHttpServletRequest runtime = request("/v1/workplace/explore");
        runtime.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        runtime.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        runtime.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        runtime.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        runtime.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.WORKPLACE:VIEW");
        MockHttpServletResponse runtimeResponse = new MockHttpServletResponse();

        filter.doFilter(runtime, runtimeResponse, new MockFilterChain());

        assertThat(runtimeResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest denied = new MockHttpServletRequest(
                "PUT", "/v1/admin/workplace/policy");
        denied.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        denied.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        denied.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        denied.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        denied.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "APP.WORKPLACE:UPDATE");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

        filter.doFilter(denied, deniedResponse, new MockFilterChain());

        assertThat(deniedResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest admin = new MockHttpServletRequest(
                "PUT", "/v1/admin/workplace/policy");
        admin.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        admin.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
        admin.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        admin.addHeader(PlatformSecurityFilter.ROLES_HEADER, "CALENDAR_ADMIN");
        admin.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.WORKPLACE:MANAGE");
        MockHttpServletResponse adminResponse = new MockHttpServletResponse();

        filter.doFilter(admin, adminResponse, new MockFilterChain());

        assertThat(adminResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void spacePlanningBoardReportsRequireTheDedicatedExportPermission() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);
        String commandId = "123e4567-e89b-42d3-a456-426614174000";
        List<String[]> routes = List.of(
                new String[]{"POST", "/v1/admin/workplace/space-planning/reports:preview"},
                new String[]{"POST", "/v1/admin/workplace/space-planning/reports"},
                new String[]{"GET", "/v1/admin/workplace/space-planning/reports/" + commandId},
                new String[]{"GET", "/v1/admin/workplace/space-planning/reports/"
                        + commandId + "/content"});

        for (String[] route : routes) {
            MockHttpServletRequest allowed = new MockHttpServletRequest(route[0], route[1]);
            allowed.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
            allowed.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
            allowed.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
            allowed.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
            allowed.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                    "ADMIN.WORKPLACE:EXPORT");
            MockHttpServletResponse allowedResponse = new MockHttpServletResponse();

            filter.doFilter(allowed, allowedResponse, new MockFilterChain());

            assertThat(allowedResponse.getStatus()).as(route[0] + " " + route[1])
                    .isEqualTo(200);

            MockHttpServletRequest denied = new MockHttpServletRequest(route[0], route[1]);
            denied.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
            denied.addHeader(PlatformSecurityFilter.USER_HEADER, "19");
            denied.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
            denied.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
            denied.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                    "ADMIN.WORKPLACE:MANAGE");
            MockHttpServletResponse deniedResponse = new MockHttpServletResponse();

            filter.doFilter(denied, deniedResponse, new MockFilterChain());

            assertThat(deniedResponse.getStatus()).as(route[0] + " " + route[1])
                    .isEqualTo(403);
        }
    }

    @Test
    void workplaceLegalHoldRequiresManagePermission() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        String path = "/v1/admin/workplace/bookings/"
                + "00000000-0000-0000-0000-000000000001/legal-hold";
        MockHttpServletRequest updateOnly = new MockHttpServletRequest("PUT", path);
        updateOnly.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        updateOnly.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        updateOnly.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        updateOnly.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        updateOnly.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.WORKPLACE:UPDATE");
        MockHttpServletResponse denied = new MockHttpServletResponse();

        filter.doFilter(updateOnly, denied, new MockFilterChain());

        assertThat(denied.getStatus()).isEqualTo(403);

        MockHttpServletRequest manager = new MockHttpServletRequest("PUT", path);
        manager.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        manager.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
        manager.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        manager.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        manager.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.WORKPLACE:MANAGE");
        MockHttpServletResponse allowed = new MockHttpServletResponse();

        filter.doFilter(manager, allowed, new MockFilterChain());

        assertThat(allowed.getStatus()).isEqualTo(200);
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
        allowedPolicy.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.MAIL:POLICY_MANAGE");
        MockHttpServletResponse allowedPolicyResponse = new MockHttpServletResponse();

        filter.doFilter(allowedPolicy, allowedPolicyResponse, new MockFilterChain());

        assertThat(allowedPolicyResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void separatesPurposeBoundMailAdministrationCapabilities() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);
        String[][] cases = {
                {"PUT", "/v1/admin/mail/connections/00000000-0000-0000-0000-000000000001", "CONNECTION_MANAGE"},
                {"POST", "/v1/admin/mail/shared-inboxes/00000000-0000-0000-0000-000000000001/members", "SHARED_INBOX_MANAGE"},
                {"POST", "/v1/admin/mail/retention/holds", "HOLD_MANAGE"},
                {"PUT", "/v1/admin/mail/retention/holds/00000000-0000-0000-0000-000000000001", "HOLD_MANAGE"},
                {"POST", "/v1/admin/mail/retention/holds/00000000-0000-0000-0000-000000000001/release-previews", "HOLD_MANAGE"},
                {"GET", "/v1/admin/mail/retention/hold-release-previews/00000000-0000-0000-0000-000000000002", "HOLD_MANAGE"},
                {"POST", "/v1/admin/mail/retention/hold-release-previews/00000000-0000-0000-0000-000000000002/approvals", "HOLD_MANAGE"},
                {"POST", "/v1/admin/mail/retention/hold-release-previews/00000000-0000-0000-0000-000000000002/execute", "HOLD_MANAGE"},
                {"POST", "/v1/admin/mail/retention/purge-previews", "PURGE_PREVIEW"},
                {"POST", "/v1/admin/mail/retention/purges/00000000-0000-0000-0000-000000000001/execute", "PURGE_EXECUTE"},
                {"POST", "/v1/admin/mail/delivery-audit/00000000-0000-0000-0000-000000000001/retry", "DELIVERY_RETRY"},
                {"POST", "/v1/admin/mail/delivery-audit/exports", "EVIDENCE_EXPORT"},
                {"POST", "/v1/admin/mail/writing-assets/SIGNATURE/drafts", "WRITING_ASSET_EDIT"},
                {"POST", "/v1/admin/mail/writing-assets/SIGNATURE/00000000-0000-0000-0000-000000000001/submit", "WRITING_ASSET_SUBMIT"},
                {"POST", "/v1/admin/mail/writing-assets/SIGNATURE/00000000-0000-0000-0000-000000000001/approve", "WRITING_ASSET_APPROVE"},
                {"POST", "/v1/admin/mail/writing-assets/SIGNATURE/00000000-0000-0000-0000-000000000001/publish", "WRITING_ASSET_PUBLISH"},
                {"POST", "/v1/admin/mail/writing-assets/SIGNATURE/00000000-0000-0000-0000-000000000001/retire", "WRITING_ASSET_RETIRE"}
        };

        for (String[] value : cases) {
            MockHttpServletRequest denied = mailAdminRequest(
                    value[0], value[1], "ADMIN.MAIL:MANAGE");
            MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
            filter.doFilter(denied, deniedResponse, new MockFilterChain());
            assertThat(deniedResponse.getStatus())
                    .as("generic MANAGE must not authorize %s %s", value[0], value[1])
                    .isEqualTo(403);

            MockHttpServletRequest allowed = mailAdminRequest(
                    value[0], value[1], "ADMIN.MAIL:" + value[2]);
            MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
            filter.doFilter(allowed, allowedResponse, new MockFilterChain());
            assertThat(allowedResponse.getStatus())
                    .as("%s must authorize %s %s", value[2], value[0], value[1])
                    .isEqualTo(200);
        }
    }

    @Test
    void allowsOnlyPurposeBoundOperatorsToReadRedactedMailRecoveryCandidates()
            throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);
        String[] purgeAuthorities = {
                "PURGE_PREVIEW", "PURGE_AUTHORIZE", "PURGE_EXECUTE"
        };
        for (String authority : purgeAuthorities) {
            MockHttpServletRequest preview = mailAdminRequest(
                    "GET", "/v1/admin/mail/retention/purge-previews",
                    "ADMIN.MAIL:" + authority);
            MockHttpServletResponse previewResponse = new MockHttpServletResponse();
            filter.doFilter(preview, previewResponse, new MockFilterChain());
            assertThat(previewResponse.getStatus()).as(authority).isEqualTo(200);

            MockHttpServletRequest job = mailAdminRequest(
                    "GET", "/v1/admin/mail/retention/purge-jobs/"
                            + "00000000-0000-0000-0000-000000000001",
                    "ADMIN.MAIL:" + authority);
            MockHttpServletResponse jobResponse = new MockHttpServletResponse();
            filter.doFilter(job, jobResponse, new MockFilterChain());
            assertThat(jobResponse.getStatus()).as(authority).isEqualTo(200);
        }

        for (String authority : new String[]{
                "AUDIT_READ", "DELIVERY_RECONCILE", "DELIVERY_RETRY", "DELIVERY_CANCEL"}) {
            MockHttpServletRequest audit = mailAdminRequest(
                    "GET", "/v1/admin/mail/delivery-audit", "ADMIN.MAIL:" + authority);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(audit, response, new MockFilterChain());
            assertThat(response.getStatus()).as(authority).isEqualTo(200);
        }

        MockHttpServletRequest denied = mailAdminRequest(
                "GET", "/v1/admin/mail/delivery-audit", "ADMIN.MAIL:VIEW");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        filter.doFilter(denied, deniedResponse, new MockFilterChain());
        assertThat(deniedResponse.getStatus()).isEqualTo(403);
    }

}
