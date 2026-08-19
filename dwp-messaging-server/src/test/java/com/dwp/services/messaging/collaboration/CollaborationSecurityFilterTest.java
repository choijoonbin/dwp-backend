package com.dwp.services.messaging.collaboration;

import com.dwp.services.messaging.security.MessagingRequestContext;
import com.dwp.services.messaging.security.MessagingSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationSecurityFilterTest {

    private static final String SERVICE_TOKEN = "collaboration-test-token";

    private final MessagingSecurityFilter filter = new MessagingSecurityFilter(
            SERVICE_TOKEN,
            new ObjectMapper().findAndRegisterModules());

    @Test
    void searchRequiresMessagingViewPermissionAndPreservesVerifiedTenant() throws Exception {
        MockHttpServletRequest request = request("GET", "/v1/search", "APP.MESSAGING:VIEW");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<MessagingRequestContext.Subject> captured = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                captured.set(MessagingRequestContext.get()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get().tenantId()).isEqualTo(8101L);
        assertThat(captured.get().userId()).isEqualTo(7101L);
    }

    @Test
    void conversationCreationRejectsViewOnlyPermission() throws Exception {
        MockHttpServletRequest request = request(
                "POST", "/v1/conversations", "APP.MESSAGING:VIEW");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(invoked.get()).isFalse();
        assertThat(response.getContentAsString()).contains("E2001");
    }

    @Test
    void conversationCreationAcceptsMessagingCreatePermission() throws Exception {
        MockHttpServletRequest request = request(
                "POST", "/v1/conversations", "APP.MESSAGING:CREATE");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<MessagingRequestContext.Subject> captured = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                captured.set(MessagingRequestContext.get()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(captured.get().permissions()).containsExactly("APP.MESSAGING:CREATE");
    }

    private MockHttpServletRequest request(String method, String path, String permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("X-DWP-Service-Token", SERVICE_TOKEN);
        request.addHeader("X-DWP-User-ID", "7101");
        request.addHeader("X-DWP-Tenant-ID", "8101");
        request.addHeader("X-DWP-Permissions", permissions);
        return request;
    }
}
