package com.dwp.services.synapsex.client;

import com.dwp.core.common.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * auth-server 권한 검증 Feign 클라이언트.
 * /api/synapse/admin/** RBAC 강제 시 사용.
 */
@FeignClient(
        name = "auth-server-permission",
        url = "${auth.server.url:http://localhost:8001}"
)
public interface AuthServerPermissionClient {

    /**
     * 권한 검증
     *
     * @param tenantId       X-Tenant-ID (FeignHeaderInterceptor로 전파)
     * @param userId         X-User-ID (FeignHeaderInterceptor로 전파)
     * @param resourceKey    예: menu.admin.monitoring
     * @param permissionCode VIEW, EXECUTE 등
     * @return canAccess 여부
     */
    @GetMapping("/internal/permission/check")
    ApiResponse<Boolean> check(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestHeader("X-User-ID") Long userId,
            @RequestParam("resourceKey") String resourceKey,
            @RequestParam("permissionCode") String permissionCode);
}
