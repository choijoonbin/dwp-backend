package com.dwp.services.provider.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.security.RolePlaneBoundary;
import com.dwp.services.provider.rollout.FeatureRolloutInternalEvaluationSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ProviderSecurityFilter extends OncePerRequestFilter {

    private static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String ROLES_HEADER = "X-DWP-Roles";
    private static final String AUTH_SESSION_ID_HEADER = "X-DWP-Auth-Session-ID";
    private static final String IDENTITY_PLANE_HEADER = "X-DWP-Identity-Plane";
    private final String serviceToken;
    private final ProviderOperatorService operatorService;
    private final ObjectMapper objectMapper;

    public ProviderSecurityFilter(
            @Value("${dwp.provider.service-token:}") String serviceToken,
            ProviderOperatorService operatorService,
            ObjectMapper objectMapper) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.operatorService = operatorService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.startsWith("/v3/api-docs")
                || path.equals(FeatureRolloutInternalEvaluationSecurityFilter.PATH)
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (serviceToken.isBlank()) {
            error(response, ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Provider service identity is not configured.");
            return;
        }
        if (!constantTimeEquals(serviceToken, request.getHeader(SERVICE_TOKEN_HEADER))) {
            error(response, ErrorCode.UNAUTHORIZED, "Trusted provider service identity is required.");
            return;
        }
        Long userId = positiveLong(request.getHeader(USER_HEADER));
        Long authTenantId = positiveLong(request.getHeader(TENANT_HEADER));
        UUID authSessionId = uuid(request.getHeader(AUTH_SESSION_ID_HEADER));
        boolean providerIdentityPlane = "PROVIDER".equalsIgnoreCase(
                request.getHeader(IDENTITY_PLANE_HEADER));
        Set<String> assertedRoles = Arrays.stream(value(request.getHeader(ROLES_HEADER)).split(","))
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        if (RolePlaneBoundary.hasConflict(assertedRoles)) {
            error(response, ErrorCode.FORBIDDEN,
                    "Provider control-plane roles cannot coexist with tenant or workspace roles.");
            return;
        }
        ProviderRequestContext.Actor operator = userId == null || authTenantId == null
                || authSessionId == null
                || !providerIdentityPlane
                ? null
                : operatorService.activeOperator(authTenantId, userId).orElse(null);
        if (operator != null) operator = operator.withAuthSessionId(authSessionId);
        // Auth and provider stores are separate revocation domains. Requiring
        // the complete active role set to match prevents a stale high-risk
        // provider assignment in either store from being hidden by one role
        // that still overlaps.
        boolean matchingProviderRole = operator != null
                && !assertedRoles.isEmpty()
                && assertedRoles.stream().allMatch(RolePlaneBoundary::isProviderRole)
                && operator.roles().stream().allMatch(RolePlaneBoundary::isProviderRole)
                && operator.roles().equals(assertedRoles);
        if (!matchingProviderRole) {
            error(response, ErrorCode.FORBIDDEN,
                    "An active provider operator identity is required.");
            return;
        }
        ProviderRequestContext.set(operator);
        try {
            chain.doFilter(request, response);
        } finally {
            ProviderRequestContext.clear();
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void error(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
