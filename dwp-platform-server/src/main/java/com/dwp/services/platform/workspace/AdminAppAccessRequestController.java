package com.dwp.services.platform.workspace;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.platform.security.ResourceRoleAuthorization;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/app-access-requests")
public class AdminAppAccessRequestController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String LOCALE = "Accept-Language";
    private static final String CORRELATION = "X-Correlation-ID";
    private static final String ROLES = "X-DWP-Roles";
    private static final String RESOURCE_ROLES = "X-DWP-Resource-Roles";

    private final WorkspaceService service;

    public AdminAppAccessRequestController(WorkspaceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<WorkspaceDtos.AppAccessRequest>> requests(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(value = ROLES, required = false) String roles,
            @RequestHeader(value = RESOURCE_ROLES, required = false) String resourceRoles,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestParam(defaultValue = "ALL") String state) {
        boolean tenantWide = canViewTenantWide(roles);
        Set<String> resources = ResourceRoleAuthorization.resourcesFor(
                resourceRoles, "APP_OWNER", "APP_ACCESS_MANAGER",
                "APP_ACCESS_APPROVER", "APP_ACCESS_REVIEWER");
        return ApiResponse.success(service.appAccessRequests(
                tenantId, locale, state, tenantWide, resources));
    }

    @PostMapping("/{requestId}/decision")
    public ApiResponse<WorkspaceDtos.AppAccessRequest> decide(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = RESOURCE_ROLES, required = false) String resourceRoles,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody WorkspaceDtos.AppAccessDecisionRequest request) {
        return ApiResponse.success(service.decideAppAccessRequest(
                tenantId, actorId, locale, correlationId, requestId, request,
                ResourceRoleAuthorization.resourcesFor(
                        resourceRoles, "APP_ACCESS_APPROVER")));
    }

    @PostMapping("/{requestId}/fulfillment")
    public ApiResponse<WorkspaceDtos.AppAccessRequest> fulfill(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = RESOURCE_ROLES, required = false) String resourceRoles,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody WorkspaceDtos.AppAccessFulfillmentRequest request) {
        return ApiResponse.success(service.fulfillAppAccessRequest(
                tenantId, actorId, locale, correlationId, requestId, request,
                ResourceRoleAuthorization.resourcesFor(
                        resourceRoles, "APP_ACCESS_MANAGER")));
    }

    @PostMapping("/{requestId}/revocation")
    public ApiResponse<WorkspaceDtos.AppAccessRequest> revoke(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = RESOURCE_ROLES, required = false) String resourceRoles,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody WorkspaceDtos.AppAccessFulfillmentRequest request) {
        return ApiResponse.success(service.revokeAppAccessRequest(
                tenantId, actorId, locale, correlationId, requestId, request,
                ResourceRoleAuthorization.resourcesFor(
                        resourceRoles, "APP_ACCESS_MANAGER")));
    }

    private boolean canViewTenantWide(String roles) {
        return hasRole(roles, "APP_CATALOG_ADMIN");
    }

    private boolean hasRole(String roles, String expected) {
        if (roles == null) return false;
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .anyMatch(expected::equals);
    }
}
