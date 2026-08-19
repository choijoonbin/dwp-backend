package com.dwp.services.notification.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationSecurityFilterTest {

    private final NotificationSecurityFilter filter = new NotificationSecurityFilter(
            "gateway-secret",
            "dwp-gateway",
            "dwp-approval-server,dwp-people-server",
            "dwp-approval-server=approval-secret,dwp-people-server=people-secret",
            new ObjectMapper().findAndRegisterModules());

    @Test
    void requiresConfiguredServiceIdentityAndVerifiedTenantUser() throws Exception {
        MockHttpServletRequest request = request("GET", "/v1/summary", "");
        request.removeHeader(NotificationSecurityFilter.SERVICE_TOKEN_HEADER);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void publicApiRequiresGatewaySourceBoundToGatewayToken() throws Exception {
        MockHttpServletRequest missingSource = request(
                "GET", "/v1/summary", "APP.NOTIFICATIONS:VIEW");
        missingSource.removeHeader(NotificationSecurityFilter.SOURCE_SERVICE_HEADER);
        MockHttpServletRequest producerSource = request(
                "GET", "/v1/summary", "APP.NOTIFICATIONS:VIEW");
        producerSource.removeHeader(NotificationSecurityFilter.SOURCE_SERVICE_HEADER);
        producerSource.addHeader(
                NotificationSecurityFilter.SOURCE_SERVICE_HEADER, "dwp-approval-server");

        assertThat(execute(missingSource).getStatus()).isEqualTo(401);
        assertThat(execute(producerSource).getStatus()).isEqualTo(401);
    }

    @Test
    void allowsAllSelfServiceOperationsWithApplicationViewPermission() throws Exception {
        MockHttpServletResponse read = execute(request(
                "GET", "/v1/inbox", "APP.NOTIFICATIONS:VIEW"));
        MockHttpServletResponse triage = execute(request(
                "POST", "/v1/inbox/4198b9aa-8cd5-49f8-a82b-6f9150b95f55/read",
                "APP.NOTIFICATIONS:VIEW"));
        MockHttpServletResponse settings = execute(request(
                "PUT", "/v1/me/delivery-profile", "APP.NOTIFICATIONS:VIEW"));

        assertThat(read.getStatus()).isEqualTo(200);
        assertThat(triage.getStatus()).isEqualTo(200);
        assertThat(settings.getStatus()).isEqualTo(200);
    }

    @Test
    void neverUsesRoleNamesAsAuthorization() throws Exception {
        MockHttpServletRequest request = request("GET", "/v1/summary", "");
        request.addHeader(NotificationSecurityFilter.ROLES_HEADER, "PLATFORM_ADMIN,TENANT_ADMIN");

        MockHttpServletResponse response = execute(request);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void mapsEveryAdminAreaToItsOwnResource() throws Exception {
        assertThat(execute(request(
                "GET", "/v1/admin/overview", "ADMIN.NOTIFICATION_OPERATIONS:VIEW"))
                .getStatus()).isEqualTo(200);
        assertThat(execute(request(
                "GET", "/v1/admin/types", "ADMIN.NOTIFICATION_CONTRACT:VIEW"))
                .getStatus()).isEqualTo(200);
        assertThat(execute(request(
                "PUT", "/v1/admin/templates/id", "ADMIN.NOTIFICATION_TEMPLATE:VIEW"))
                .getStatus()).isEqualTo(403);
        assertThat(execute(request(
                "PUT", "/v1/admin/templates/id", "ADMIN.NOTIFICATION_TEMPLATE:MANAGE"))
                .getStatus()).isEqualTo(200);
        assertThat(execute(request(
                "POST", "/v1/admin/policies/id/publish", "ADMIN.NOTIFICATION_POLICY:MANAGE"))
                .getStatus()).isEqualTo(403);
        assertThat(execute(request(
                "POST", "/v1/admin/policies/id/publish", "ADMIN.NOTIFICATION_POLICY:APPROVE"))
                .getStatus()).isEqualTo(200);
        assertThat(execute(request(
                "GET", "/v1/admin/operations", "ADMIN.NOTIFICATION_AUDIT:VIEW"))
                .getStatus()).isEqualTo(403);
        assertThat(execute(request(
                "GET", "/v1/admin/audit", "ADMIN.NOTIFICATION_AUDIT:VIEW"))
                .getStatus()).isEqualTo(200);
        assertThat(execute(request(
                "GET", "/v1/admin/unregistered", "ADMIN.NOTIFICATION_OPERATIONS:MANAGE"))
                .getStatus()).isEqualTo(403);
    }

    @Test
    void internalProducerMustBeExplicitlyAllowlisted() throws Exception {
        MockHttpServletRequest allowed = internal(
                "dwp-approval-server", "approval-secret");
        MockHttpServletRequest denied = internal("unknown-service", "approval-secret");
        MockHttpServletRequest wrongBinding = internal(
                "dwp-approval-server", "people-secret");
        MockHttpServletRequest gatewayToken = internal(
                "dwp-approval-server", "gateway-secret");

        assertThat(execute(allowed).getStatus()).isEqualTo(200);
        assertThat(execute(denied).getStatus()).isEqualTo(403);
        assertThat(execute(wrongBinding).getStatus()).isEqualTo(403);
        assertThat(execute(gatewayToken).getStatus()).isEqualTo(403);
    }

    private MockHttpServletRequest request(String method, String path, String permissions) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(NotificationSecurityFilter.SERVICE_TOKEN_HEADER, "gateway-secret");
        request.addHeader(NotificationSecurityFilter.SOURCE_SERVICE_HEADER, "dwp-gateway");
        request.addHeader(NotificationSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(NotificationSecurityFilter.USER_HEADER, "17");
        request.addHeader(NotificationSecurityFilter.PERMISSIONS_HEADER, permissions);
        return request;
    }

    private MockHttpServletRequest internal(String sourceService, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/v1/intents/direct");
        request.addHeader(NotificationSecurityFilter.SERVICE_TOKEN_HEADER, token);
        request.addHeader(NotificationSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(NotificationSecurityFilter.SOURCE_SERVICE_HEADER, sourceService);
        return request;
    }

    private MockHttpServletResponse execute(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
