package com.dwp.services.synapsex.client;

import com.dwp.core.common.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * auth-server 사용자 조회 Feign 클라이언트
 *
 * Team Snapshot display_name 연동용.
 * com_users에서 display_name을 배치 조회합니다.
 */
@FeignClient(
        name = "auth-server-users",
        url = "${auth.server.url:http://localhost:8001}"
)
public interface AuthServerUserClient {

    /**
     * 사용자 display_name 배치 조회
     *
     * @param tenantId X-Tenant-ID 헤더 (FeignHeaderInterceptor로 전파)
     * @param ids      쉼표 구분 user_id 목록
     * @return userId(문자열) → displayName 맵
     */
    @GetMapping("/internal/users/display-names")
    ApiResponse<Map<String, String>> getDisplayNames(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam("ids") String ids);
}
