package com.dwp.services.platform.security;

import jakarta.servlet.http.HttpServletRequest;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Shared validation for trusted product rollout and decision evidence at Platform. */
final class PlatformProductAuthorizationSupport {

    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");

    private final PlatformCanaryPepRegistry canaryRegistry;
    private final PlatformApprovalsPepRegistry approvalsRegistry;

    PlatformProductAuthorizationSupport(
            PlatformCanaryPepRegistry canaryRegistry,
            PlatformApprovalsPepRegistry approvalsRegistry) {
        this.canaryRegistry = canaryRegistry;
        this.approvalsRegistry = approvalsRegistry;
    }

    TrustedAuthorityEvidence trustedAuthority(HttpServletRequest request) {
        String route = exactHeader(request, PlatformSecurityHeaders.ROUTE_CONTRACT);
        String revision = exactHeader(
                request, PlatformSecurityHeaders.CURRENT_DECISION_REVISION);
        String context = exactHeader(request, PlatformSecurityHeaders.CONTEXT);
        String scope = exactHeader(request, PlatformSecurityHeaders.SCOPE);
        OffsetDateTime revalidateAt = instant(exactHeader(
                request, PlatformSecurityHeaders.CURRENT_REVALIDATE_AT));
        if (!trustedText(route) || revision == null
                || !revision.matches("psr-[a-f0-9]{64}")
                || !trustedText(context) || !trustedText(scope)
                || revalidateAt == null || !revalidateAt.isAfter(OffsetDateTime.now())) {
            return null;
        }
        return new TrustedAuthorityEvidence(
                route, revision, revalidateAt, context, scope);
    }

    boolean validRolloutEvidence(String state, String revision, String cohort) {
        return state != null && cohort != null
                && ROLLOUT_STATES.contains(state)
                && ROLLOUT_COHORTS.contains(cohort)
                && revision != null
                && revision.matches("rollout-[a-f0-9]{64}");
    }

    boolean stateChangingCanary(List<String> routeKeys) {
        return canaryRegistry.bindingContracts().stream()
                .anyMatch(binding -> routeKeys.contains(binding.routeContractKey())
                        && "ACTION".equals(binding.routeKind()));
    }

    boolean stateChangingApproval(List<String> routeKeys) {
        return approvalsRegistry.bindingContracts().stream()
                .anyMatch(binding -> routeKeys.contains(binding.routeContractKey())
                        && "ACTION".equals(binding.routeKind()));
    }

    boolean legacyProductAuthorized(
            HttpServletRequest request, boolean supportAccess) {
        if (supportAccess) return true;
        String path = request.getRequestURI();
        String method = request.getMethod();
        String permission = request.getHeader(PlatformSecurityHeaders.PERMISSIONS);
        if (path.startsWith("/v1/communications")) {
            return hasAuthority(permission, "APP.COMMUNICATIONS",
                    read(method) ? "VIEW" : "UPDATE");
        }
        if (path.startsWith("/v1/admin/announcements")) {
            String action = read(method) ? "VIEW"
                    : path.endsWith("/publish") || path.endsWith("/archive")
                    ? "APPROVE" : "POST".equals(method) ? "CREATE" : "UPDATE";
            return hasAuthority(permission, "ADMIN.COMMUNICATIONS", action)
                    && hasResponsibility(request, "RS_COMMUNICATIONS");
        }
        if (path.startsWith("/v1/services")) {
            return hasAuthority(permission, "APP.EMPLOYEE_SERVICES",
                    read(method) ? "VIEW" : "UPDATE");
        }
        if (path.startsWith("/v1/admin/services/catalog")) {
            String action = read(method) ? "VIEW"
                    : "POST".equals(method) ? "CREATE" : "UPDATE";
            return hasAuthority(permission, "ADMIN.SERVICE_CATALOG", action)
                    && hasResponsibility(request, "RS_SERVICES");
        }
        if (path.startsWith("/v1/admin/services/requests")) {
            return hasAuthority(permission, "ADMIN.SERVICE_OPERATIONS",
                    read(method) ? "VIEW" : "UPDATE")
                    && hasResponsibility(request, "RS_SERVICES");
        }
        return false;
    }

    String exactHeader(HttpServletRequest request, String name) {
        java.util.Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || !trustedText(value)) return null;
        return value.trim();
    }

    private boolean hasResponsibility(HttpServletRequest request, String resourceSetKey) {
        return ResourceRoleAuthorization.has(
                request.getHeader(PlatformSecurityHeaders.RESOURCE_ROLES),
                "APP_CONFIG_ADMIN", resourceSetKey);
    }

    private boolean hasAuthority(String header, String resourceKey, String action) {
        if (header == null || header.isBlank()) return false;
        String expected = resourceKey.toUpperCase() + ":" + action.toUpperCase();
        return Arrays.stream(header.split(","))
                .map(String::trim).map(String::toUpperCase).anyMatch(expected::equals);
    }

    private boolean read(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    private OffsetDateTime instant(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private boolean trustedText(String value) {
        return value != null && !value.isBlank() && value.length() <= 200
                && value.indexOf(',') < 0 && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    record TrustedAuthorityEvidence(
            String routeContractKey,
            String currentRevision,
            OffsetDateTime revalidateAt,
            String contextKey,
            String scopeKey) {
    }
}
