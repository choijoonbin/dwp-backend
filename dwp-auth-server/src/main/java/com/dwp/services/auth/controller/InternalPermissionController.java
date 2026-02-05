package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.auth.service.rbac.AdminGuardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 내부 통신용 권한 검증 API.
 * Synapsex 등에서 /api/synapse/admin/** RBAC 검증 시 Feign으로 호출.
 * /internal/** 는 permitAll (서비스 간 내부 통신).
 */
@RestController
@RequestMapping("/internal/permission")
@RequiredArgsConstructor
public class InternalPermissionController {

    private final AdminGuardService adminGuardService;

    /**
     * 권한 검증
     * GET /internal/permission/check?userId=1&tenantId=1&resourceKey=menu.admin.monitoring&permissionCode=VIEW
     *
     * @param tenantId     X-Tenant-ID 헤더 또는 query param
     * @param userId       X-User-ID 헤더 또는 query param
     * @param resourceKey  예: menu.admin.monitoring
     * @param permissionCode VIEW, EXECUTE 등
     * @return { "success": true, "data": true } 또는 { "data": false }
     */
    @GetMapping("/check")
    public ApiResponse<Boolean> check(
            @RequestHeader(value = HeaderConstants.X_TENANT_ID, required = false) Long headerTenantId,
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long headerUserId,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long userId,
            @RequestParam String resourceKey,
            @RequestParam String permissionCode) {

        Long effectiveTenantId = headerTenantId != null ? headerTenantId : tenantId;
        Long effectiveUserId = headerUserId != null ? headerUserId : userId;

        if (effectiveTenantId == null || effectiveUserId == null || resourceKey == null || resourceKey.isBlank()) {
            return ApiResponse.success(false);
        }

        boolean canAccess = adminGuardService.canAccess(effectiveUserId, effectiveTenantId, resourceKey, permissionCode);
        return ApiResponse.success(canAccess);
    }
}
