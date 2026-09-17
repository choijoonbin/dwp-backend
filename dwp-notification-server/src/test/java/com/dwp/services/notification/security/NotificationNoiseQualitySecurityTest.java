package com.dwp.services.notification.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationNoiseQualitySecurityTest {

    private static final String TOKEN = "gateway-secret-token-at-least-24";
    private final NotificationSecurityFilter filter = new NotificationSecurityFilter(
            TOKEN,
            "dwp-gateway",
            "dwp-approval-server",
            "dwp-approval-server=approval-secret-token-at-least-24",
            new ObjectMapper().findAndRegisterModules());

    @Test
    void requiresTheNotificationOperationsAdminPermission() throws Exception {
        assertThat(execute("ADMIN.NOTIFICATION_OPERATIONS:VIEW").getStatus())
                .isEqualTo(200);
        assertThat(execute("APP.NOTIFICATIONS:VIEW").getStatus())
                .isEqualTo(403);
    }

    private MockHttpServletResponse execute(String permissions) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/admin/noise-quality");
        request.addHeader(NotificationSecurityFilter.SERVICE_TOKEN_HEADER, TOKEN);
        request.addHeader(NotificationSecurityFilter.SOURCE_SERVICE_HEADER, "dwp-gateway");
        request.addHeader(NotificationSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(NotificationSecurityFilter.USER_HEADER, "17");
        request.addHeader(NotificationSecurityFilter.PERMISSIONS_HEADER, permissions);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
