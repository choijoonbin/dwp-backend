package com.dwp.services.notification.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAttentionControlSecurityTest {

    private static final String GATEWAY_TOKEN = "gateway-secret-token-at-least-24";
    private static final String CONTROL_PATH =
            "/v1/inbox/4198b9aa-8cd5-49f8-a82b-6f9150b95f55/attention-controls";

    private final NotificationSecurityFilter filter = new NotificationSecurityFilter(
            GATEWAY_TOKEN,
            "dwp-gateway",
            "dwp-approval-server",
            "dwp-approval-server=approval-secret-token-at-least-24",
            new ObjectMapper().findAndRegisterModules());

    @Test
    void previewAndApplyFailClosedImmediatelyAfterViewPermissionRevocation()
            throws Exception {
        assertThat(execute(request(CONTROL_PATH + "/preview", true)).getStatus())
                .isEqualTo(200);
        assertThat(execute(request(CONTROL_PATH, true)).getStatus())
                .isEqualTo(200);

        assertThat(execute(request(CONTROL_PATH + "/preview", false)).getStatus())
                .isEqualTo(403);
        assertThat(execute(request(CONTROL_PATH, false)).getStatus())
                .isEqualTo(403);
    }

    private MockHttpServletRequest request(String path, boolean permitted) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader(NotificationSecurityFilter.SERVICE_TOKEN_HEADER, GATEWAY_TOKEN);
        request.addHeader(NotificationSecurityFilter.SOURCE_SERVICE_HEADER, "dwp-gateway");
        request.addHeader(NotificationSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(NotificationSecurityFilter.USER_HEADER, "17");
        if (permitted) {
            request.addHeader(
                    NotificationSecurityFilter.PERMISSIONS_HEADER,
                    "APP.NOTIFICATIONS:VIEW");
        }
        return request;
    }

    private MockHttpServletResponse execute(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
