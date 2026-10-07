package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

abstract class PlatformSecurityFilterTestSupport {

    protected final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    protected MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    protected MockHttpServletResponse apply(
            PlatformSecurityFilter filter,
            MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    protected MockHttpServletRequest mailAdminRequest(
            String method, String path, String permission) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "18");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "MAIL_ADMIN");
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, permission);
        return request;
    }

    protected MockHttpServletRequest tenantAdminRequest(
            String method, String path, String permission) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "17");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "3");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, "TENANT_ADMIN");
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        if (permission != null) {
            request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, permission);
        }
        return request;
    }

    protected void legacyProduct(MockHttpServletRequest request) {
        request.addHeader(PlatformSecurityFilter.ROLLOUT_STATE_HEADER, "000");
        request.addHeader(PlatformSecurityFilter.ROLLOUT_REVISION_HEADER,
                "rollout-" + "0123456789abcdef".repeat(4));
        request.addHeader(PlatformSecurityFilter.ROLLOUT_COHORT_HEADER, "baseline");
    }}
