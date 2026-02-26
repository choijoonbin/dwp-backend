package com.dwp.services.mcp.client;

import com.dwp.core.common.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "mcp-auth-server-permission",
        url = "${auth.server.url:http://localhost:8001}"
)
public interface AuthServerPermissionClient {

    @GetMapping("/internal/permission/is-admin")
    ApiResponse<Boolean> isAdmin(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestHeader("X-User-ID") Long userId);

    @GetMapping("/internal/permission/check")
    ApiResponse<Boolean> check(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestHeader("X-User-ID") Long userId,
            @RequestParam("resourceKey") String resourceKey,
            @RequestParam("permissionCode") String permissionCode);
}

