package com.dwp.services.platform.security;

import com.dwp.core.security.RolePlaneBoundary;
import com.dwp.services.platform.servicecenter.ServicesProductSurfacePepFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.filter.OncePerRequestFilter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

abstract class PlatformSecurityPolicySupport extends OncePerRequestFilter {

    static final String SERVICE_TOKEN_HEADER = PlatformSecurityHeaders.SERVICE_TOKEN;
    static final String USER_HEADER = PlatformSecurityHeaders.USER;
    static final String TENANT_HEADER = PlatformSecurityHeaders.TENANT;
    static final String ROLES_HEADER = PlatformSecurityHeaders.ROLES;
    static final String PERMISSIONS_HEADER = PlatformSecurityHeaders.PERMISSIONS;
    static final String RESOURCE_ROLES_HEADER = PlatformSecurityHeaders.RESOURCE_ROLES;
    static final String SUPPORT_SESSION_HEADER = PlatformSecurityHeaders.SUPPORT_SESSION;
    static final String SUPPORT_SCOPES_HEADER = PlatformSecurityHeaders.SUPPORT_SCOPES;
    static final String ACTOR_TENANT_HEADER = PlatformSecurityHeaders.ACTOR_TENANT;
    static final String ROUTE_CONTRACT_HEADER = PlatformSecurityHeaders.ROUTE_CONTRACT;
    static final String CURRENT_DECISION_REVISION_HEADER =
            PlatformSecurityHeaders.CURRENT_DECISION_REVISION;
    static final String CURRENT_REVALIDATE_AT_HEADER =
            PlatformSecurityHeaders.CURRENT_REVALIDATE_AT;
    static final String EXPECTED_DECISION_REVISION_HEADER =
            PlatformSecurityHeaders.EXPECTED_DECISION_REVISION;
    static final String CONTEXT_HEADER = PlatformSecurityHeaders.CONTEXT;
    static final String SCOPE_HEADER = PlatformSecurityHeaders.SCOPE;
    static final String RESPONSE_DECISION_REVISION_HEADER =
            PlatformSecurityHeaders.RESPONSE_DECISION_REVISION;
    static final String ROLLOUT_COHORT_HEADER = PlatformSecurityHeaders.ROLLOUT_COHORT;
    static final String ROLLOUT_REVISION_HEADER = PlatformSecurityHeaders.ROLLOUT_REVISION;
    static final String ROLLOUT_STATE_HEADER = PlatformSecurityHeaders.ROLLOUT_STATE;
    static final String CONTROL_PLANE_HEADER = "X-DWP-Control-Plane";
    static final String WIDGET_OWNER_SCOPE_HEADER =
            "X-DWP-Widget-Owner-Product-Keys";
    protected static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN");
    protected static final Set<String> PERSONAL_PREFERENCE_CODE_SET_PATHS = Set.of(
            "/v1/catalog/code-sets/PLATFORM.PREFERENCE.COLOR_MODE",
            "/v1/catalog/code-sets/PLATFORM.PREFERENCE.DENSITY",
            "/v1/catalog/code-sets/PLATFORM.PREFERENCE.TIME_ZONE",
            "/v1/catalog/code-sets/PLATFORM.PREFERENCE.DATE_FORMAT",
            "/v1/catalog/code-sets/PLATFORM.PREFERENCE.TIME_FORMAT",
            "/v1/catalog/code-sets/PLATFORM.PREFERENCE.FIRST_DAY_OF_WEEK",
            "/v1/catalog/code-sets/PLATFORM.PREFERENCE.NUMBER_FORMAT");
    protected static final Set<String> AUDIT_VIEW_CODE_SET_PATHS = Set.of(
            "/v1/catalog/code-sets/PLATFORM.AUDIT.WINDOW",
            "/v1/catalog/code-sets/PLATFORM.AUDIT.CATEGORY_FILTER",
            "/v1/catalog/code-sets/PLATFORM.AUDIT.SEVERITY_FILTER",
            "/v1/catalog/code-sets/PLATFORM.AUDIT.OUTCOME_FILTER",
            "/v1/catalog/code-sets/PLATFORM.EVENT_ENVELOPE.DOMAIN",
            "/v1/catalog/code-sets/PLATFORM.EVENT_ENVELOPE.CLASSIFICATION",
            "/v1/catalog/code-sets/PLATFORM.SYS_AUDIT_EXPORT_JOBS.FORMAT");
    protected static final Set<String> API_MONITORING_CODE_SET_PATHS = Set.of(
            "/v1/catalog/code-sets/PLATFORM.API_HISTORY.WINDOW",
            "/v1/catalog/code-sets/PLATFORM.API_HISTORY.OBSERVATION_POINT_FILTER",
            "/v1/catalog/code-sets/PLATFORM.API_HISTORY.HTTP_METHOD_FILTER",
            "/v1/catalog/code-sets/PLATFORM.API_HISTORY.OUTCOME_FILTER");
    protected static final String RUNTIME_CODE_SET_PATH_PREFIX = "/v1/catalog/code-sets/";
    private static final String SUPPORT_EXPERIENCE_PREVIEW_PATH =
            "/v1/admin/tenant-experience-preview";

    protected static boolean providerWidgetRegistryPath(String path) {
        return path.equals("/v1/admin/widget-definitions")
                || path.startsWith("/v1/admin/widget-definitions/")
                || path.startsWith("/v1/admin/widget-definition-versions/")
                || path.equals("/v1/admin/widget-runtime-controls")
                || path.startsWith("/v1/admin/widget-runtime-controls/")
                || path.startsWith("/v1/admin/widget-registry/");
    }

    protected boolean validProviderOwnerScope(HttpServletRequest request) {
        var values = request.getHeaders(WIDGET_OWNER_SCOPE_HEADER);
        List<String> headers = values == null ? List.of() : Collections.list(values);
        if (headers.size() != 1 || headers.getFirst().length() > 3_872) return false;
        Set<String> owners = parseValues(headers.getFirst());
        return !owners.isEmpty()
                && owners.size() <= 32
                && owners.stream().allMatch(owner -> owner.length() <= 120
                        && owner.matches("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$"));
    }

    protected boolean hasScopedAppAccess(HttpServletRequest request) {
        String resourceRoles = request.getHeader(RESOURCE_ROLES_HEADER);
        if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) {
            return hasRole(request.getHeader(ROLES_HEADER), Set.of("APP_CATALOG_ADMIN"))
                    || !ResourceRoleAuthorization.resourcesFor(
                    resourceRoles, "APP_OWNER", "APP_ACCESS_MANAGER",
                    "APP_ACCESS_APPROVER", "APP_ACCESS_REVIEWER").isEmpty();
        }
        String path = request.getRequestURI();
        if (path.endsWith("/fulfillment") || path.endsWith("/revocation")) {
            return !ResourceRoleAuthorization.resourcesFor(
                    resourceRoles, "APP_ACCESS_MANAGER").isEmpty();
        }
        return !ResourceRoleAuthorization.resourcesFor(
                resourceRoles, "APP_ACCESS_APPROVER").isEmpty();
    }

    protected boolean hasPermission(String permissionsHeader, String resourceKey) {
        if (isBlank(permissionsHeader)) return false;
        String prefix = resourceKey.toUpperCase() + ":";
        return Arrays.stream(permissionsHeader.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .anyMatch(value -> value.startsWith(prefix));
    }

    protected boolean hasAuthority(
            String permissionsHeader,
            String resourceKey,
            String permissionCode) {
        if (isBlank(permissionsHeader)) return false;
        String expected = resourceKey.toUpperCase() + ":" + permissionCode.toUpperCase();
        return Arrays.stream(permissionsHeader.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .anyMatch(expected::equals);
    }

    protected boolean hasAnyAuthority(
            String permissionsHeader,
            String resourceKey,
            String... permissionCodes) {
        return Arrays.stream(permissionCodes)
                .anyMatch(code -> hasAuthority(permissionsHeader, resourceKey, code));
    }

    protected boolean hasTenantAdministrationAuthority(
            HttpServletRequest request,
            String resourceKey,
            boolean supportAccess) {
        if (supportAccess
                || !"TENANT".equals(request.getHeader("X-DWP-Identity-Plane"))
                || !isBlank(request.getHeader("X-DWP-Provider-Tenant-ID"))
                || !isBlank(request.getHeader(ACTOR_TENANT_HEADER))) {
            return false;
        }
        String permission = switch (request.getMethod()) {
            case "GET", "HEAD" -> "VIEW";
            case "POST", "PUT", "PATCH", "DELETE" -> "MANAGE";
            default -> null;
        };
        return permission != null
                && hasAuthority(request.getHeader(PERMISSIONS_HEADER), resourceKey, permission);
    }

    protected boolean hasApiHistoryAuthority(
            HttpServletRequest request,
            boolean supportAccess) {
        if (supportAccess
                || !"TENANT".equals(request.getHeader("X-DWP-Identity-Plane"))
                || !isBlank(request.getHeader("X-DWP-Provider-Tenant-ID"))
                || !isBlank(request.getHeader(ACTOR_TENANT_HEADER))) {
            return false;
        }
        String permission = switch (request.getMethod()) {
            case "GET", "HEAD" -> "VIEW";
            case "POST", "PUT", "PATCH", "DELETE" -> "MANAGE";
            default -> null;
        };
        return permission != null && hasAuthority(
                request.getHeader(PERMISSIONS_HEADER),
                "ADMIN.API_MONITORING",
                permission);
    }

    protected boolean hasPersonalRuntimeCodeSetAccess(
            HttpServletRequest request,
            boolean supportAccess) {
        return tenantRuntimeRead(request, supportAccess);
    }

    protected boolean hasAuditRuntimeCodeSetAccess(
            HttpServletRequest request,
            boolean supportAccess) {
        return tenantRuntimeRead(request, supportAccess)
                && hasAuthority(
                request.getHeader(PERMISSIONS_HEADER),
                "ADMIN.AUDIT_VIEW",
                "VIEW");
    }

    protected boolean hasApiMonitoringRuntimeCodeSetAccess(
            HttpServletRequest request,
            boolean supportAccess) {
        return tenantRuntimeRead(request, supportAccess)
                && hasAuthority(
                request.getHeader(PERMISSIONS_HEADER),
                "ADMIN.API_MONITORING",
                "VIEW");
    }

    protected boolean tenantRuntimeRead(
            HttpServletRequest request,
            boolean supportAccess) {
        return !supportAccess
                && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && "TENANT".equals(request.getHeader("X-DWP-Identity-Plane"))
                && isBlank(request.getHeader("X-DWP-Provider-Tenant-ID"))
                && isBlank(request.getHeader(ACTOR_TENANT_HEADER));
    }

    protected boolean canonicalRuntimeCodeSetPath(String path) {
        String codeSetKey = path.substring(RUNTIME_CODE_SET_PATH_PREFIX.length());
        return codeSetKey.matches("[A-Z][A-Z0-9_.]{2,99}");
    }

    protected String tenantAdministrationResource(String path) {
        if (pathFamily(path, "/v1/admin/tenant-branding")) {
            return "ADMIN.TENANT_BRANDING";
        }
        if (pathFamily(path, "/v1/admin/preference-exceptions")) {
            return "ADMIN.MANAGED_PREFERENCES";
        }
        if (pathFamily(path, "/v1/admin/localization")) {
            return "ADMIN.LOCALIZATION";
        }
        if (pathFamily(path, "/v1/admin/catalog")) {
            return "ADMIN.PLATFORM_CATALOG";
        }
        if (pathFamily(path, "/v1/admin/registry-entries")) {
            return "ADMIN.PLATFORM_REGISTRY";
        }
        if (pathFamily(path, "/v1/admin/navigation")) {
            return "ADMIN.NAVIGATION";
        }
        if (pathFamily(path, "/v1/admin/reference-sets")) {
            return "ADMIN.REFERENCE_DATA";
        }
        return null;
    }

    protected boolean hasCalendarAuthority(HttpServletRequest request) {
        String requiredPermission = switch (request.getMethod()) {
            case "GET", "HEAD" -> "VIEW";
            case "POST" -> request.getRequestURI().endsWith("/events") ? "CREATE" : "UPDATE";
            case "PUT", "PATCH", "DELETE" -> "UPDATE";
            default -> "VIEW";
        };
        return hasAuthority(
                request.getHeader(PERMISSIONS_HEADER), "APP.CALENDAR", requiredPermission);
    }

    protected boolean hasRoomsAuthority(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && "/v1/rooms/bookings".equals(path)
                && "route.workplace.work.reservations.page".equals(
                request.getHeader(ROUTE_CONTRACT_HEADER))) {
            return hasAuthority(
                    request.getHeader(PERMISSIONS_HEADER), "APP.WORKPLACE", "VIEW");
        }
        String requiredPermission = switch (request.getMethod()) {
            case "GET", "HEAD" -> "VIEW";
            case "POST" -> path.endsWith("/bookings")
                    || (path.startsWith("/v1/workplace/experience/facilities/") && path.endsWith("/requests"))
                    ? "CREATE" : "UPDATE";
            case "PUT", "PATCH", "DELETE" -> "UPDATE";
            default -> "VIEW";
        };
        return hasAuthority(
                request.getHeader(PERMISSIONS_HEADER), "APP.ROOMS", requiredPermission);
    }

    protected boolean hasRoomsAdminAuthority(HttpServletRequest request) {
        String path = request.getRequestURI();
        String requiredPermission = switch (request.getMethod()) {
            case "GET", "HEAD" -> "VIEW";
            case "POST" -> path.endsWith("/decision")
                    || path.endsWith("/trash")
                    || path.endsWith("/restore") ? "MANAGE" : "CREATE";
            case "PUT" -> path.endsWith("/policy") ? "MANAGE" : "UPDATE";
            default -> "MANAGE";
        };
        return hasAuthority(
                request.getHeader(PERMISSIONS_HEADER), "ADMIN.ROOMS", requiredPermission);
    }

    protected boolean hasWorkplaceAuthority(HttpServletRequest request) {
        String path = request.getRequestURI();
        String requiredPermission = switch (request.getMethod()) {
            case "GET", "HEAD" -> "VIEW";
            case "POST" -> path.endsWith("/bookings")
                    || (path.startsWith("/v1/workplace/experience/facilities/resources/")
                    && path.endsWith("/requests")) ? "CREATE" : "UPDATE";
            case "PUT", "PATCH", "DELETE" -> "UPDATE";
            default -> "VIEW";
        };
        return hasAuthority(
                request.getHeader(PERMISSIONS_HEADER), "APP.WORKPLACE", requiredPermission);
    }

    protected boolean hasWorkplaceAdminAuthority(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (isWorkplaceBoardReportExport(path)) {
            return hasAuthority(
                    request.getHeader(PERMISSIONS_HEADER), "ADMIN.WORKPLACE", "EXPORT");
        }
        String requiredPermission = switch (request.getMethod()) {
            case "GET", "HEAD" -> isWorkplaceConnectorReplayStatus(path)
                    ? "MANAGE" : "VIEW";
            case "POST" -> path.endsWith("/background")
                    ? "UPDATE"
                    : isWorkplaceGovernanceTransition(path) ? "MANAGE" : "CREATE";
            case "PUT" -> isSensitiveWorkplaceOperation(path) ? "MANAGE" : "UPDATE";
            default -> "MANAGE";
        };
        return hasAuthority(
                request.getHeader(PERMISSIONS_HEADER), "ADMIN.WORKPLACE", requiredPermission);
    }

    protected boolean isWorkplaceBoardReportExport(String path) {
        String base = "/v1/admin/workplace/space-planning/reports";
        return path.equals(base)
                || path.equals(base + ":preview")
                || path.matches("^" + base
                + "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}"
                + "-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}(/content)?$");
    }

    protected boolean isSensitiveWorkplaceOperation(String path) {
        return (path.startsWith("/v1/admin/workplace/experience/collaboration/connectors/"))
                || path.endsWith("/policy")
                || path.endsWith("/force-cancel")
                || path.endsWith("/legal-hold");
    }

    protected boolean isWorkplaceGovernanceTransition(String path) {
        return (path.startsWith("/v1/admin/workplace/experience/collaboration/")
                && (path.endsWith("/changes") || path.endsWith("/review")))
                || (path.startsWith("/v1/admin/workplace/connectors/")
                && (path.endsWith("/replays:preview") || path.endsWith("/replays")))
                || path.endsWith("/review")
                || path.endsWith("/publish")
                || path.endsWith("/restore");
    }

    protected boolean isWorkplaceConnectorReplayStatus(String path) {
        return path.matches(
                "^/v1/admin/workplace/connectors/[^/]+/replays/[^/]+$");
    }

    protected boolean hasCalendarAdminAuthority(HttpServletRequest request) {
        String path = request.getRequestURI();
        String requiredPermission = switch (request.getMethod()) {
            case "GET", "HEAD" -> "VIEW";
            case "POST" -> path.endsWith("/decision")
                    || path.endsWith("/trash")
                    || path.endsWith("/restore") ? "MANAGE" : "CREATE";
            case "PUT" -> path.endsWith("/policy") ? "MANAGE" : "UPDATE";
            default -> "MANAGE";
        };
        return hasAuthority(
                request.getHeader(PERMISSIONS_HEADER), "ADMIN.CALENDAR", requiredPermission);
    }

    protected boolean hasMailAuthority(HttpServletRequest request) {
        String path = request.getRequestURI();
        String requiredPermission = switch (request.getMethod()) {
            case "GET", "HEAD" -> "VIEW";
            case "POST" -> path.equals("/v1/mail/messages") ? "CREATE"
                    : path.endsWith("/replies")
                    || path.endsWith("/messages/advanced")
                    || path.matches(".*/contact-groups/[^/]+/messages$")
                    || path.endsWith("/retry") ? "SEND"
                    : path.matches(".*/proposals/[^/]+/(decision|handoff/cancel)$") ? "DECIDE"
                    : isMailCreatePath(path) ? "CREATE" : "UPDATE";
            case "DELETE" -> "DELETE";
            case "PUT", "PATCH" -> "UPDATE";
            default -> "VIEW";
        };
        return hasAuthority(
                request.getHeader(PERMISSIONS_HEADER), "APP.MAIL", requiredPermission);
    }

    protected boolean isMailCreatePath(String path) {
        return path.equals("/v1/mail/drafts")
                || path.equals("/v1/mail/saved-views")
                || path.equals("/v1/mail/templates")
                || path.equals("/v1/mail/signatures")
                || path.equals("/v1/mail/organization/folders")
                || path.equals("/v1/mail/organization/rules");
    }

    protected boolean hasMailAdminAuthority(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        String permissions = request.getHeader(PERMISSIONS_HEADER);
        if (("GET".equals(method) || "HEAD".equals(method))
                && path.startsWith("/v1/admin/mail/retention/purge-previews")) {
            return hasAnyAuthority(permissions, "ADMIN.MAIL",
                    "PURGE_PREVIEW", "PURGE_AUTHORIZE", "PURGE_EXECUTE");
        }
        if (("GET".equals(method) || "HEAD".equals(method))
                && path.startsWith("/v1/admin/mail/retention/purge-jobs")) {
            return hasAnyAuthority(permissions, "ADMIN.MAIL",
                    "PURGE_PREVIEW", "PURGE_AUTHORIZE", "PURGE_EXECUTE");
        }
        if (("GET".equals(method) || "HEAD".equals(method))
                && path.equals("/v1/admin/mail/delivery-audit")) {
            return hasAnyAuthority(permissions, "ADMIN.MAIL",
                    "AUDIT_READ", "DELIVERY_RECONCILE", "DELIVERY_RETRY",
                    "DELIVERY_CANCEL");
        }
        String requiredPermission = mailAdminPermission(
                method, path);
        return hasAuthority(
                permissions, "ADMIN.MAIL", requiredPermission);
    }

    protected String mailAdminPermission(String method, String path) {
        if (path.startsWith("/v1/admin/mail/writing-assets")) {
            if ("GET".equals(method) || "HEAD".equals(method)) return "VIEW";
            if (path.endsWith("/submit")) return "WRITING_ASSET_SUBMIT";
            if (path.endsWith("/approve")) return "WRITING_ASSET_APPROVE";
            if (path.endsWith("/publish")) return "WRITING_ASSET_PUBLISH";
            if (path.endsWith("/retire")) return "WRITING_ASSET_RETIRE";
            return "WRITING_ASSET_EDIT";
        }
        if (path.startsWith("/v1/admin/mail/delivery-audit")) {
            if (path.contains("/exports")) return "EVIDENCE_EXPORT";
            if ("GET".equals(method) || "HEAD".equals(method)) return "AUDIT_READ";
            if (path.endsWith("/reconcile")) return "DELIVERY_RECONCILE";
            if (path.endsWith("/retry")) return "DELIVERY_RETRY";
            if (path.endsWith("/cancel")) return "DELIVERY_CANCEL";
            return "AUDIT_READ";
        }
        if (path.startsWith("/v1/admin/mail/retention/holds")) {
            return "GET".equals(method) || "HEAD".equals(method)
                    ? "VIEW" : "HOLD_MANAGE";
        }
        if (path.startsWith("/v1/admin/mail/retention/hold-release-previews")) {
            return "HOLD_MANAGE";
        }
        if (path.startsWith("/v1/admin/mail/retention/evidence-exports")) {
            return "EVIDENCE_EXPORT";
        }
        if (path.startsWith("/v1/admin/mail/retention/purge-previews")) {
            return "PURGE_PREVIEW";
        }
        if (path.contains("/retention/purges/") && path.endsWith("/approvals")) {
            return "PURGE_AUTHORIZE";
        }
        if (path.startsWith("/v1/admin/mail/retention/purge-jobs")) return "PURGE_EXECUTE";
        if (path.contains("/retention/purges/") && path.endsWith("/execute")) {
            return "PURGE_EXECUTE";
        }
        if (path.startsWith("/v1/admin/mail/connections")) {
            return "GET".equals(method) || "HEAD".equals(method)
                    ? "VIEW" : "CONNECTION_MANAGE";
        }
        if (path.startsWith("/v1/admin/mail/shared-inboxes")) {
            if (path.equals("/v1/admin/mail/shared-inboxes/member-candidates")) {
                return "SHARED_INBOX_MANAGE";
            }
            return "GET".equals(method) || "HEAD".equals(method)
                    ? "VIEW" : "SHARED_INBOX_MANAGE";
        }
        if (path.equals("/v1/admin/mail/policy")
                && !("GET".equals(method) || "HEAD".equals(method))) {
            return "POLICY_MANAGE";
        }
        return switch (method) {
            case "GET", "HEAD" -> "VIEW";
            default -> "MANAGE";
        };
    }

    protected boolean hasDwaionAgentAdminAuthority(HttpServletRequest request) {
        String path = request.getRequestURI();
        String requiredPermission = switch (request.getMethod()) {
            case "GET", "HEAD" -> "VIEW";
            case "PATCH" -> "UPDATE";
            case "POST" -> path.endsWith("/activate")
                    ? "APPROVE"
                    : path.endsWith("/retire") ? "MANAGE" : "CREATE";
            default -> "MANAGE";
        };
        return hasAuthority(
                request.getHeader(PERMISSIONS_HEADER),
                "ADMIN.DWAION_AGENTS",
                requiredPermission);
    }

    protected boolean hasHomeExperienceAuthority(HttpServletRequest request) {
        String method = request.getMethod();
        String required = "GET".equals(method) || "HEAD".equals(method) ? "VIEW" : "MANAGE";
        return hasAuthority(request.getHeader(PERMISSIONS_HEADER), "ADMIN.HOME_EXPERIENCE", required);
    }

    protected boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    protected boolean pathFamily(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    protected boolean hasRole(String rolesHeader, Set<String> allowedRoles) {
        if (rolesHeader == null || rolesHeader.isBlank()) return false;
        return Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .anyMatch(allowedRoles::contains);
    }

    protected boolean authorizedSupportRequest(HttpServletRequest request) {
        if (positiveLong(request.getHeader(ACTOR_TENANT_HEADER)) == null) return false;
        Set<String> scopes = parseValues(request.getHeader(SUPPORT_SCOPES_HEADER));
        String path = request.getRequestURI();
        if (path.equals(SUPPORT_EXPERIENCE_PREVIEW_PATH)) {
            return "GET".equals(request.getMethod())
                    && scopes.contains("TENANT_EXPERIENCE_PREVIEW");
        }
        return false;
    }

    protected Set<String> parseValues(String header) {
        if (isBlank(header)) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    protected boolean isRuntimeRead(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return ("GET".equals(method) || "HEAD".equals(method))
                && (path.startsWith("/v1/catalog/")
                || path.startsWith("/v1/reference-data/")
                || path.equals("/v1/workspace/work-items")
                || path.equals("/v1/workspace/productivity/items")
                || path.equals("/v1/mail/threads")
                || path.equals("/v1/calendar/events"));
    }

    protected Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    protected boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

}
