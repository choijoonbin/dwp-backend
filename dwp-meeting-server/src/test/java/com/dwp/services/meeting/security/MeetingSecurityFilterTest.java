package com.dwp.services.meeting.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingSecurityFilterTest {

    @AfterEach
    void clear() {
        MeetingRequestContext.clear();
    }

    @Test
    void buildsTenantContextOnlyFromTrustedGatewayHeaders()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "trusted-token", new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = request("GET", "/v1/home");
        request.addHeader("X-DWP-Service-Token", "trusted-token");
        request.addHeader("X-DWP-User-ID", "101");
        request.addHeader("X-DWP-Tenant-ID", "77");
        request.addHeader("X-DWP-Permissions", "APP.MEETINGS:VIEW");
        request.addHeader("X-DWP-Display-Name-B64", Base64.getUrlEncoder().withoutPadding()
                .encodeToString("박현우".getBytes(StandardCharsets.UTF_8)));
        AtomicReference<MeetingRequestContext.Subject> captured = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) ->
                captured.set(MeetingRequestContext.get());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(captured.get().tenantId()).isEqualTo(77L);
        assertThat(captured.get().userId()).isEqualTo(101L);
        assertThat(captured.get().displayName()).isEqualTo("박현우");
        assertThatThrownBy(MeetingRequestContext::get)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAppPermissionForAdministrativePolicyMutation()
            throws ServletException, IOException {
        MeetingSecurityFilter filter = new MeetingSecurityFilter(
                "trusted-token", new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = request("PUT", "/v1/admin/policy");
        request.addHeader("X-DWP-Service-Token", "trusted-token");
        request.addHeader("X-DWP-User-ID", "101");
        request.addHeader("X-DWP-Tenant-ID", "77");
        request.addHeader("X-DWP-Permissions", "APP.MEETINGS:UPDATE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }
}
