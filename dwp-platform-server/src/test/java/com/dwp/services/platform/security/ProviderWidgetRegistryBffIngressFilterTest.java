package com.dwp.services.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ProviderWidgetRegistryBffIngressFilterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void rewritesCanonicalRouteAndTerminatesPurposeCredentialBeforePlatformSecurity() throws Exception {
        ProviderWidgetRegistryBffIngressFilter ingress = filter("widget-secret", "platform-secret");
        PlatformSecurityFilter platform = new PlatformSecurityFilter(
                "platform-secret", "runtime-secret", MAPPER);
        MockHttpServletRequest request = validRequest(
                "GET", ProviderWidgetRegistryBffIngressFilter.INTERNAL_PREFIX + "/definitions");
        request.setQueryString("page=0&size=25");
        AtomicReference<HttpServletRequest> accepted = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ingress.doFilter(request, response, (rewritten, outerResponse) ->
                platform.doFilter(rewritten, outerResponse, (trusted, innerResponse) ->
                        accepted.set((HttpServletRequest) trusted)));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(accepted.get()).isNotNull();
        assertThat(accepted.get().getRequestURI()).isEqualTo("/v1/admin/widget-definitions");
        assertThat(accepted.get().getServletPath()).isEqualTo("/v1/admin/widget-definitions");
        assertThat(accepted.get().getQueryString()).isEqualTo("page=0&size=25");
        assertThat(values(accepted.get(), PlatformSecurityFilter.SERVICE_TOKEN_HEADER))
                .containsExactly("platform-secret");
        assertThat(values(accepted.get(), ProviderWidgetRegistryBffIngressFilter.TOKEN_HEADER))
                .isEmpty();
        assertThat(Collections.list(accepted.get().getHeaderNames()))
                .doesNotContain(ProviderWidgetRegistryBffIngressFilter.TOKEN_HEADER);
        assertThat(accepted.get().getHeader(PlatformSecurityFilter.USER_HEADER)).isEqualTo("900001");
        assertThat(accepted.get().getHeader(PlatformSecurityFilter.TENANT_HEADER)).isEqualTo("1");
        assertThat(accepted.get().getHeader(PlatformSecurityFilter.ROLES_HEADER))
                .isEqualTo("PROVIDER_ADMIN");
        assertThat(accepted.get().getHeader(PlatformSecurityFilter.PERMISSIONS_HEADER))
                .isEqualTo("WIDGET_CATALOG_READ");
        assertThat(accepted.get().getHeader(PlatformSecurityFilter.WIDGET_OWNER_SCOPE_HEADER))
                .isEqualTo("core.work");
        assertThat(accepted.get().getHeader("X-DWP-Auth-Session-ID"))
                .isEqualTo("40000000-0000-0000-0000-000000000001");
    }

    @ParameterizedTest
    @MethodSource("canonicalRoutes")
    void rewritesOnlySupportedControllerRoutes(String method, String internalPath, String publicPath)
            throws Exception {
        MockHttpServletRequest request = validRequest(method, internalPath);
        AtomicReference<HttpServletRequest> accepted = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter("widget-secret", "platform-secret").doFilter(
                request, response, (rewritten, ignored) ->
                        accepted.set((HttpServletRequest) rewritten));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(accepted.get().getRequestURI()).isEqualTo(publicPath);
    }

    @Test
    void rejectsMissingWrongAndDuplicatePurposeCredentialsAndGenericCredentialInjection()
            throws Exception {
        for (MockHttpServletRequest denied : List.of(
                requestWithoutToken("GET", suffix("/definitions")),
                requestWithToken("GET", suffix("/definitions"), "wrong-secret"),
                requestWithDuplicateToken("GET", suffix("/definitions")),
                requestWithGenericToken("GET", suffix("/definitions")))) {
            AtomicInteger calls = new AtomicInteger();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("widget-secret", "platform-secret").doFilter(
                    denied, response, (ignoredRequest, ignoredResponse) -> calls.incrementAndGet());

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void failsClosedWhenEitherServerCredentialIsUnconfigured() throws Exception {
        for (ProviderWidgetRegistryBffIngressFilter ingress : List.of(
                filter("", "platform-secret"),
                filter("widget-secret", ""))) {
            AtomicInteger calls = new AtomicInteger();
            MockHttpServletResponse response = new MockHttpServletResponse();

            ingress.doFilter(validRequest("GET", suffix("/definitions")), response,
                    (ignoredRequest, ignoredResponse) -> calls.incrementAndGet());

            assertThat(response.getStatus()).isEqualTo(502);
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void rejectsUnsupportedMethodsRoutesAndAmbiguousPathsBeforeCredentialEvaluation()
            throws Exception {
        for (MockHttpServletRequest denied : List.of(
                validRequest("DELETE", suffix("/definitions")),
                validRequest("POST", suffix("/definitions/id")),
                validRequest("GET", suffix("/definition-versions")),
                validRequest("GET", suffix("/registry")),
                validRequest("GET", suffix("/registry/unknown")),
                validRequest("GET", suffix("/definitions/id/unknown")),
                validRequest("GET", suffix("/definitions%2Fid")),
                validRequest("GET", suffix("/definitions;matrix")),
                validRequest("GET", suffix("/definitions\\id")),
                validRequest("GET", suffix("//definitions")),
                validRequest("GET", suffix("/./definitions")),
                validRequest("GET", suffix("/other/../definitions")))) {
            AtomicInteger calls = new AtomicInteger();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("widget-secret", "platform-secret").doFilter(
                    denied, response, (ignoredRequest, ignoredResponse) -> calls.incrementAndGet());

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void platformProviderBoundaryRejectsUnverifiedIdentityAndOwnerHeaders() throws Exception {
        PlatformSecurityFilter platform = new PlatformSecurityFilter(
                "platform-secret", "runtime-secret", MAPPER);
        for (MockHttpServletRequest denied : List.of(
                request("GET", suffix("/definitions"), "TENANT_ADMIN", "TENANT",
                        "WIDGET_REGISTRY_PROVIDER", "core.work"),
                request("GET", suffix("/definitions"), "PROVIDER_ADMIN", "PROVIDER",
                        null, "core.work"),
                request("GET", suffix("/definitions"), "PROVIDER_ADMIN", "PROVIDER",
                        "WIDGET_REGISTRY_PROVIDER", null))) {
            AtomicInteger accepted = new AtomicInteger();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("widget-secret", "platform-secret").doFilter(
                    denied, response, (rewritten, outerResponse) ->
                            platform.doFilter(rewritten, outerResponse,
                                    (ignoredRequest, ignoredResponse) -> accepted.incrementAndGet()));

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(accepted).hasValue(0);
        }
    }

    private static Stream<Arguments> canonicalRoutes() {
        return Stream.of(
                route("GET", "/definitions", "/v1/admin/widget-definitions"),
                route("POST", "/definitions", "/v1/admin/widget-definitions"),
                route("GET", "/definitions/definition-id",
                        "/v1/admin/widget-definitions/definition-id"),
                route("POST", "/definitions/definition-id/versions",
                        "/v1/admin/widget-definitions/definition-id/versions"),
                route("PUT", "/definition-versions/version-id",
                        "/v1/admin/widget-definition-versions/version-id"),
                route("POST", "/definition-versions/version-id/decision",
                        "/v1/admin/widget-definition-versions/version-id/decision"),
                route("GET", "/runtime-controls", "/v1/admin/widget-runtime-controls"),
                route("POST", "/runtime-controls/control-id/enable",
                        "/v1/admin/widget-runtime-controls/control-id/enable"),
                route("GET", "/registry/readiness", "/v1/admin/widget-registry/readiness"),
                route("GET", "/registry/events", "/v1/admin/widget-registry/events"));
    }

    private static Arguments route(String method, String suffix, String publicPath) {
        return Arguments.of(method, suffix(suffix), publicPath);
    }

    private static ProviderWidgetRegistryBffIngressFilter filter(
            String purposeToken, String serviceToken) {
        return new ProviderWidgetRegistryBffIngressFilter(purposeToken, serviceToken, MAPPER);
    }

    private static MockHttpServletRequest validRequest(String method, String path) {
        return request(method, path, "PROVIDER_ADMIN", "PROVIDER",
                "WIDGET_REGISTRY_PROVIDER", "core.work");
    }

    private static MockHttpServletRequest request(
            String method,
            String path,
            String roles,
            String identityPlane,
            String controlPlane,
            String owners) {
        MockHttpServletRequest request = requestWithToken(method, path, "widget-secret");
        request.addHeader(PlatformSecurityFilter.USER_HEADER, "900001");
        request.addHeader(PlatformSecurityFilter.TENANT_HEADER, "1");
        request.addHeader(PlatformSecurityFilter.ROLES_HEADER, roles);
        request.addHeader(PlatformSecurityFilter.PERMISSIONS_HEADER, "WIDGET_CATALOG_READ");
        request.addHeader("X-DWP-Auth-Session-ID", "40000000-0000-0000-0000-000000000001");
        request.addHeader("X-DWP-Identity-Plane", identityPlane);
        if (controlPlane != null) {
            request.addHeader(PlatformSecurityFilter.CONTROL_PLANE_HEADER, controlPlane);
        }
        if (owners != null) request.addHeader(PlatformSecurityFilter.WIDGET_OWNER_SCOPE_HEADER, owners);
        return request;
    }

    private static MockHttpServletRequest requestWithoutToken(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static MockHttpServletRequest requestWithToken(
            String method, String path, String token) {
        MockHttpServletRequest request = requestWithoutToken(method, path);
        request.addHeader(ProviderWidgetRegistryBffIngressFilter.TOKEN_HEADER, token);
        return request;
    }

    private static MockHttpServletRequest requestWithDuplicateToken(String method, String path) {
        MockHttpServletRequest request = requestWithToken(method, path, "widget-secret");
        request.addHeader(ProviderWidgetRegistryBffIngressFilter.TOKEN_HEADER, "widget-secret");
        return request;
    }

    private static MockHttpServletRequest requestWithGenericToken(String method, String path) {
        MockHttpServletRequest request = requestWithToken(method, path, "widget-secret");
        request.addHeader(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "injected-secret");
        return request;
    }

    private static String suffix(String suffix) {
        return ProviderWidgetRegistryBffIngressFilter.INTERNAL_PREFIX + suffix;
    }

    private static List<String> values(HttpServletRequest request, String header) {
        return request.getHeaders(header) == null
                ? List.of() : Collections.list(request.getHeaders(header));
    }
}
