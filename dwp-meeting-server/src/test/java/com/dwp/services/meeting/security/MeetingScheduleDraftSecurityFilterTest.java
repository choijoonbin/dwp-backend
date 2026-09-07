package com.dwp.services.meeting.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingScheduleDraftSecurityFilterTest {

    private final MeetingSecurityFilter filter = new MeetingSecurityFilter(
            "trusted-token", new ObjectMapper().findAndRegisterModules());

    @AfterEach
    void clearContext() {
        MeetingRequestContext.clear();
    }

    @Test
    void scheduleDraftRoutesRequireCreateOrManageEvenForReads() throws Exception {
        for (String permission : List.of("APP.MEETINGS:CREATE", "APP.MEETINGS:MANAGE")) {
            for (Route route : List.of(
                    new Route("GET", "/v1/schedule-draft"),
                    new Route("PUT", "/v1/schedule-draft"),
                    new Route("POST", "/v1/schedule-draft/recurrence-preview"),
                    new Route("POST", "/v1/schedule-draft/commit"),
                    new Route("POST", "/v1/schedule-draft/discard"))) {
                AtomicBoolean invoked = new AtomicBoolean();
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(request(route, permission, "WORKSPACE_MEMBER"), response,
                        (servletRequest, servletResponse) -> invoked.set(true));
                assertThat(invoked).as(route.path()).isTrue();
            }
        }
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(request(new Route("GET", "/v1/schedule-draft"),
                        "APP.MEETINGS:VIEW", "WORKSPACE_MEMBER"),
                denied, (servletRequest, servletResponse) -> { });
        assertThat(denied.getStatus()).isEqualTo(403);
    }

    @Test
    void selfPreparationUsesViewButWorkspaceRoutesRejectSupportIdentity() throws Exception {
        Route preparation = new Route("PUT", "/v1/meetings/"
                + "11111111-1111-1111-1111-111111111111/my-preparation");
        Route uppercasePreparation = new Route("PUT", "/v1/meetings/"
                + "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA/my-preparation");
        for (Route route : List.of(preparation, uppercasePreparation)) {
            AtomicBoolean invoked = new AtomicBoolean();
            filter.doFilter(request(route, "APP.MEETINGS:VIEW", "WORKSPACE_MEMBER"),
                    new MockHttpServletResponse(),
                    (servletRequest, servletResponse) -> invoked.set(true));
            assertThat(invoked).as(route.path()).isTrue();
        }

        for (Route route : List.of(preparation, uppercasePreparation,
                new Route("GET", "/v1/schedule-draft"))) {
            MockHttpServletResponse denied = new MockHttpServletResponse();
            filter.doFilter(request(route, "APP.MEETINGS:VIEW,APP.MEETINGS:CREATE",
                    "PROVIDER_SUPPORT"), denied,
                    (servletRequest, servletResponse) -> { });
            assertThat(denied.getStatus()).as(route.path()).isEqualTo(403);
        }
    }

    private MockHttpServletRequest request(Route route, String permission, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest(route.method(), route.path());
        request.setRequestURI(route.path());
        request.addHeader(MeetingSecurityFilter.SERVICE_TOKEN, "trusted-token");
        request.addHeader(MeetingSecurityFilter.USER, "101");
        request.addHeader(MeetingSecurityFilter.TENANT, "77");
        request.addHeader(MeetingSecurityFilter.ROLES, role);
        request.addHeader(MeetingSecurityFilter.PERMISSIONS, permission);
        return request;
    }

    private record Route(String method, String path) {
    }
}
