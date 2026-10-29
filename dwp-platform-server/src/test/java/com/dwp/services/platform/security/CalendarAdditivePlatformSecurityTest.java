package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarAdditivePlatformSecurityTest {

    private static final String UUID_VALUE =
            "123e4567-e89b-42d3-a456-426614174000";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void v2EventsRequiresCanonicalTenantUserPersonAndCalendarView() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", mapper);

        MockHttpServletResponse allowed = filter(filter, request(
                "GET", "/v2/calendar/events", "APP.CALENDAR:VIEW", true));
        assertThat(allowed.getStatus()).isEqualTo(200);

        MockHttpServletResponse wrongAuthority = filter(filter, request(
                "GET", "/v2/calendar/events", "APP.CALENDAR:CREATE", true));
        assertThat(wrongAuthority.getStatus()).isEqualTo(403);

        MockHttpServletResponse missingPerson = filter(filter, request(
                "GET", "/v2/calendar/events", "APP.CALENDAR:VIEW", false));
        assertThat(missingPerson.getStatus()).isEqualTo(401);
    }

    @Test
    void personalReminderPutUsesViewAuthorityButStillRequiresExactIdentity() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", mapper);
        String path = "/v1/calendar/events/" + UUID_VALUE + "/reminders";

        MockHttpServletResponse allowed = filter(filter, request(
                "PUT", path, "APP.CALENDAR:VIEW", true));
        assertThat(allowed.getStatus()).isEqualTo(200);

        MockHttpServletResponse wrongAuthority = filter(filter, request(
                "PUT", path, "APP.CALENDAR:CREATE", true));
        assertThat(wrongAuthority.getStatus()).isEqualTo(403);

        MockHttpServletResponse missingPerson = filter(filter, request(
                "PUT", path, "APP.CALENDAR:VIEW", false));
        assertThat(missingPerson.getStatus()).isEqualTo(401);

        MockHttpServletResponse nonCanonical = filter(filter, request(
                "PUT",
                "/v1/calendar/events/123E4567-E89B-42D3-A456-426614174000/reminders",
                "APP.CALENDAR:VIEW",
                true));
        assertThat(nonCanonical.getStatus()).isEqualTo(403);
    }

    private MockHttpServletRequest request(
            String method,
            String path,
            String permission,
            boolean person) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        if (person) request.addHeader("X-DWP-Person-Public-ID", UUID_VALUE);
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
}
