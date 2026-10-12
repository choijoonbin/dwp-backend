package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HrisApprovalSecurityBoundaryTest {
    @Test
    void onlyExactCreatePostBypassesLegacyBrowserSecurityFilter() throws Exception {
        ApprovalSecurityFilter filter = new ApprovalSecurityFilter(
                "", new ObjectMapper().findAndRegisterModules());
        var exact = new MockHttpServletRequest(
                "POST", "/internal/hris-approval/v1/approval-requests");
        var near = new MockHttpServletRequest(
                "POST", "/internal/hris-approval/v1/approval-requests/");
        AtomicInteger admitted = new AtomicInteger();

        filter.doFilter(exact, new MockHttpServletResponse(),
                (request, response) -> admitted.incrementAndGet());
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(near, rejected,
                (request, response) -> admitted.incrementAndGet());

        assertThat(admitted).hasValue(1);
        assertThat(rejected.getStatus()).isEqualTo(502);
    }
}
