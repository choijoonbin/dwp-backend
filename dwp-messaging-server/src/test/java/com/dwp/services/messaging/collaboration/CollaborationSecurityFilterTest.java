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
    private static final String WORK_SOURCE_TOKEN = "work-source-test-token";

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
    void exactMessageReadUsesTheExistingMessagingViewPermission() throws Exception {
        String path = "/v1/conversations/71000000-0000-4000-8000-000000000001/messages/"
                + "72000000-0000-4000-8000-000000000001";
        MockHttpServletRequest permitted = request("GET", path, "APP.MESSAGING:VIEW");
        MockHttpServletResponse permittedResponse = new MockHttpServletResponse();
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);

        filter.doFilter(
                permitted, permittedResponse,
                (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(permittedResponse.getStatus()).isEqualTo(200);
        assertThat(invoked.get()).isTrue();

        invoked.set(false);
        MockHttpServletRequest denied = request("GET", path, "APP.WORK:VIEW");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        filter.doFilter(denied, deniedResponse,
                (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(deniedResponse.getStatus()).isEqualTo(403);
        assertThat(invoked.get()).isFalse();
        assertThat(deniedResponse.getContentAsString()).contains("E2001");
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

    @Test
    void internalWorkSourceRequiresItsDedicatedServiceTrustInAdditionToMessagingIdentity()
            throws Exception {
        MessagingSecurityFilter workSourceFilter = new MessagingSecurityFilter(
                SERVICE_TOKEN, WORK_SOURCE_TOKEN,
                new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest missing = request(
                "GET",
                "/internal/v1/work-sources/conversations/71000000-0000-4000-8000-000000000001/messages/72000000-0000-4000-8000-000000000001",
                "APP.MESSAGING:VIEW");
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);

        workSourceFilter.doFilter(
                missing, missingResponse,
                (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(missingResponse.getStatus()).isEqualTo(401);
        assertThat(invoked.get()).isFalse();

        MockHttpServletRequest trusted = request(
                "GET",
                "/internal/v1/work-sources/conversations/71000000-0000-4000-8000-000000000001/messages/72000000-0000-4000-8000-000000000001",
                "APP.MESSAGING:VIEW,APP.WORK:VIEW");
        trusted.addHeader("X-DWP-Work-Source-Token", WORK_SOURCE_TOKEN);
        MockHttpServletResponse trustedResponse = new MockHttpServletResponse();
        AtomicReference<MessagingRequestContext.Subject> captured = new AtomicReference<>();

        workSourceFilter.doFilter(
                trusted, trustedResponse,
                (ignoredRequest, ignoredResponse) -> captured.set(MessagingRequestContext.get()));

        assertThat(trustedResponse.getStatus()).isEqualTo(200);
        assertThat(captured.get().tenantId()).isEqualTo(8101L);
        assertThat(captured.get().userId()).isEqualTo(7101L);
        assertThat(captured.get().permissions())
                .containsExactlyInAnyOrder("APP.MESSAGING:VIEW", "APP.WORK:VIEW");
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
