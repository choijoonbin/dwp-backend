package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformDeviceIdentitySecurityTest {
    private static final String CREDENTIAL =
            "opaque-device-credential-material-1234567890";
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsOnlyGatewayAuthenticatedDeviceEvidenceOnEveryDeviceRoute() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);
        String id = "40000000-0000-0000-0000-000000000017";
        List<RequestCase> routes = List.of(
                new RequestCase("POST", "/v1/device/workplace/devices:register"),
                new RequestCase("POST", "/v1/device/workplace/devices/" + id + "/heartbeat"),
                new RequestCase("GET", "/v1/device/workplace/devices/" + id + "/projection"),
                new RequestCase("POST", "/v1/device/workplace/devices/" + id
                        + "/access-pass:pair"),
                new RequestCase("GET", "/v1/workplace/kiosk/session"),
                new RequestCase("GET", "/v1/workplace/kiosk/visits/" + id),
                new RequestCase("POST", "/v1/workplace/kiosk/visits/" + id + ":arrive"),
                new RequestCase("POST", "/v1/workplace/kiosk/visits/" + id + ":checkout"),
                new RequestCase("POST", "/v1/workplace/kiosk/devices/" + id + ":heartbeat"),
                new RequestCase("POST", "/v1/workplace/kiosk/devices/" + id + ":help"));

        for (RequestCase route : routes) {
            MockHttpServletRequest request = valid(route.method(), route.path());
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).as(route.path()).isEqualTo(200);
        }
    }

    @Test
    void rejectsMissingDuplicateForgedAndMixedDeviceEvidence() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);
        String path = "/v1/workplace/kiosk/session";

        MockHttpServletRequest missing = new MockHttpServletRequest("GET", path);
        missing.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        missing.addHeader(PlatformSecurityFilter.TENANT_HEADER, "42");
        assertDenied(filter, missing, 401);

        MockHttpServletRequest legacyHash = new MockHttpServletRequest("GET", path);
        legacyHash.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        legacyHash.addHeader(PlatformSecurityFilter.TENANT_HEADER, "42");
        legacyHash.addHeader(PlatformDeviceIdentity.PLANE_HEADER, "DEVICE");
        legacyHash.addHeader("X-DWP-Device-Identity-SHA256", "a".repeat(64));
        assertDenied(filter, legacyHash, 401);

        MockHttpServletRequest duplicate = valid("GET", path);
        duplicate.addHeader(PlatformDeviceIdentity.CREDENTIAL_HEADER, CREDENTIAL + "-second");
        assertDenied(filter, duplicate, 401);

        MockHttpServletRequest mixedUser = valid("GET", path);
        mixedUser.addHeader(PlatformSecurityFilter.USER_HEADER, "7");
        mixedUser.addHeader(PlatformSecurityFilter.ROLES_HEADER, "PLATFORM_ADMIN");
        mixedUser.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER,
                "ADMIN.WORKPLACE:MANAGE");
        assertDenied(filter, mixedUser, 403);

        MockHttpServletRequest mixedSupport = valid("GET", path);
        mixedSupport.addHeader(PlatformSecurityFilter.SUPPORT_SESSION_HEADER, "support-session");
        assertDenied(filter, mixedSupport, 403);

        MockHttpServletRequest nonCanonicalTenant = valid("GET", path);
        nonCanonicalTenant.removeHeader(PlatformSecurityFilter.TENANT_HEADER);
        nonCanonicalTenant.addHeader(PlatformSecurityFilter.TENANT_HEADER, "042");
        assertDenied(filter, nonCanonicalTenant, 401);

        MockHttpServletRequest runtimeIdentity = valid("GET", path);
        runtimeIdentity.removeHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER);
        runtimeIdentity.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "runtime");
        assertDenied(filter, runtimeIdentity, 401);
    }

    @Test
    void rejectsDevicePlaneOnNonDeviceRoutes() throws Exception {
        PlatformSecurityFilter filter = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);
        MockHttpServletRequest request = valid("GET", "/v1/workplace/visits");

        assertDenied(filter, request, 403);
    }

    @Test
    void workplaceProductPepExplicitlySkipsDevicePlaneRoutes() throws Exception {
        PlatformWorkplaceProductPepFilter filter = new PlatformWorkplaceProductPepFilter(
                true, new PlatformWorkplaceProductPepRegistry(objectMapper), objectMapper);
        MockHttpServletRequest request = valid("GET", "/v1/workplace/kiosk/session");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest valid(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(PlatformDeviceIdentity.PLANE_HEADER, PlatformDeviceIdentity.PLANE);
        request.addHeader(PlatformDeviceIdentity.CREDENTIAL_HEADER, CREDENTIAL);
        return request;
    }

    private static void assertDenied(
            PlatformSecurityFilter filter, MockHttpServletRequest request, int status)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(status);
    }

    private record RequestCase(String method, String path) { }
}
