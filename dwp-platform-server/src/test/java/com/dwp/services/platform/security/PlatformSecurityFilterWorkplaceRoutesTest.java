package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformSecurityFilterWorkplaceRoutesTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void workplaceConnectorReplayRequiresManagePermission() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter("trusted", "runtime", objectMapper);
        String path = "/v1/admin/workplace/connectors/CALENDAR/replays";
        MockHttpServletRequest createOnly = new MockHttpServletRequest("POST", path);
        createOnly.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        createOnly.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        createOnly.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        createOnly.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        createOnly.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.WORKPLACE:CREATE");
        MockHttpServletResponse denied = new MockHttpServletResponse();

        filter.doFilter(createOnly, denied, new MockFilterChain());

        assertThat(denied.getStatus()).isEqualTo(403);

        MockHttpServletRequest manager = new MockHttpServletRequest("POST", path);
        manager.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        manager.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
        manager.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        manager.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        manager.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.WORKPLACE:MANAGE");
        MockHttpServletResponse allowed = new MockHttpServletResponse();

        filter.doFilter(manager, allowed, new MockFilterChain());

        assertThat(allowed.getStatus()).isEqualTo(200);

        String statusPath = path + "/00000000-0000-4000-8000-000000000001";
        MockHttpServletRequest readOnly = new MockHttpServletRequest("GET", statusPath);
        readOnly.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        readOnly.addHeader(PlatformSecurityFilter.USER_HEADER, "19");
        readOnly.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        readOnly.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        readOnly.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.WORKPLACE:VIEW");
        MockHttpServletResponse readDenied = new MockHttpServletResponse();

        filter.doFilter(readOnly, readDenied, new MockFilterChain());

        assertThat(readDenied.getStatus()).isEqualTo(403);

        MockHttpServletRequest statusManager = new MockHttpServletRequest("GET", statusPath);
        statusManager.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        statusManager.addHeader(PlatformSecurityFilter.USER_HEADER, "20");
        statusManager.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        statusManager.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        statusManager.addHeader(
                PlatformSecurityFilter.PERMISSIONS_HEADER, "ADMIN.WORKPLACE:MANAGE");
        MockHttpServletResponse statusAllowed = new MockHttpServletResponse();

        filter.doFilter(statusManager, statusAllowed, new MockFilterChain());

        assertThat(statusAllowed.getStatus()).isEqualTo(200);
    }

    @Test
    void reservationsPageMayReadRoomBookingsThroughItsExactTrustedRoute()
            throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);
        String path = "/v1/rooms/bookings";

        MockHttpServletRequest canonical = new MockHttpServletRequest("GET", path);
        canonical.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        canonical.addHeader(PlatformSecurityFilter.USER_HEADER, "21");
        canonical.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        canonical.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        canonical.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "APP.WORKPLACE:VIEW");
        canonical.addHeader(PlatformSecurityFilter.ROUTE_CONTRACT_HEADER,
                "route.workplace.work.reservations.page");
        MockHttpServletResponse allowed = new MockHttpServletResponse();

        filter.doFilter(canonical, allowed, new MockFilterChain());

        assertThat(allowed.getStatus()).isEqualTo(200);

        MockHttpServletRequest ungoverned = new MockHttpServletRequest("GET", path);
        ungoverned.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        ungoverned.addHeader(PlatformSecurityFilter.USER_HEADER, "22");
        ungoverned.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        ungoverned.addHeader(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER");
        ungoverned.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "APP.WORKPLACE:VIEW");
        MockHttpServletResponse denied = new MockHttpServletResponse();

        filter.doFilter(ungoverned, denied, new MockFilterChain());

        assertThat(denied.getStatus()).isEqualTo(403);
    }
}
