package com.dwp.services.messaging.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingWorkSourceSecurityFilterTest {
    private static final String SERVICE_TOKEN = "messaging-service-test";
    private static final String WORK_SOURCE_TOKEN = "work-source-test";
    private static final String PATH = "/internal/v1/work-sources/conversations/"
            + "2f44fa32-a765-41c7-a267-1d79f376eb7b/messages/"
            + "31179c9f-21b4-47c3-a76b-638fe20b00d7";

    private final MessagingSecurityFilter filter = new MessagingSecurityFilter(
            SERVICE_TOKEN, WORK_SOURCE_TOKEN, new ObjectMapper().findAndRegisterModules());

    @Test
    void internalWorkSourceRequiresServiceAndPurposeBoundTokens() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletRequest missingPurpose = request();
        missingPurpose.addHeader("X-DWP-Service-Token", SERVICE_TOKEN);
        MockHttpServletResponse rejected = new MockHttpServletResponse();

        filter.doFilter(missingPurpose, rejected,
                (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(rejected.getStatus()).isEqualTo(401);
        assertThat(invoked).isFalse();

        MockHttpServletRequest authorized = request();
        authorized.addHeader("X-DWP-Service-Token", SERVICE_TOKEN);
        authorized.addHeader("X-DWP-Work-Source-Token", WORK_SOURCE_TOKEN);
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        AtomicReference<MessagingRequestContext.Subject> subject = new AtomicReference<>();

        filter.doFilter(authorized, accepted,
                (ignoredRequest, ignoredResponse) -> subject.set(MessagingRequestContext.get()));

        assertThat(accepted.getStatus()).isEqualTo(200);
        assertThat(subject.get().tenantId()).isEqualTo(7L);
        assertThat(subject.get().userId()).isEqualTo(11L);
    }

    @Test
    void purposeBoundTokenCannotReplaceTheMessagingServiceIdentity() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader("X-DWP-Work-Source-Token", WORK_SOURCE_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(invoked).isFalse();
    }

    @Test
    void internalWorkSourceRequiresTheActorsWorkViewEntitlement() throws Exception {
        MockHttpServletRequest request = request();
        request.removeHeader("X-DWP-Permissions");
        request.addHeader("X-DWP-Permissions", "APP.MESSAGING:VIEW");
        request.addHeader("X-DWP-Service-Token", SERVICE_TOKEN);
        request.addHeader("X-DWP-Work-Source-Token", WORK_SOURCE_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(invoked).isFalse();
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        request.addHeader("X-DWP-Tenant-ID", "7");
        request.addHeader("X-DWP-User-ID", "11");
        request.addHeader("X-DWP-Permissions", "APP.WORK:VIEW,APP.MESSAGING:VIEW");
        return request;
    }
}
