package com.dwp.services.platform.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.security.RolePlaneBoundary;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public final class PersonalSettingsSecurityFilter extends OncePerRequestFilter {

    private static final String PATH = "/v1/personal-settings";
    private static final String IDENTITY_PLANE_HEADER = "X-DWP-Identity-Plane";
    private static final String PROVIDER_TENANT_HEADER = "X-DWP-Provider-Tenant-ID";
    private final ObjectMapper objectMapper;

    public PersonalSettingsSecurityFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals(PATH) || path.startsWith(PATH + "/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        if (!"TENANT".equals(request.getHeader(IDENTITY_PLANE_HEADER))
                || present(request.getHeader(PlatformSecurityFilter.SUPPORT_SESSION_HEADER))
                || present(request.getHeader(PlatformSecurityFilter.SUPPORT_SCOPES_HEADER))
                || present(request.getHeader(PROVIDER_TENANT_HEADER))
                || present(request.getHeader(PlatformSecurityFilter.ACTOR_TENANT_HEADER))
                || RolePlaneBoundary.isProviderIdentity(roles(request))) {
            response.setStatus(ErrorCode.FORBIDDEN.getHttpStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(
                    ErrorCode.FORBIDDEN,
                    "Personal settings require a tenant self-service identity."));
            return;
        }
        chain.doFilter(request, response);
    }

    private Set<String> roles(HttpServletRequest request) {
        String header = request.getHeader(PlatformSecurityFilter.ROLES_HEADER);
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
