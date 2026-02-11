package com.dwp.services.synapsex.client;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.synapsex.dto.workbench.WorkbenchSettingMenuDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * auth-server 메뉴 엔트리 조회 (워크벤치 deepLink 연동용).
 * GET /auth/menus/entries?keys=... → 권한 필터된 menuKey, label, deepLink.
 */
@FeignClient(
        name = "auth-server-menus",
        url = "${auth.server.url:http://localhost:8001}"
)
public interface AuthServerMenuClient {

    /**
     * 메뉴 엔트리 목록 조회 (X-Tenant-ID, Authorization 등 Feign 인터셉터로 전파).
     *
     * @param tenantId X-Tenant-ID
     * @param keys     쉼표 구분 menu key 목록 (예: menu.knowledge-policy.rag,menu.knowledge-policy.policies)
     */
    @GetMapping("/auth/menus/entries")
    ApiResponse<List<WorkbenchSettingMenuDto>> getMenuEntries(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam("keys") String keys);
}
