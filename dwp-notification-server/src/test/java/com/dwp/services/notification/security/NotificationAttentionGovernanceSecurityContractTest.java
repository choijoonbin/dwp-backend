package com.dwp.services.notification.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAttentionGovernanceSecurityContractTest {

    private static final String TOKEN = "gateway-secret-token-at-least-24";

    private final NotificationSecurityFilter filter = new NotificationSecurityFilter(
            TOKEN,
            "dwp-gateway",
            "dwp-approval-server",
            "dwp-approval-server=approval-secret-token-at-least-24",
            new ObjectMapper().findAndRegisterModules());

    @Test
    void appliesExactViewManageAndIndependentApprovalPermissions() throws Exception {
        String base = "/v1/admin/policies/attention-governance";

        assertThat(execute(request("GET", base, "ADMIN.NOTIFICATION_POLICY:VIEW")))
                .isEqualTo(200);
        assertThat(execute(request("POST", base + "/drafts",
                "ADMIN.NOTIFICATION_POLICY:VIEW"))).isEqualTo(403);
        assertThat(execute(request("POST", base + "/drafts",
                "ADMIN.NOTIFICATION_POLICY:MANAGE"))).isEqualTo(200);
        assertThat(execute(request("POST", base + "/id/publish",
                "ADMIN.NOTIFICATION_POLICY:MANAGE"))).isEqualTo(403);
        assertThat(execute(request("POST", base + "/id/publish",
                "ADMIN.NOTIFICATION_POLICY:APPROVE"))).isEqualTo(200);
        assertThat(execute(request("POST", base + "/id/reject",
                "ADMIN.NOTIFICATION_POLICY:APPROVE"))).isEqualTo(200);
        assertThat(execute(request("POST", base + "/id/withdraw",
                "ADMIN.NOTIFICATION_POLICY:APPROVE"))).isEqualTo(403);
        assertThat(execute(request("POST", base + "/id/withdraw",
                "ADMIN.NOTIFICATION_POLICY:MANAGE"))).isEqualTo(200);
    }

    private MockHttpServletRequest request(String method, String path, String permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(NotificationSecurityFilter.SERVICE_TOKEN_HEADER, TOKEN);
        request.addHeader(NotificationSecurityFilter.SOURCE_SERVICE_HEADER, "dwp-gateway");
        request.addHeader(NotificationSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(NotificationSecurityFilter.USER_HEADER, "17");
        request.addHeader(NotificationSecurityFilter.PERMISSIONS_HEADER, permissions);
        return request;
    }

    private int execute(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
