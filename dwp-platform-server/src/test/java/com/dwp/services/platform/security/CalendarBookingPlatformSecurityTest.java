package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarBookingPlatformSecurityTest {

    private static final String PERSON = "123e4567-e89b-42d3-a456-426614174000";
    private static final String PAGE = "223e4567-e89b-42d3-a456-426614174000";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void authenticatedBookingRoutesRequireExactPersonAndCalendarViewAuthority()
            throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", json);
        List<Route> routes = List.of(
                new Route("GET", "/v1/calendar/booking/pages/" + PAGE + "/bookable"),
                new Route("GET", "/v1/calendar/booking/pages/" + PAGE + "/availability"),
                new Route("POST", "/v1/calendar/booking/pages/" + PAGE + "/holds"),
                new Route("POST", "/v1/calendar/booking/pages/" + PAGE + "/appointments"),
                new Route("GET", "/v1/calendar/booking/appointments"));

        for (Route route : routes) {
            assertThat(filter(filter, request(route, "APP.CALENDAR:VIEW", true)).getStatus())
                    .as(route.path()).isEqualTo(200);
            assertThat(filter(filter, request(route, "APP.CALENDAR:CREATE", true)).getStatus())
                    .as(route.path()).isEqualTo(403);
            assertThat(filter(filter, request(route, "APP.CALENDAR:VIEW", false)).getStatus())
                    .as(route.path()).isEqualTo(401);
        }
    }

    @Test
    void bookingRoutesRejectRuntimeIdentityDuplicateEvidenceAndPathAliases()
            throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", json);
        Route route = new Route("GET", "/v1/calendar/booking/appointments");

        MockHttpServletRequest runtime = request(route, "APP.CALENDAR:VIEW", true);
        runtime.removeHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER);
        runtime.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        assertThat(filter(filter, runtime).getStatus()).isEqualTo(401);

        MockHttpServletRequest duplicate = request(route, "APP.CALENDAR:VIEW", true);
        duplicate.addHeader("X-DWP-Person-Public-ID", PERSON);
        assertThat(filter(filter, duplicate).getStatus()).isEqualTo(401);

        Route aliased = new Route("GET", "/v1/calendar/booking/%61ppointments");
        assertThat(filter(filter, request(aliased, "APP.CALENDAR:VIEW", true)).getStatus())
                .isEqualTo(403);
    }

    private MockHttpServletRequest request(Route route, String permission, boolean person) {
        MockHttpServletRequest request = new MockHttpServletRequest(route.method(), route.path());
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        if (person) request.addHeader("X-DWP-Person-Public-ID", PERSON);
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, permission);
        return request;
    }

    private MockHttpServletResponse filter(
            PlatformSecurityFilter filter,
            MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private record Route(String method, String path) { }
}
