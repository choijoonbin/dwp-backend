package com.dwp.services.meeting.security;

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
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MeetingSecurityFilter extends OncePerRequestFilter {

    private static final String SERVICE_TOKEN = "X-DWP-Service-Token";
    private static final String USER = "X-DWP-User-ID";
    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String ROLES = "X-DWP-Roles";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String GROUPS = "X-DWP-Group-Refs";
    private static final String PERSON = "X-DWP-Person-Public-ID";
    private static final String DISPLAY_NAME = "X-DWP-Display-Name-B64";

    private final String serviceToken;
    private final ObjectMapper objectMapper;

    public MeetingSecurityFilter(
            @Value("${dwp.meeting.service-token:}") String serviceToken,
            ObjectMapper objectMapper) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.startsWith("/v3/api-docs")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (serviceToken.isBlank()) {
            writeError(response, ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Meeting service identity is not configured.");
            return;
        }
        if (!constantTimeEquals(serviceToken, request.getHeader(SERVICE_TOKEN))) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Trusted Meeting service identity is required.");
            return;
        }

        Long userId = positiveLong(request.getHeader(USER));
        Long tenantId = positiveLong(request.getHeader(TENANT));
        Set<String> permissions = parse(request.getHeader(PERMISSIONS));
        if (userId == null || tenantId == null) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Verified user and tenant identity are required.");
            return;
        }
        if (!authorized(request, permissions)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The Meeting permission required for this operation is missing.");
            return;
        }

        MeetingRequestContext.set(new MeetingRequestContext.Subject(
                userId,
                tenantId,
                uuid(request.getHeader(PERSON)),
                decoded(request.getHeader(DISPLAY_NAME)),
                parse(request.getHeader(ROLES)),
                permissions,
                parse(request.getHeader(GROUPS))));
        try {
            filterChain.doFilter(request, response);
        } finally {
            MeetingRequestContext.clear();
        }
    }

    private boolean authorized(HttpServletRequest request, Set<String> permissions) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/v1/admin/")) {
            return has(permissions, "ADMIN.MEETINGS", readOnly(method) ? "VIEW" : "MANAGE");
        }
        if (readOnly(method)) return has(permissions, "APP.MEETINGS", "VIEW");
        if (path.equals("/v1/meetings") || path.equals("/v1/meetings/instant")) {
            return has(permissions, "APP.MEETINGS", "CREATE", "MANAGE");
        }
        return has(permissions, "APP.MEETINGS", "UPDATE", "MANAGE");
    }

    private boolean has(Set<String> permissions, String resource, String... actions) {
        return Arrays.stream(actions)
                .anyMatch(action -> permissions.contains(resource + ":" + action));
    }

    private boolean readOnly(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private Set<String> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(candidate -> !candidate.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Long positiveLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String decoded(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
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
