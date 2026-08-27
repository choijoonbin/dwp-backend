package com.dwp.services.auth.security;

import com.dwp.services.auth.config.SecurityExceptionHandler;
import com.dwp.services.auth.service.SessionCookieService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderAuthPlaneBoundaryFilterTest {

    private final DurableIdentityPlaneGuard planes = mock(DurableIdentityPlaneGuard.class);
    private final ProviderAuthPlaneBoundaryFilter filter = new ProviderAuthPlaneBoundaryFilter(
            planes,
            new SecurityExceptionHandler(
                    new ObjectMapper().findAndRegisterModules(),
                    mock(SessionCookieService.class)));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void providerCanUseOnlyTheExactAccountSelfServiceContract() throws Exception {
        Authentication provider = authentication(900001L, 1L);
        SecurityContextHolder.getContext().setAuthentication(provider);
        when(planes.isProvider(provider)).thenReturn(true);
        UUID sessionId = UUID.randomUUID();

        for (Request request : List.of(
                new Request("GET", "/auth/me"),
                new Request("PATCH", "/auth/me/locale"),
                new Request("GET", "/auth/permissions"),
                new Request("GET", "/auth/sessions"),
                new Request("POST", "/auth/session/refresh"),
                new Request("DELETE", "/auth/sessions/" + sessionId),
                new Request("POST", "/auth/sessions/logout-others"),
                new Request("POST", "/auth/logout"),
                new Request("GET", "/auth/csrf"))) {
            assertThat(forwarded(request)).as(request.toString()).isTrue();
        }
    }

    @Test
    void providerIsDeniedFromTenantAndUnknownAuthRoutesByDefault() throws Exception {
        Authentication provider = authentication(900001L, 1L);
        SecurityContextHolder.getContext().setAuthentication(provider);
        when(planes.isProvider(provider)).thenReturn(true);

        for (Request request : List.of(
                new Request("GET", "/auth/work/access-review-items/" + UUID.randomUUID()),
                new Request("GET", "/auth/admin/access/app-governance/presets/self-service-options"),
                new Request("POST", "/auth/admin/access/app-governance/presets/self-service-requests"),
                new Request("GET", "/auth/admin/directory/users"),
                new Request("GET", "/auth/idp"),
                new Request("GET", "/auth/me/policy"),
                new Request("POST", "/auth/login"),
                new Request("GET", "/auth/oidc/login"),
                new Request("POST", "/auth/activations/tenant-activation-token"),
                new Request("POST", "/auth/future-tenant-command"),
                new Request("GET", "/auth/me/tenant-details"),
                new Request("DELETE", "/auth/sessions/not-a-uuid"))) {
            Result result = result(request);
            assertThat(result.forwarded()).as(request.toString()).isFalse();
            assertThat(result.status()).as(request.toString()).isEqualTo(403);
        }
    }

    @Test
    void tenantIdentityIsNotRestrictedByTheProviderBoundary() throws Exception {
        Authentication tenant = authentication(71L, 9L);
        SecurityContextHolder.getContext().setAuthentication(tenant);
        when(planes.isProvider(tenant)).thenReturn(false);

        assertThat(forwarded(new Request(
                "GET", "/auth/work/access-review-items/" + UUID.randomUUID()))).isTrue();
    }

    private boolean forwarded(Request request) throws Exception {
        return result(request).forwarded();
    }

    private Result result(Request request) throws Exception {
        MockHttpServletRequest servletRequest =
                new MockHttpServletRequest(request.method(), request.path());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean forwarded = new AtomicBoolean();
        filter.doFilter(servletRequest, response, (ignoredRequest, ignoredResponse) ->
                forwarded.set(true));
        return new Result(forwarded.get(), response.getStatus());
    }

    private Authentication authentication(Long userId, Long tenantId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .issuedAt(Instant.parse("2026-08-26T03:00:00Z"))
                .expiresAt(Instant.parse("2026-08-26T04:00:00Z"))
                .claim("tenant_id", tenantId.toString())
                .build();
        return new TestingAuthenticationToken(jwt, null);
    }

    private record Request(String method, String path) {
    }

    private record Result(boolean forwarded, int status) {
    }
}
