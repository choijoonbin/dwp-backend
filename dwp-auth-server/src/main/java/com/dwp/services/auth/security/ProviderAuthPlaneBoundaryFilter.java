package com.dwp.services.auth.security;

import com.dwp.services.auth.config.SecurityExceptionHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps provider JWTs on the small Auth self-service surface. All current and
 * future tenant governance/work routes are denied unless explicitly catalogued.
 */
@Component
public class ProviderAuthPlaneBoundaryFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_GET_PATHS = Set.of(
            "/auth/policy",
            "/auth/csrf",
            "/auth/oidc/callback");

    private final DurableIdentityPlaneGuard identityPlaneGuard;
    private final SecurityExceptionHandler exceptionHandler;

    public ProviderAuthPlaneBoundaryFilter(
            DurableIdentityPlaneGuard identityPlaneGuard,
            SecurityExceptionHandler exceptionHandler) {
        this.identityPlaneGuard = identityPlaneGuard;
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.equals("/auth") && !path.startsWith("/auth/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof Jwt)
                || !identityPlaneGuard.isProvider(authentication)
                || providerSelfServiceRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        exceptionHandler.handle(
                request,
                response,
                new AccessDeniedException(
                        "Provider control-plane identities cannot use tenant Auth APIs."));
    }

    private boolean providerSelfServiceRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (HttpMethod.OPTIONS.matches(method)) return true;
        if (HttpMethod.GET.matches(method) && PUBLIC_GET_PATHS.contains(path)) return true;
        if (HttpMethod.GET.matches(method)) {
            return path.equals("/auth/me")
                    || path.equals("/auth/permissions")
                    || path.equals("/auth/sessions");
        }
        if (HttpMethod.PATCH.matches(method)) return path.equals("/auth/me/locale");
        if (HttpMethod.POST.matches(method)) {
            return path.equals("/auth/session/refresh")
                    || path.equals("/auth/sessions/logout-others")
                    || path.equals("/auth/logout");
        }
        return HttpMethod.DELETE.matches(method) && ownedSessionPath(path);
    }

    private boolean ownedSessionPath(String path) {
        String prefix = "/auth/sessions/";
        if (!path.startsWith(prefix)) return false;
        String value = path.substring(prefix.length());
        if (value.isBlank() || value.indexOf('/') >= 0) return false;
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
