package com.dwp.services.auth.controller.admin;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.auth.dto.admin.TenantSummaryDto;
import com.dwp.services.auth.service.admin.tenants.TenantQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin - Tenant Selector API.
 * GET /api/admin/tenants: 로그인한 사용자가 접근 가능한 Tenant 목록.
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class TenantController {

    private final TenantQueryService tenantQueryService;

    /**
     * GET /api/admin/tenants
     * 로그인한 사용자가 속한 Tenant 목록 반환 (UserAccount 기준).
     * X-Tenant-ID 없이 호출 가능 (Tenant 선택 전 초기 로드용).
     */
    @GetMapping("/tenants")
    public ApiResponse<List<TenantSummaryDto>> list(
            @RequestHeader(value = HeaderConstants.X_USER_ID, required = false) Long userId,
            Authentication authentication) {
        Long actorUserId = userId != null ? userId : getUserId(authentication);
        if (actorUserId == null) {
            return ApiResponse.error(com.dwp.core.common.ErrorCode.UNAUTHORIZED, "사용자 정보를 확인할 수 없습니다.");
        }
        List<TenantSummaryDto> list = tenantQueryService.getTenantsForUser(actorUserId);
        return ApiResponse.success(list);
    }

    private Long getUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            try {
                return Long.parseLong(jwt.getSubject());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
