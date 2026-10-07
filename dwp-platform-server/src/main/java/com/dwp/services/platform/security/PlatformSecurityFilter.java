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
public class PlatformSecurityFilter extends PlatformSecurityPolicySupport {

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
        if (path.startsWith(RUNTIME_CODE_SET_PATH_PREFIX)
                && !canonicalRuntimeCodeSetPath(path)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "A canonical runtime code-set path is required.");
            return;
        }
        if (PERSONAL_PREFERENCE_CODE_SET_PATHS.contains(path)
                && !hasPersonalRuntimeCodeSetAccess(request, supportAccess)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Personal preference code sets require a tenant data-plane identity.");
            return;
        }
        if (AUDIT_VIEW_CODE_SET_PATHS.contains(path)
                && !hasAuditRuntimeCodeSetAccess(request, supportAccess)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Exact audit view permission is required for this code set.");
            return;
        }
        if (API_MONITORING_CODE_SET_PATHS.contains(path)
                && !hasApiMonitoringRuntimeCodeSetAccess(request, supportAccess)) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Exact API monitoring permission is required for this code set.");
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
        boolean apiHistoryAdminPath = pathFamily(path, "/v1/admin/api-history");
        boolean delegatedApiHistoryAccess = apiHistoryAdminPath
                && hasApiHistoryAuthority(request, supportAccess);
        if (apiHistoryAdminPath && !delegatedApiHistoryAccess) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Exact API monitoring permission is required.");
            return;
        }
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
        String tenantAdministrationResource = tenantAdministrationResource(path);
        boolean tenantAdministrationPath = tenantAdministrationResource != null;
        boolean delegatedTenantAdministrationAccess = tenantAdministrationPath
                && hasTenantAdministrationAuthority(
                request, tenantAdministrationResource, supportAccess);
        if (tenantAdministrationPath && !delegatedTenantAdministrationAccess) {
            writeError(response, ErrorCode.FORBIDDEN,
                    "Exact tenant settings administration permission is required.");
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
                && !auditAdminPath && !delegatedApiHistoryAccess && !savedViewCustodyPath
                && !scopedAppAccess && !delegatedCommunicationsAccess && !delegatedServicesAccess
                && !delegatedCalendarAccess && !delegatedRoomsAccess
                && !delegatedWorkplaceAccess && !delegatedMailAccess
                && !delegatedDwaionAgentAccess && !delegatedHomeExperienceAccess
                && !delegatedTenantAdministrationAccess
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

    private void writeError(HttpServletResponse response, ErrorCode errorCode, String message)
            throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(errorCode, message));
    }

}
