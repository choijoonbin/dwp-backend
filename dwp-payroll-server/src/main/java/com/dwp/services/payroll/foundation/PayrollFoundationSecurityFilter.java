package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Establishes a verified gateway boundary before any payroll foundation header is trusted. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(
        name = "dwp.hris.payroll-foundation.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PayrollFoundationSecurityFilter extends OncePerRequestFilter {

    static final String PATH_PREFIX = "/v1/hris/payroll/foundation";
    static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    static final String USER_HEADER = "X-DWP-User-ID";
    static final String ROLES_HEADER = "X-DWP-Roles";
    static final String PERMISSIONS_HEADER = "X-DWP-Permissions";
    static final String PURPOSE_HEADER = "X-DWP-Purpose";
    static final String SCOPE_HEADER = "X-DWP-Legal-Entity-Scope";
    static final String POLICY_REVISION_HEADER = "X-DWP-Policy-Revision";
    static final String AUTHORIZATION_REVISION_HEADER = "X-DWP-Authorization-Revision";

    private final String serviceToken;
    private final ObjectMapper objectMapper;

    PayrollFoundationSecurityFilter(
            @Value("${dwp.payroll.service-token:}") String serviceToken,
            ObjectMapper objectMapper) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (serviceToken.isBlank()) {
            writeError(
                    response,
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Payroll service identity is not configured.");
            return;
        }
        String presentedToken = singleHeader(request, SERVICE_TOKEN_HEADER);
        if (!constantTimeEquals(serviceToken, presentedToken)) {
            writeError(
                    response,
                    ErrorCode.UNAUTHORIZED,
                    "Trusted payroll gateway identity is required.");
            return;
        }

        Long tenantId = positiveLong(singleHeader(request, TENANT_HEADER));
        Long actorId = positiveLong(singleHeader(request, USER_HEADER));
        Set<String> roles = tokens(singleHeader(request, ROLES_HEADER));
        Set<String> permissions = tokens(singleHeader(request, PERMISSIONS_HEADER));
        String purpose = normalized(singleHeader(request, PURPOSE_HEADER));
        String scope = normalized(singleHeader(request, SCOPE_HEADER));
        String policyRevision = revision(singleHeader(request, POLICY_REVISION_HEADER));
        String authorizationRevision = revision(
                singleHeader(request, AUTHORIZATION_REVISION_HEADER));
        if (tenantId == null || actorId == null || roles.isEmpty() || permissions.isEmpty()
                || purpose == null || scope == null || policyRevision == null
                || authorizationRevision == null) {
            writeError(
                    response,
                    ErrorCode.UNAUTHORIZED,
                    "Verified payroll identity, authority revisions, purpose and scope are required.");
            return;
        }

        PayrollFoundationRequestContext.set(
                new PayrollFoundationRequestContext.VerifiedSubject(
                        tenantId, actorId, roles, permissions, purpose, scope,
                        policyRevision, authorizationRevision));
        try {
            filterChain.doFilter(request, response);
        } finally {
            PayrollFoundationRequestContext.clear();
        }
    }

    private String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) {
            return null;
        }
        String value = values.getFirst();
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Set<String> tokens(String value) {
        if (value == null) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : value.split("[,\\s]+")) {
            String normalized = normalized(token);
            if (normalized != null) {
                tokens.add(normalized);
            }
        }
        return Set.copyOf(tokens);
    }

    private String normalized(String value) {
        if (value == null || value.isBlank() || value.length() > 2_000) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9*._,:-]+") ? normalized : null;
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private String revision(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,239}")
                ? value : null;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
    }
}
