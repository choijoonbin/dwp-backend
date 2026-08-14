package com.dwp.services.auth.security;

import com.dwp.observability.api.ApiHistoryAttributes;
import com.dwp.services.auth.service.AuthSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AuthSessionActivityFilter extends OncePerRequestFilter {

    private final AuthSessionService authSessionService;

    public AuthSessionActivityFilter(AuthSessionService authSessionService) {
        this.authSessionService = authSessionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/auth/session/refresh".equals(request.getRequestURI())
                || request.getRequestURI().startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Jwt jwt) {
            authSessionService.touch(jwt.getId());
            request.setAttribute(ApiHistoryAttributes.ACTOR_TYPE, "USER");
            request.setAttribute(ApiHistoryAttributes.ACTOR_ID, jwt.getSubject());
            request.setAttribute(ApiHistoryAttributes.TENANT_ID, jwt.getClaimAsString("tenant_id"));
            request.setAttribute(
                    ApiHistoryAttributes.AUTH_TYPE,
                    bearerRequest(request) ? "BEARER" : "SESSION");
        }
        filterChain.doFilter(request, response);
    }

    private boolean bearerRequest(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        return authorization != null && authorization.startsWith("Bearer ");
    }
}
