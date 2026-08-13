package com.dwp.services.provider.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
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
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ProviderSecurityFilter extends OncePerRequestFilter {

    private static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String ROLES_HEADER = "X-DWP-Roles";
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
        return path.startsWith("/actuator/health") || path.equals("/error");
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
        Set<String> assertedRoles = Arrays.stream(value(request.getHeader(ROLES_HEADER)).split(","))
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        ProviderRequestContext.Actor operator = userId == null || authTenantId == null
                ? null
                : operatorService.activeOperator(authTenantId, userId).orElse(null);
        boolean matchingProviderRole = operator != null
                && operator.roles().stream().anyMatch(assertedRoles::contains);
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
