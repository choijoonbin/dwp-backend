package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.IdentityAdminDtos;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import com.dwp.services.auth.service.IdentityAdminService;
import com.dwp.services.auth.service.IdentityAuditService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/auth/admin/identity")
public class IdentityAdminController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final IdentityAdminService identityAdminService;
    private final IdentityAuditService identityAuditService;

    public IdentityAdminController(
            IdentityAdminService identityAdminService,
            IdentityAuditService identityAuditService) {
        this.identityAdminService = identityAdminService;
        this.identityAuditService = identityAuditService;
    }

    @GetMapping("/users")
    public ApiResponse<IdentityAdminDtos.PageResult<IdentityAdminDtos.UserAccessSummary>> users(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AuthenticatedUserResolver.requireTenantAdmin(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(identityAdminService.listUsers(tenantId, query, page, size));
    }

    @GetMapping("/roles")
    public ApiResponse<List<IdentityAdminDtos.RoleSummary>> roles(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        AuthenticatedUserResolver.requireTenantAdmin(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(identityAdminService.listRoles(tenantId));
    }

    @PutMapping("/users/{userId}/roles")
    public ApiResponse<IdentityAdminDtos.UserAccessSummary> replaceRoles(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long userId,
            @Valid @RequestBody IdentityAdminDtos.ReplaceUserRolesRequest request) {
        AuthenticatedUserResolver.requireTenantAdmin(authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(identityAdminService.replaceRoles(
                tenantId, actorId, correlationId, userId, request));
    }

    @GetMapping("/audit-events")
    public ApiResponse<IdentityAdminDtos.PageResult<IdentityAdminDtos.IdentityAuditEventResponse>> audit(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AuthenticatedUserResolver.requireTenantAdmin(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(identityAuditService.list(tenantId, page, size));
    }
}

