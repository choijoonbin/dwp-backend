package com.dwp.services.messaging.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingPresenceSecurityFilterTest {

    private static final String SERVICE_TOKEN = "presence-test-token";

    private final MessagingSecurityFilter filter = new MessagingSecurityFilter(
            SERVICE_TOKEN,
            new ObjectMapper().findAndRegisterModules());

    @Test
    void heartbeatAcceptsMessagingViewPermission() throws Exception {
        MockHttpServletRequest request = request("APP.MESSAGING:VIEW");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(invoked).isTrue();
    }

    @Test
    void heartbeatRejectsMutationPermissionWithoutMessagingView() throws Exception {
        MockHttpServletRequest request = request("APP.MESSAGING:CREATE");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(invoked).isFalse();
        assertThat(response.getContentAsString()).contains("E2001");
    }

    @Test
    void viewPermissionExceptionAppliesOnlyToTheExactHeartbeatPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/v1/presence/heartbeat/another-user");
        request.addHeader("X-DWP-Service-Token", SERVICE_TOKEN);
        request.addHeader("X-DWP-User-ID", "7101");
        request.addHeader("X-DWP-Tenant-ID", "8101");
        request.addHeader("X-DWP-Permissions", "APP.MESSAGING:VIEW");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(invoked).isFalse();
    }

    private MockHttpServletRequest request(String permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/v1/presence/heartbeat");
        request.addHeader("X-DWP-Service-Token", SERVICE_TOKEN);
        request.addHeader("X-DWP-User-ID", "7101");
        request.addHeader("X-DWP-Tenant-ID", "8101");
        request.addHeader("X-DWP-Permissions", permissions);
        return request;
    }
}
