package com.dwp.services.notification.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAppSummarySecurityTest {

    private static final String GATEWAY_TOKEN = "gateway-secret-token-at-least-24";

    private final NotificationSecurityFilter filter = new NotificationSecurityFilter(
            GATEWAY_TOKEN,
            "dwp-gateway",
            "",
            "",
            new ObjectMapper().findAndRegisterModules());

    @Test
    void appSummaryRequiresVerifiedSelfServicePermission() throws Exception {
        MockHttpServletRequest allowed = request("APP.NOTIFICATIONS:VIEW");
        MockHttpServletRequest denied = request("");

        assertThat(execute(allowed).getStatus()).isEqualTo(200);
        assertThat(execute(denied).getStatus()).isEqualTo(403);
    }

    private MockHttpServletRequest request(String permissions) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/v1/summary/by-app");
        request.addHeader(NotificationSecurityFilter.SERVICE_TOKEN_HEADER, GATEWAY_TOKEN);
        request.addHeader(NotificationSecurityFilter.SOURCE_SERVICE_HEADER, "dwp-gateway");
        request.addHeader(NotificationSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(NotificationSecurityFilter.USER_HEADER, "900018");
        request.addHeader(NotificationSecurityFilter.PERMISSIONS_HEADER, permissions);
        return request;
    }

    private MockHttpServletResponse execute(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
