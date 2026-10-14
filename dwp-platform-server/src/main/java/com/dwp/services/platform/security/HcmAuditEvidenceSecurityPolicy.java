package com.dwp.services.platform.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.security.RolePlaneBoundary;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Closed owner-side policy for the two read-only HCM audit evidence routes.
 * The product query parameter selects this projection; it never supplies authority.
 */
final class HcmAuditEvidenceSecurityPolicy {

    static final String PAGE_ROUTE = "route.hcm.audit.evidence.page";
    static final String DETAIL_ROUTE = "route.hcm.audit.evidence-detail.data";
    static final String PURPOSE = "HRIS_AUDIT_EVIDENCE";
    static final String CONTEXT = "hcm.management";
    static final String HCM_ROOT_RESOURCE_SET = "RS_HCM_CONFIG";

    private static final String EVENTS_PATH = "/v1/admin/audit-control/events";
    private static final Pattern DETAIL_PATH = Pattern.compile(
            "^/v1/admin/audit-control/events/"
                    + "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-"
                    + "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private static final Set<String> LIST_PARAMETERS = Set.of(
            "product", "window", "category", "severity", "outcome",
            "sourceService", "query", "size", "cursor");
    private static final Set<String> DETAIL_PARAMETERS = Set.of("product");
    static final Set<String> CONFLICTING_PACKAGES = Set.of(
            "HRIS_LINE_MANAGER",
            "HRIS_HR_BUSINESS_PARTNER",
            "HRIS_HR_LIFECYCLE_APPROVER",
            "HRIS_HR_SENSITIVE_DATA_READER",
            "HRIS_HR_COMPENSATION_READER",
            "HRIS_WORKFORCE_EXPORT_OPERATOR",
            "HRIS_TIMEKEEPER",
            "HRIS_TIME_APPROVER",
            "HRIS_TIME_CONTROLLER",
            "HRIS_LEAVE_ADMINISTRATOR",
            "HRIS_LEAVE_APPROVER",
            "HRIS_LEAVE_BALANCE_ADJUSTER",
            "HRIS_LEAVE_ADJUSTMENT_APPROVER",
            "HRIS_PAYROLL_SPECIALIST",
            "HRIS_PAYROLL_RUN_OPERATOR",
            "HRIS_PAYROLL_VALIDATOR",
            "HRIS_PAYROLL_CONTROLLER",
            "HRIS_PAYROLL_RELEASE_OFFICER",
            "HRIS_PAYROLL_ACCOUNTING",
            "HRIS_BANK_TAX_MAINTAINER",
            "HRIS_STATUTORY_OPERATOR",
            "HRIS_PERFORMANCE_OPERATOR",
            "HRIS_PERFORMANCE_CALIBRATOR",
            "HRIS_PERFORMANCE_RESULT_PUBLISHER",
            "HRIS_CONFIG_AUTHOR",
            "HRIS_CONFIG_PUBLISHER",
            "HRIS_INTEGRATION_AUTHOR",
            "HRIS_INTEGRATION_EXECUTOR");

    boolean auditControlPath(String path) {
        return path != null && (path.equals("/v1/admin/audit-control")
                || path.startsWith("/v1/admin/audit-control/"));
    }

    boolean sharedEvidencePath(String path) {
        return routeFor(path) != null;
    }

    boolean auditProjectionAttempt(HttpServletRequest request) {
        return auditControlPath(request.getRequestURI()) && hasProductParameter(request);
    }

    boolean exactHcmRouteCandidate(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && routeFor(request.getRequestURI()) != null
                && exactParameter(request, "product", "HCM")
                && exactRawProduct(request);
    }

    boolean hasProductParameter(HttpServletRequest request) {
        return request.getParameterMap().containsKey("product");
    }

    boolean carriesHrisAuditorEvidence(HttpServletRequest request) {
        return tokens(request.getHeader(PlatformSecurityHeaders.ROLES))
                .contains("HRIS_AUDITOR")
                || !ResourceRoleAuthorization.resourcesFor(
                request.getHeader(PlatformSecurityHeaders.RESOURCE_ROLES),
                "HRIS_DUTY_AUDITOR").isEmpty();
    }

    Evaluation validateRequestShape(HttpServletRequest request) {
        String expectedRoute = routeFor(request.getRequestURI());
        if (!"GET".equals(request.getMethod()) || expectedRoute == null
                || !exactParameter(request, "product", "HCM")
                || !exactRawProduct(request)
                || !allowedParameters(request, expectedRoute)) {
            return Evaluation.denied(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The HCM audit evidence route or query is invalid.");
        }
        return Evaluation.allowed(expectedRoute);
    }

    Evaluation evaluate(
            HttpServletRequest request,
            PlatformProductAuthorizationSupport.TrustedAuthorityEvidence authority) {
        Evaluation shape = validateRequestShape(request);
        if (!shape.allowed()) return shape;
        String expectedRoute = shape.routeContractKey();
        if (authority == null
                || !expectedRoute.equals(authority.routeContractKey())
                || !CONTEXT.equals(authority.contextKey())
                || authority.scopeKey() == null
                || authority.scopeKey().isBlank()) {
            return Evaluation.denied(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Current HCM audit route authority is required.");
        }
        if (!tenantIdentity(request)
                || !hasAuthority(request, "APP.HCM", "VIEW")
                || !hasAuthority(request, "ADMIN.AUDIT_VIEW", "VIEW")
                || !tokens(request.getHeader(PlatformSecurityHeaders.ROLES))
                .contains("HRIS_AUDITOR")
                || !ResourceRoleAuthorization.has(
                request.getHeader(PlatformSecurityHeaders.RESOURCE_ROLES),
                "HRIS_DUTY_AUDITOR", HCM_ROOT_RESOURCE_SET)
                || !PURPOSE.equals(exactHeader(request, PlatformSecurityHeaders.PURPOSE))
                || !Set.of("NORMAL", "ELEVATED").contains(exactHeader(
                request, PlatformSecurityHeaders.ACTIVE_ACCESS_MODE))
                || !validUuid(exactHeader(
                request, PlatformSecurityHeaders.SUBJECT_PRINCIPAL_PUBLIC_ID))
                || !positiveRevision(exactHeader(
                request, PlatformSecurityHeaders.FIELD_POLICY_REVISION))
                || !positiveRevision(exactHeader(
                request, PlatformSecurityHeaders.AUTHORIZATION_REVISION))
                || tokens(request.getHeader(PlatformSecurityHeaders.OMITTED_FIELD_GROUPS))
                .contains("AUDIT_PROJECTION")) {
            return Evaluation.denied(
                    ErrorCode.FORBIDDEN,
                    "The exact HCM audit evidence authority is required.");
        }
        if (!Collections.disjoint(
                tokens(request.getHeader(PlatformSecurityHeaders.ROLES)),
                CONFLICTING_PACKAGES)) {
            return Evaluation.denied(
                    ErrorCode.SOD_CONFLICT,
                    "HCM audit independence policy prevents this request.");
        }
        return Evaluation.allowed(expectedRoute);
    }

    private boolean tenantIdentity(HttpServletRequest request) {
        return "TENANT".equals(exactHeader(request, "X-DWP-Identity-Plane"))
                && headerAbsent(request, PlatformSecurityHeaders.SUPPORT_SESSION)
                && headerAbsent(request, PlatformSecurityHeaders.ACTOR_TENANT)
                && headerAbsent(request, "X-DWP-Provider-Tenant-ID")
                && !RolePlaneBoundary.isProviderIdentity(
                tokens(request.getHeader(PlatformSecurityHeaders.ROLES)));
    }

    private boolean exactRawProduct(HttpServletRequest request) {
        String rawQuery = request.getQueryString();
        if (rawQuery == null) return true;
        int matches = 0;
        for (String component : rawQuery.split("&", -1)) {
            if ("product=HCM".equals(component)) matches++;
        }
        return matches == 1;
    }

    private boolean headerAbsent(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        return values == null || !values.hasMoreElements();
    }

    private boolean allowedParameters(HttpServletRequest request, String route) {
        Set<String> allowed = PAGE_ROUTE.equals(route)
                ? LIST_PARAMETERS : DETAIL_PARAMETERS;
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (!allowed.contains(name)) return false;
            String[] values = request.getParameterValues(name);
            if (values == null || values.length != 1) return false;
        }
        return true;
    }

    private String routeFor(String path) {
        if (EVENTS_PATH.equals(path)) return PAGE_ROUTE;
        if (path != null && DETAIL_PATH.matcher(path).matches()) return DETAIL_ROUTE;
        return null;
    }

    private boolean exactParameter(
            HttpServletRequest request, String name, String expected) {
        String[] values = request.getParameterValues(name);
        return values != null && values.length == 1 && expected.equals(values[0]);
    }

    private boolean hasAuthority(
            HttpServletRequest request, String resource, String action) {
        return tokens(request.getHeader(PlatformSecurityHeaders.PERMISSIONS))
                .contains((resource + ":" + action).toUpperCase(Locale.ROOT));
    }

    private Set<String> tokens(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String exactHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank()
                || value.length() > 512 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf(',') >= 0) {
            return null;
        }
        return value.trim();
    }

    private boolean positiveRevision(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean validUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString()
                    .equals(value.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    record Evaluation(
            boolean allowed,
            String routeContractKey,
            ErrorCode errorCode,
            String message) {

        static Evaluation allowed(String routeContractKey) {
            return new Evaluation(true, routeContractKey, null, null);
        }

        static Evaluation denied(ErrorCode errorCode, String message) {
            return new Evaluation(false, null, errorCode, message);
        }
    }
}
