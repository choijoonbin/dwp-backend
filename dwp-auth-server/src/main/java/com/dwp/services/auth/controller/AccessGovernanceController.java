package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.AccessGovernanceDtos;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import com.dwp.services.auth.service.AccessGovernanceService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/auth/admin/access/governance")
public class AccessGovernanceController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final AccessGovernanceService service;

    public AccessGovernanceController(AccessGovernanceService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    public ApiResponse<List<AccessGovernanceDtos.RoleSummary>> roles(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.roles(tenantId));
    }

    @PostMapping("/roles")
    public ApiResponse<AccessGovernanceDtos.RoleSummary> createRole(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AccessGovernanceDtos.CreateRoleRequest request) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.createRole(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, request));
    }

    @PutMapping("/roles/{roleId}")
    public ApiResponse<AccessGovernanceDtos.RoleSummary> updateRole(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long roleId,
            @Valid @RequestBody AccessGovernanceDtos.UpdateRoleRequest request) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.updateRole(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, roleId, request));
    }

    @PutMapping("/roles/{roleId}/permissions")
    public ApiResponse<AccessGovernanceDtos.RoleSummary> replacePermissions(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long roleId,
            @Valid @RequestBody AccessGovernanceDtos.ReplacePermissionsRequest request) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.replacePermissions(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, roleId, request));
    }

    @GetMapping("/resources")
    public ApiResponse<List<AccessGovernanceDtos.ResourceSummary>> resources(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.resources(tenantId));
    }

    @PostMapping("/resources")
    public ApiResponse<AccessGovernanceDtos.ResourceSummary> createResource(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AccessGovernanceDtos.CreateResourceRequest request) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.createResource(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, request));
    }

    @GetMapping("/group-role-assignments")
    public ApiResponse<List<AccessGovernanceDtos.GroupRoleAssignmentSummary>> groupAssignments(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.groupAssignments(tenantId));
    }

    @PostMapping("/group-role-assignments")
    public ApiResponse<AccessGovernanceDtos.GroupRoleAssignmentSummary> createGroupAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AccessGovernanceDtos.CreateGroupRoleAssignmentRequest request) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.createGroupAssignment(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, request));
    }

    @PatchMapping("/group-role-assignments/{assignmentId}/revoke")
    public ApiResponse<AccessGovernanceDtos.GroupRoleAssignmentSummary> revokeGroupAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long assignmentId,
            @RequestParam Long version) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.revokeGroupAssignment(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, assignmentId, version));
    }

    @GetMapping("/users/{userId}/effective-access")
    public ApiResponse<AccessGovernanceDtos.EffectiveAccess> effectiveAccess(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @PathVariable Long userId) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.effectiveAccess(tenantId, userId));
    }

    private Long tenantAdmin(Authentication authentication, String tenantHeader) {
        AuthenticatedUserResolver.requireTenantAdmin(authentication);
        return TenantContextResolver.requireTenantId(tenantHeader, authentication);
    }
}
