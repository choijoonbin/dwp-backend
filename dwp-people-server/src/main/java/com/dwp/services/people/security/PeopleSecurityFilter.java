package com.dwp.services.people.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.people.hr.performance.compensationplanning.ApprovedCompensationSnapshotRequestContext;
import com.dwp.services.people.hris.contracts.hrm011.Hrm011WorkloadRequestContext;
import com.dwp.services.people.hris.contracts.tim003.TimeAbsenceOwnerRequestContext;
import com.dwp.services.people.hris.separation.hrm013.Hrm013CallbackIngressFilter;
import com.dwp.services.people.hris.separation.hrm013.Hrm013CallbackRequestContext;
import com.dwp.services.people.integration.LocalSyntheticPeopleWorkforceBootstrapPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
public class PeopleSecurityFilter extends OncePerRequestFilter {

    static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    static final String USER_HEADER = "X-DWP-User-ID";
    static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    static final String ROLES_HEADER = PeopleSecurityHeaders.ROLES;
    static final String PERMISSIONS_HEADER = PeopleSecurityHeaders.PERMISSIONS;
    static final String SUPPORT_SESSION_HEADER = PeopleSecurityHeaders.SUPPORT_SESSION;
    static final String SUPPORT_SCOPES_HEADER = PeopleSecurityHeaders.SUPPORT_SCOPES;
    static final String ACTOR_TENANT_HEADER = "X-DWP-Actor-Tenant-ID";
    static final String PERSON_PUBLIC_ID_HEADER = "X-DWP-Person-Public-ID";
    static final String LEGACY_ROLE_FALLBACK_HEADER = "X-DWP-Legacy-Role-Fallback-Allowed";
    private static final String GATEWAY_SERVICE_IDENTITY = "dwp-gateway";
    private static final String PLATFORM_SERVICE_IDENTITY = "dwp-platform-server";
    private static final Set<String> PRODUCT_SURFACE_ELIGIBILITY_AUDIENCES = Set.of(
            GATEWAY_SERVICE_IDENTITY, PLATFORM_SERVICE_IDENTITY);
    private static final String PRODUCT_SURFACE_ELIGIBILITY_PATH =
            "/internal/people/v1/product-surface-eligibility/evaluate";
    private static final String HRIS_NAVIGATION_LEGAL_ENTITY_VERIFY_PATH =
            "/internal/people/v1/hris/navigation/legal-entity-context/verify";
    private static final Set<String> ADMIN_ROLES =
            Set.of("ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN", "HR_ADMIN", "PEOPLE_ADMIN");
    private static final Set<String> WORKFORCE_ROLES =
            Set.of("ADMIN", "HR_ADMIN", "PEOPLE_ADMIN");
    private final String serviceToken;
    private final ObjectMapper objectMapper;
    private final boolean employeeChangeEnabled;

    @Autowired
    public PeopleSecurityFilter(
            @Value("${dwp.people.service-token:}") String serviceToken,
            ObjectMapper objectMapper,
            @Value("${dwp.hris.employee-change.enabled:false}")
            boolean employeeChangeEnabled) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.objectMapper = objectMapper;
        this.employeeChangeEnabled = employeeChangeEnabled;
    }

    PeopleSecurityFilter(String serviceToken, ObjectMapper objectMapper) {
        this(serviceToken, objectMapper, false);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/internal/provider/")
                || ApprovedCompensationSnapshotRequestContext.isVerifiedRequest(request)
                || Hrm011WorkloadRequestContext.isVerifiedRequest(request)
                || TimeAbsenceOwnerRequestContext.isVerifiedRequest(request)
                || Hrm013CallbackRequestContext.isVerifiedRequest(request)
                || isAuthorizedSyntheticBootstrap(request, path)
                || isHomeProviderPost(request, path)
                || employeeChangeEnabled
                        && HrisEmployeeChangeSecurityBridge.isApprovalDecisionCallback(
                                request.getMethod(), path, request.getQueryString())
                || path.equals("/error");
    }

    private boolean isAuthorizedSyntheticBootstrap(
            HttpServletRequest request,
            String path) {
        return "POST".equals(request.getMethod())
                && LocalSyntheticPeopleWorkforceBootstrapPaths.PATH.equals(path)
                && Boolean.TRUE.equals(request.getAttribute(
                        LocalSyntheticPeopleWorkforceBootstrapPaths.AUTHORIZED_REQUEST_ATTRIBUTE));
    }

    private boolean isHomeProviderPost(HttpServletRequest request, String path) {
        return "POST".equals(request.getMethod())
                && (HomeWidgetProviderContract.BATCH_PATH.equals(path)
                || HomeWidgetProviderContract.COMMAND_PATH.equals(path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (Hrm013CallbackIngressFilter.isCallbackPath(request.getRequestURI())) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Verified HRM-013 workload authority is required.");
            return;
        }
        if (HrisEmployeeChangeSecurityBridge.claimsApprovalCallbackFamily(
                request.getRequestURI())) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Verified HRIS approval workload authority is required.");
            return;
        }
        if (serviceToken.isBlank()) {
            writeError(response, ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "People service identity is not configured.");
            return;
        }
        if (!constantTimeEquals(serviceToken, request.getHeader(SERVICE_TOKEN_HEADER))) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Trusted people service identity is required.");
            return;
        }
        if (PRODUCT_SURFACE_ELIGIBILITY_PATH.equals(request.getRequestURI())
                && !exactEligibilityAudience(request)) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "One exact trusted eligibility service identity is required.");
            return;
        }
        if (HRIS_NAVIGATION_LEGAL_ENTITY_VERIFY_PATH.equals(request.getRequestURI())
                && (!constantTimeEquals(serviceToken,
                        exactHeader(request, SERVICE_TOKEN_HEADER))
                    || !PLATFORM_SERVICE_IDENTITY.equals(
                        exactHeader(request, SERVICE_IDENTITY_HEADER)))) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "One exact trusted Platform service identity is required.");
            return;
        }

        Long actorId = positiveLong(request.getHeader(USER_HEADER));
        Long tenantId = positiveLong(request.getHeader(TENANT_HEADER));
        Set<String> roles = parseRoles(request.getHeader(ROLES_HEADER));
        Set<String> permissions = parseRoles(request.getHeader(PERMISSIONS_HEADER));
        boolean legacyRoleFallbackAllowed = Boolean.parseBoolean(
                request.getHeader(LEGACY_ROLE_FALLBACK_HEADER));
        UUID personPublicId = uuid(request.getHeader(PERSON_PUBLIC_ID_HEADER));
        if (actorId == null || tenantId == null) {
            writeError(response, ErrorCode.UNAUTHORIZED,
                    "Verified user and tenant identity are required.");
            return;
        }
        boolean supportAccess = request.getHeader(SUPPORT_SESSION_HEADER) != null
                && !request.getHeader(SUPPORT_SESSION_HEADER).isBlank();
        boolean exactHcmEvidence = exactHcmEvidence(request);
        String employeeChangeRoute = HrisEmployeeChangeSecurityBridge.browserRouteContract(
                request.getMethod(), request.getRequestURI());
        if (HrisEmployeeChangeSecurityBridge.claimsBrowserFamily(
                request.getRequestURI())
                && (employeeChangeRoute == null
                || !exactHcmEvidence(request, employeeChangeRoute))) {
            writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Exact v36 employee-change route authority is required.");
            return;
        }
        if (supportAccess && !authorizedSupportRequest(request)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The support session does not permit this workforce resource.");
            return;
        }
        boolean workforceAdmin = request.getRequestURI().startsWith("/v1/admin/workforce/");
        boolean workforceAdminPermission = hasPermission(
                permissions,
                "ADMIN.WORKFORCE_ACCESS",
                isReadOnly(request) ? Set.of("VIEW", "MANAGE") : Set.of("MANAGE"));
        boolean legacyAdminRole = legacyRoleFallbackAllowed && permissions.isEmpty()
                && roles.stream().anyMatch(ADMIN_ROLES::contains);
        if (!supportAccess && request.getRequestURI().startsWith("/v1/admin/")
                && !legacyAdminRole
                && !(workforceAdmin && workforceAdminPermission)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "People administrator permission is required.");
            return;
        }
        boolean workforcePermission = hasPermission(
                permissions,
                "DATA.WORKFORCE",
                isReadOnly(request) ? Set.of("VIEW", "MANAGE") : Set.of("MANAGE"));
        boolean legacyWorkforceRole = legacyRoleFallbackAllowed && permissions.isEmpty()
                && roles.stream().anyMatch(WORKFORCE_ROLES::contains);
        if (!exactHcmEvidence && !supportAccess
                && request.getRequestURI().startsWith("/v1/workforce/")
                && !legacyWorkforceRole
                && !workforcePermission) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Workforce operations permission is required.");
            return;
        }
        boolean hcmPermission = hasPermission(
                permissions,
                "APP.HCM",
                Set.of("VIEW", "MANAGE")) || hasPermission(
                permissions,
                "APP.HRIS",
                Set.of("VIEW", "MANAGE"));
        boolean legacyHcmRole = legacyRoleFallbackAllowed && permissions.isEmpty()
                && (roles.contains("WORKSPACE_MEMBER")
                    || roles.stream().anyMatch(WORKFORCE_ROLES::contains));
        if (!exactHcmEvidence && request.getRequestURI().startsWith("/v1/hr/")
                && (!supportAccess && !hcmPermission && !legacyHcmRole)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "HR application permission is required.");
            return;
        }

        PeopleRequestContext.set(actorId, tenantId, personPublicId, roles, permissions);
        try {
            filterChain.doFilter(request, response);
        } finally {
            PeopleRequestContext.clear();
        }
    }

    private boolean exactHcmEvidence(HttpServletRequest request) {
        String state = exactHeader(request, PeopleSecurityHeaders.ROLLOUT_STATE);
        String route = exactHeader(request, PeopleSecurityHeaders.ROUTE_CONTRACT);
        return state != null && state.matches("1[1][01]")
                && route != null && route.startsWith("route.hcm.");
    }

    private boolean exactEligibilityAudience(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return false;
        String token = exactHeader(request, SERVICE_TOKEN_HEADER);
        String identity = exactHeader(request, SERVICE_IDENTITY_HEADER);
        String tenant = exactHeader(request, TENANT_HEADER);
        String actor = exactHeader(request, USER_HEADER);
        String subject = exactHeader(request, PERSON_PUBLIC_ID_HEADER);
        return constantTimeEquals(serviceToken, token)
                && identity != null
                && PRODUCT_SURFACE_ELIGIBILITY_AUDIENCES.contains(identity)
                && canonicalPositiveLong(tenant) != null
                && canonicalPositiveLong(actor) != null
                && canonicalUuid(subject) != null;
    }

    private Long canonicalPositiveLong(String value) {
        Long parsed = positiveLong(value);
        return parsed != null && Long.toString(parsed).equals(value) ? parsed : null;
    }

    private boolean exactHcmEvidence(HttpServletRequest request, String expectedRoute) {
        return exactHcmEvidence(request) && expectedRoute.equals(
                exactHeader(request, PeopleSecurityHeaders.ROUTE_CONTRACT));
    }

    private String exactHeader(HttpServletRequest request, String name) {
        java.util.Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank()
                || !value.equals(value.trim()) || value.indexOf(',') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return null;
        }
        return value;
    }

    private Set<String> parseRoles(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean hasPermission(
            Set<String> permissions,
            String resource,
            Set<String> allowedActions) {
        return allowedActions.stream()
                .anyMatch(action -> permissions.contains(resource + ":" + action));
    }

    private boolean isReadOnly(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    }

    private boolean authorizedSupportRequest(HttpServletRequest request) {
        if (positiveLong(request.getHeader(ACTOR_TENANT_HEADER)) == null) return false;
        if (!("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))) return false;
        if (!parseRoles(request.getHeader(SUPPORT_SCOPES_HEADER)).contains("WORKFORCE_READ")) {
            return false;
        }
        // The generated HCM contract declares future support projections, but the
        // service does not yet carry a trusted population boundary or execute a
        // response field mask. No People endpoint is therefore safe to expose.
        return false;
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
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private UUID canonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode, String message)
            throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(errorCode, message));
    }
}
