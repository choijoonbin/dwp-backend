package com.dwp.services.platform.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.filter.ApiHistoryServletFilter;
import com.dwp.core.security.RolePlaneBoundary;
import com.dwp.services.platform.servicecenter.ServicesProductSurfacePepFilter;
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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PlatformSecurityFilter extends OncePerRequestFilter {

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
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN");
    private static final String SUPPORT_EXPERIENCE_PREVIEW_PATH =
            "/v1/admin/tenant-experience-preview";

    private final String serviceToken;
    private final String runtimeServiceToken;
    private final ObjectMapper objectMapper;
    private final PlatformCanaryPepRegistry canaryPepRegistry;
    private final boolean approvalsProductAuthorizationV2Enabled;
    private final PlatformApprovalsPepRegistry approvalsPepRegistry;
    private final PlatformProductAuthorizationSupport productAuthorization;

    @Autowired
    public PlatformSecurityFilter(
            @Value("${dwp.platform.service-token:}") String serviceToken,
            @Value("${dwp.platform.runtime-service-token:}") String runtimeServiceToken,
            @Value("${dwp.platform.product-authorization-approvals-v2-enabled:false}")
            boolean approvalsProductAuthorizationV2Enabled,
            ObjectMapper objectMapper,
            PlatformCanaryPepRegistry canaryPepRegistry,
            PlatformApprovalsPepRegistry approvalsPepRegistry) {
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.runtimeServiceToken = runtimeServiceToken == null ? "" : runtimeServiceToken.trim();
        this.objectMapper = objectMapper;
        this.canaryPepRegistry = canaryPepRegistry;
        this.approvalsProductAuthorizationV2Enabled = approvalsProductAuthorizationV2Enabled;
        this.approvalsPepRegistry = approvalsPepRegistry;
        this.productAuthorization = new PlatformProductAuthorizationSupport(
                canaryPepRegistry, approvalsPepRegistry);
    }

    PlatformSecurityFilter(
            String serviceToken,
            String runtimeServiceToken,
            ObjectMapper objectMapper) {
        this(serviceToken, runtimeServiceToken, objectMapper,
                new PlatformCanaryPepRegistry(objectMapper));
    }

    PlatformSecurityFilter(
            String serviceToken,
            String runtimeServiceToken,
            ObjectMapper objectMapper,
            PlatformCanaryPepRegistry canaryPepRegistry) {
        this(serviceToken, runtimeServiceToken, false, objectMapper, canaryPepRegistry,
                new PlatformApprovalsPepRegistry(objectMapper));
    }

    PlatformSecurityFilter(
            String serviceToken,
            String runtimeServiceToken,
            boolean approvalsProductAuthorizationV2Enabled,
            ObjectMapper objectMapper) {
        this(serviceToken, runtimeServiceToken, approvalsProductAuthorizationV2Enabled,
                objectMapper, new PlatformCanaryPepRegistry(objectMapper),
                new PlatformApprovalsPepRegistry(objectMapper));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.startsWith("/v3/api-docs")
                || path.startsWith(ApiHistoryServletFilter.COLLECTOR_PATH)
                || path.startsWith("/internal/audit/events")
                || path.startsWith("/internal/provider/")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean devicePath = PlatformDeviceIdentity.owns(request.getMethod(), path);
        String identityPlane = PlatformDeviceIdentity.exactHeader(
                request, PlatformDeviceIdentity.PLANE_HEADER);
        if (PlatformDeviceIdentity.PLANE.equals(identityPlane) && !devicePath) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The DEVICE identity plane is restricted to governed device routes.");
            return;
        }
        if (request.getRequestURI().startsWith("/v1/workspace/activity")) {
            response.setHeader("Cache-Control", "private, no-store, max-age=0");
        }
        boolean runtimeRead = isRuntimeRead(request);
        if (serviceToken.isBlank() && (!runtimeRead || runtimeServiceToken.isBlank())) {
            writeError(response, ErrorCode.EXTERNAL_SERVICE_ERROR, "Platform service identity is not configured.");
            return;
        }
        String providedToken = devicePath
                ? PlatformDeviceIdentity.exactHeader(request, SERVICE_TOKEN_HEADER)
                : request.getHeader(SERVICE_TOKEN_HEADER);
        boolean gatewayIdentity = !serviceToken.isBlank()
                && constantTimeEquals(serviceToken, providedToken);
        boolean runtimeIdentity = runtimeRead
                && !runtimeServiceToken.isBlank()
                && constantTimeEquals(runtimeServiceToken, providedToken);
        if (!gatewayIdentity && !runtimeIdentity) {
            writeError(response, ErrorCode.UNAUTHORIZED, "Trusted platform service identity is required.");
            return;
        }

        if (devicePath) {
            Long deviceTenant = PlatformDeviceIdentity.canonicalTenant(request, TENANT_HEADER);
            String credential = PlatformDeviceIdentity.exactHeader(
                    request, PlatformDeviceIdentity.CREDENTIAL_HEADER);
            if (!gatewayIdentity || deviceTenant == null
                    || !PlatformDeviceIdentity.PLANE.equals(identityPlane)
                    || !PlatformDeviceIdentity.validCredential(credential)) {
                writeError(response, ErrorCode.UNAUTHORIZED,
                        "Trusted DEVICE identity evidence is required.");
                return;
            }
            if (PlatformDeviceIdentity.hasConflictingEvidence(request)) {
                writeError(response, ErrorCode.FORBIDDEN,
                        "User, role, permission, product, and support evidence cannot coexist with DEVICE identity.");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        Long actorId = positiveLong(request.getHeader(USER_HEADER));
        Long tenantId = positiveLong(request.getHeader(TENANT_HEADER));
        if (actorId == null || tenantId == null) {
            writeError(response, ErrorCode.UNAUTHORIZED, "Verified user and tenant identity are required.");
            return;
        }
        if (RolePlaneBoundary.hasConflict(
                parseValues(request.getHeader(ROLES_HEADER)))) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Provider control-plane roles cannot coexist with tenant or workspace roles.");
            return;
        }
        boolean providerWidgetRegistryPath = providerWidgetRegistryPath(path);
        boolean providerWidgetRegistryAccess = providerWidgetRegistryPath
                && "WIDGET_REGISTRY_PROVIDER".equals(request.getHeader(CONTROL_PLANE_HEADER))
                && "PROVIDER".equals(request.getHeader("X-DWP-Identity-Plane"))
                && RolePlaneBoundary.isProviderIdentity(parseValues(request.getHeader(ROLES_HEADER)))
                && validProviderOwnerScope(request);
        if (providerWidgetRegistryPath && !providerWidgetRegistryAccess) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Provider Widget Registry identity and trusted route are required.");
            return;
        }
        List<String> resolvedCanaryRoutes = List.of();
        List<String> resolvedApprovalRoutes = List.of();
        boolean approvalStateChanging = false;
        String approvalExpectedRevision = null;
        // Additive Services response authority is verified by the source-owned PEP before
        // this filter. Preserve immutable v1 route matching for every existing route.
        boolean canaryProductPath = canaryPepRegistry.ownsPath(path)
                && !ServicesProductSurfacePepFilter.hasVerifiedRequesterResponseAuthority(request);
        boolean approvalProductPath = approvalsPepRegistry.governsPathFamily(path);
        boolean productPath = canaryProductPath || approvalProductPath;
        boolean exactEnforcement = false;
        if (productPath && gatewayIdentity) {
            String rolloutState = productAuthorization.exactHeader(
                    request, ROLLOUT_STATE_HEADER);
            if (!productAuthorization.validRolloutEvidence(
                    rolloutState,
                    productAuthorization.exactHeader(request, ROLLOUT_REVISION_HEADER),
                    productAuthorization.exactHeader(request, ROLLOUT_COHORT_HEADER))) {
                writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Trusted product rollout evidence is missing or invalid.");
                return;
            }
            exactEnforcement = rolloutState.charAt(1) == '1';
        }
        boolean supportAccess = !isBlank(request.getHeader(SUPPORT_SESSION_HEADER));
        if (supportAccess && !authorizedSupportRequest(request)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The support session does not permit this platform resource.");
            return;
        }
        boolean personalHomePath = pathFamily(path, "/v1/home-views") || pathFamily(path, "/v1/home-experience")
                || pathFamily(path, "/v1/home-templates")
                || pathFamily(path, "/v1/home-composer/proposals")
                || pathFamily(path, "/v1/home-preferences")
                || pathFamily(path, "/v2/home");
        if (personalHomePath
                && (supportAccess
                || !"TENANT".equals(request.getHeader("X-DWP-Identity-Plane"))
                || !isBlank(request.getHeader("X-DWP-Provider-Tenant-ID"))
                || !isBlank(request.getHeader(ACTOR_TENANT_HEADER)))) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Personal Home settings require a tenant data-plane identity.");
            return;
        }
        boolean adminHomeExperiencePath = pathFamily(path, "/v1/admin/home-experience");
        boolean delegatedHomeExperienceAccess = adminHomeExperiencePath && !supportAccess
                && "TENANT".equals(request.getHeader("X-DWP-Identity-Plane"))
                && hasHomeExperienceAuthority(request);
        if (adminHomeExperiencePath && !delegatedHomeExperienceAccess) {
            writeError(response, ErrorCode.FORBIDDEN, "Home Experience administration permission is required.");
            return;
        }
        PlatformProductAuthorizationSupport.TrustedAuthorityEvidence trustedAuthority = null;
        if (exactEnforcement) {
            trustedAuthority = productAuthorization.trustedAuthority(request);
            if (trustedAuthority == null) {
                writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Trusted product route, context, scope, and revision evidence is invalid.");
                return;
            }
        }
        if (canaryProductPath && exactEnforcement) {
            PlatformCanaryPepRegistry.Decision decision = canaryPepRegistry.authorize(
                    new PlatformCanaryPepRegistry.RequestEvidence(
                            request.getMethod(),
                            path,
                            parseValues(request.getHeader(PERMISSIONS_HEADER)),
                            request.getHeader(RESOURCE_ROLES_HEADER),
                            request.getHeader(SUPPORT_SESSION_HEADER),
                            parseValues(request.getHeader(SUPPORT_SCOPES_HEADER)),
                            request.getHeader(ACTOR_TENANT_HEADER),
                            trustedAuthority.routeContractKey()));
            if (!decision.allowed()) {
                writeError(response, ErrorCode.FORBIDDEN,
                        "The exact Platform product route authority is required.");
                return;
            }
            request.setAttribute(
                    PlatformCanaryPepRegistry.class.getName() + ".routeContractKeys",
                    decision.routeContractKeys());
            resolvedCanaryRoutes = decision.routeContractKeys();
            if (productAuthorization.stateChangingCanary(resolvedCanaryRoutes)
                    && !trustedAuthority.currentRevision().equals(
                    productAuthorization.exactHeader(
                            request, EXPECTED_DECISION_REVISION_HEADER))) {
                writeError(response, ErrorCode.DECISION_REVISION_CONFLICT,
                        "Platform authority changed after the client decision.");
                return;
            }
        }
        if (approvalProductPath && exactEnforcement) {
            if (!approvalsProductAuthorizationV2Enabled) {
                writeError(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "Approval-home product authorization is not ready for enforcement.");
                return;
            }
            PlatformApprovalsPepRegistry.Decision decision = approvalsPepRegistry.authorize(
                    new PlatformApprovalsPepRegistry.RequestEvidence(
                            request.getMethod(), path,
                            parseValues(request.getHeader(PERMISSIONS_HEADER)),
                            trustedAuthority.routeContractKey()));
            if (!decision.allowed()) {
                writeError(response, ErrorCode.FORBIDDEN,
                        "The exact approval-home route authority is required.");
                return;
            }
            resolvedApprovalRoutes = decision.routeContractKeys();
            request.setAttribute(
                    PlatformApprovalsPepRegistry.class.getName() + ".routeContractKeys",
                    resolvedApprovalRoutes);
            boolean stateChanging = productAuthorization
                    .stateChangingApproval(resolvedApprovalRoutes);
            String expected = productAuthorization.exactHeader(
                    request, EXPECTED_DECISION_REVISION_HEADER);
            if (stateChanging && !trustedAuthority.currentRevision().equals(expected)) {
                writeError(response, ErrorCode.DECISION_REVISION_CONFLICT,
                        "Approval-home authority changed after the client decision.");
                return;
            }
            approvalStateChanging = stateChanging;
            approvalExpectedRevision = expected;
            response.setHeader(RESPONSE_DECISION_REVISION_HEADER,
                    trustedAuthority.currentRevision());
        }
        if (canaryProductPath && !exactEnforcement
                && !productAuthorization.legacyProductAuthorized(request, supportAccess)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "The legacy product permission required for this operation is missing.");
            return;
        }
        boolean auditAdminPath = path.startsWith("/v1/admin/audit-control");
        boolean savedViewCustodyPath = path.startsWith("/v1/admin/saved-view-ownership");
        if (auditAdminPath && isBlank(request.getHeader(PERMISSIONS_HEADER))) {
            writeError(response, ErrorCode.FORBIDDEN, "Audit permission is required.");
            return;
        }
        if (savedViewCustodyPath && !hasPermission(
                request.getHeader(PERMISSIONS_HEADER), "ADMIN.SAVED_VIEW_CUSTODY")) {
            writeError(response, ErrorCode.FORBIDDEN, "Saved view custody permission is required.");
            return;
        }
        boolean workspacePath = path.startsWith("/v1/workspace");
        if (workspacePath && isBlank(request.getHeader(PERMISSIONS_HEADER))) {
            writeError(response, ErrorCode.FORBIDDEN, "Workspace permission is required.");
            return;
        }
        boolean activityPath = path.equals("/v1/workspace/activity")
                || path.startsWith("/v1/workspace/activity/");
        if (activityPath && (!hasAuthority(request.getHeader(PERMISSIONS_HEADER), "APP.ACTIVITY", "VIEW")
                || !"TENANT".equals(request.getHeader("X-DWP-Identity-Plane")) || supportAccess
                || parseValues(request.getHeader(ROLES_HEADER)).stream().anyMatch(role -> role.startsWith("PROVIDER_")))) {
            writeError(response, ErrorCode.FORBIDDEN, "Personal tenant Activity permission is required.");
            return;
        }
        boolean calendarPath = path.startsWith("/v1/calendar");
        if (calendarPath && !hasCalendarAuthority(request)) {
            writeError(response, ErrorCode.FORBIDDEN, "Calendar permission is required.");
            return;
        }
        boolean roomsPath = path.startsWith("/v1/rooms");
        if (roomsPath && !hasRoomsAuthority(request)) {
            writeError(response, ErrorCode.FORBIDDEN, "Rooms permission is required.");
            return;
        }
        boolean workplacePath = path.startsWith("/v1/workplace");
        if (workplacePath && !hasWorkplaceAuthority(request)) {
            writeError(response, ErrorCode.FORBIDDEN, "Workplace permission is required.");
            return;
        }
        boolean mailPath = path.startsWith("/v1/mail");
        if (mailPath && !hasMailAuthority(request)) {
            writeError(response, ErrorCode.FORBIDDEN, "Mail permission is required.");
            return;
        }
        boolean communicationsAdminPath = path.startsWith("/v1/admin/announcements");
        boolean delegatedCommunicationsAccess = communicationsAdminPath;
        boolean servicesAdminPath = path.startsWith("/v1/admin/services");
        boolean delegatedServicesAccess = servicesAdminPath;
        boolean calendarAdminPath = path.startsWith("/v1/admin/calendar");
        boolean delegatedCalendarAccess = calendarAdminPath && hasCalendarAdminAuthority(request);
        if (calendarAdminPath && !delegatedCalendarAccess) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Calendar administration permission is required.");
            return;
        }
        boolean roomsAdminPath = path.startsWith("/v1/admin/rooms");
        boolean delegatedRoomsAccess = roomsAdminPath && hasRoomsAdminAuthority(request);
        if (roomsAdminPath && !delegatedRoomsAccess) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Rooms administration permission is required.");
            return;
        }
        boolean workplaceAdminPath = path.startsWith("/v1/admin/workplace");
        boolean delegatedWorkplaceAccess = workplaceAdminPath
                && hasWorkplaceAdminAuthority(request);
        if (workplaceAdminPath && !delegatedWorkplaceAccess) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Workplace administration permission is required.");
            return;
        }
        boolean mailAdminPath = path.startsWith("/v1/admin/mail");
        boolean delegatedMailAccess = mailAdminPath && hasMailAdminAuthority(request);
        if (mailAdminPath && !delegatedMailAccess) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Mail administration permission is required.");
            return;
        }
        boolean dwaionAgentAdminPath = path.startsWith("/v1/admin/dwaion/agents");
        boolean delegatedDwaionAgentAccess = dwaionAgentAdminPath
                && hasDwaionAgentAdminAuthority(request);
        if (dwaionAgentAdminPath && !delegatedDwaionAgentAccess) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "DWAI-ON agent publishing permission is required.");
            return;
        }
        boolean appAccessRequestPath = path.startsWith("/v1/admin/app-access-requests");
        boolean scopedAppAccess = appAccessRequestPath && hasScopedAppAccess(request);
        if (appAccessRequestPath && !scopedAppAccess) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "An application-scoped access responsibility is required.");
            return;
        }
        if (!supportAccess && !providerWidgetRegistryAccess
                && !auditAdminPath && !savedViewCustodyPath
                && !scopedAppAccess && !delegatedCommunicationsAccess && !delegatedServicesAccess
                && !delegatedCalendarAccess && !delegatedRoomsAccess
                && !delegatedWorkplaceAccess && !delegatedMailAccess
                && !delegatedDwaionAgentAccess && !delegatedHomeExperienceAccess
                && path.startsWith("/v1/admin/")
                && !hasRole(request.getHeader(ROLES_HEADER), ADMIN_ROLES)) {
            writeError(response, ErrorCode.FORBIDDEN, "Tenant administrator permission is required.");
            return;
        }

        if (!resolvedCanaryRoutes.isEmpty()) {
            PlatformCanaryAuthorizationContext.set(resolvedCanaryRoutes);
        }
        if (!resolvedApprovalRoutes.isEmpty()) {
            PlatformApprovalsAuthorizationContext.setEnforced(
                    tenantId, actorId, resolvedApprovalRoutes,
                    trustedAuthority.currentRevision(), trustedAuthority.revalidateAt(),
                    trustedAuthority.contextKey(), trustedAuthority.scopeKey(),
                    approvalExpectedRevision, approvalStateChanging);
        } else if (approvalProductPath && !exactEnforcement) {
            PlatformApprovalsAuthorizationContext.setLegacy(tenantId, actorId);
        }
        RequestActorContext.set(actorId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestActorContext.clear();
            PlatformApprovalsAuthorizationContext.clear();
            PlatformCanaryAuthorizationContext.clear();
        }
    }

    private static boolean providerWidgetRegistryPath(String path) {
        return path.equals("/v1/admin/widget-definitions")
                || path.startsWith("/v1/admin/widget-definitions/")
                || path.startsWith("/v1/admin/widget-definition-versions/")
                || path.equals("/v1/admin/widget-runtime-controls")
                || path.startsWith("/v1/admin/widget-runtime-controls/")
                || path.startsWith("/v1/admin/widget-registry/");
    }

    private boolean validProviderOwnerScope(HttpServletRequest request) {
        var values = request.getHeaders(WIDGET_OWNER_SCOPE_HEADER);
        List<String> headers = values == null ? List.of() : Collections.list(values);
        if (headers.size() != 1 || headers.getFirst().length() > 3_872) return false;
        Set<String> owners = parseValues(headers.getFirst());
        return !owners.isEmpty()
                && owners.size() <= 32
                && owners.stream().allMatch(owner -> owner.length() <= 120
                        && owner.matches("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$"));
    }

    private boolean hasScopedAppAccess(HttpServletRequest request) {
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

    private boolean hasPermission(String permissionsHeader, String resourceKey) {
        if (isBlank(permissionsHeader)) return false;
        String prefix = resourceKey.toUpperCase() + ":";
        return Arrays.stream(permissionsHeader.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .anyMatch(value -> value.startsWith(prefix));
    }

    private boolean hasAuthority(
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

    private boolean hasAnyAuthority(
            String permissionsHeader,
            String resourceKey,
            String... permissionCodes) {
        return Arrays.stream(permissionCodes)
                .anyMatch(code -> hasAuthority(permissionsHeader, resourceKey, code));
    }

    private boolean hasCalendarAuthority(HttpServletRequest request) {
        String requiredPermission = switch (request.getMethod()) {
            case "GET", "HEAD" -> "VIEW";
            case "POST" -> request.getRequestURI().endsWith("/events") ? "CREATE" : "UPDATE";
            case "PUT", "PATCH", "DELETE" -> "UPDATE";
            default -> "VIEW";
        };
        return hasAuthority(
                request.getHeader(PERMISSIONS_HEADER), "APP.CALENDAR", requiredPermission);
    }

    private boolean hasRoomsAuthority(HttpServletRequest request) {
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

    private boolean hasRoomsAdminAuthority(HttpServletRequest request) {
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

    private boolean hasWorkplaceAuthority(HttpServletRequest request) {
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

    private boolean hasWorkplaceAdminAuthority(HttpServletRequest request) {
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

    private boolean isWorkplaceBoardReportExport(String path) {
        String base = "/v1/admin/workplace/space-planning/reports";
        return path.equals(base)
                || path.equals(base + ":preview")
                || path.matches("^" + base
                + "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}"
                + "-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}(/content)?$");
    }

    private boolean isSensitiveWorkplaceOperation(String path) {
        return (path.startsWith("/v1/admin/workplace/experience/collaboration/connectors/"))
                || path.endsWith("/policy")
                || path.endsWith("/force-cancel")
                || path.endsWith("/legal-hold");
    }

    private boolean isWorkplaceGovernanceTransition(String path) {
        return (path.startsWith("/v1/admin/workplace/experience/collaboration/")
                && (path.endsWith("/changes") || path.endsWith("/review")))
                || (path.startsWith("/v1/admin/workplace/connectors/")
                && (path.endsWith("/replays:preview") || path.endsWith("/replays")))
                || path.endsWith("/review")
                || path.endsWith("/publish")
                || path.endsWith("/restore");
    }

    private boolean isWorkplaceConnectorReplayStatus(String path) {
        return path.matches(
                "^/v1/admin/workplace/connectors/[^/]+/replays/[^/]+$");
    }

    private boolean hasCalendarAdminAuthority(HttpServletRequest request) {
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

    private boolean hasMailAuthority(HttpServletRequest request) {
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

    private boolean isMailCreatePath(String path) {
        return path.equals("/v1/mail/drafts")
                || path.equals("/v1/mail/saved-views")
                || path.equals("/v1/mail/templates")
                || path.equals("/v1/mail/signatures")
                || path.equals("/v1/mail/organization/folders")
                || path.equals("/v1/mail/organization/rules");
    }

    private boolean hasMailAdminAuthority(HttpServletRequest request) {
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

    private String mailAdminPermission(String method, String path) {
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

    private boolean hasDwaionAgentAdminAuthority(HttpServletRequest request) {
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

    private boolean hasHomeExperienceAuthority(HttpServletRequest request) {
        String method = request.getMethod();
        String required = "GET".equals(method) || "HEAD".equals(method) ? "VIEW" : "MANAGE";
        return hasAuthority(request.getHeader(PERMISSIONS_HEADER), "ADMIN.HOME_EXPERIENCE", required);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean pathFamily(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private boolean hasRole(String rolesHeader, Set<String> allowedRoles) {
        if (rolesHeader == null || rolesHeader.isBlank()) return false;
        return Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .anyMatch(allowedRoles::contains);
    }

    private boolean authorizedSupportRequest(HttpServletRequest request) {
        if (positiveLong(request.getHeader(ACTOR_TENANT_HEADER)) == null) return false;
        Set<String> scopes = parseValues(request.getHeader(SUPPORT_SCOPES_HEADER));
        String path = request.getRequestURI();
        if (path.equals(SUPPORT_EXPERIENCE_PREVIEW_PATH)) {
            return "GET".equals(request.getMethod())
                    && scopes.contains("TENANT_EXPERIENCE_PREVIEW");
        }
        return false;
    }

    private Set<String> parseValues(String header) {
        if (isBlank(header)) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean isRuntimeRead(HttpServletRequest request) {
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

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
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
