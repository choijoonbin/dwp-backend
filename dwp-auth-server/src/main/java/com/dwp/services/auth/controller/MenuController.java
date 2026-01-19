package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.MenuTreeResponse;
import com.dwp.services.auth.service.MenuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 메뉴 트리 API 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/auth/menus")
@RequiredArgsConstructor
public class MenuController {
    
    private final MenuService menuService;
    
    /**
     * 메뉴 트리 조회
     * GET /api/auth/menus/tree
     * 
     * 권한 기반으로 필터링된 메뉴 트리를 반환합니다.
     * 프론트엔드 사이드바 렌더링에 사용됩니다.
     * 
     * @param authentication JWT 인증 정보
     * @param tenantIdHeader 테넌트 ID (헤더, 선택적)
     * @return 메뉴 트리 응답
     */
    @GetMapping("/tree")
    public ApiResponse<MenuTreeResponse> getMenuTree(
            Authentication authentication,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantIdHeader) {
        
        Jwt jwt = (Jwt) authentication.getPrincipal();
        Long userId = Long.parseLong(jwt.getSubject());
        Long tenantId = parseTenantId(tenantIdHeader, authentication);
        
        MenuTreeResponse response = menuService.getMenuTree(userId, tenantId);
        return ApiResponse.success(response);
    }
    
    /**
     * 테넌트 ID 파싱 헬퍼 메서드
     * 헤더 → JWT 클레임 → Fallback 순서로 조회
     */
    private Long parseTenantId(String header, Authentication auth) {
        // 1. 헤더에서 직접 파싱 시도
        if (header != null && !header.isEmpty()) {
            try {
                return Long.parseLong(header);
            } catch (NumberFormatException e) {
                // 숫자가 아니면 코드로 조회 (예: "dev" → tenant_id 조회)
                log.debug("Tenant ID header is not a number, treating as code: {}", header);
                // JWT에서 tenant_id를 우선 확인
                if (auth != null && auth.getPrincipal() instanceof Jwt) {
                    Object tid = ((Jwt) auth.getPrincipal()).getClaim("tenant_id");
                    if (tid != null) {
                        return Long.parseLong(tid.toString());
                    }
                }
                // Fallback: "dev" = 1L (개발 환경)
                return "dev".equals(header) || "default".equals(header) ? 1L : 1L;
            }
        }
        
        // 2. JWT 클레임에서 조회
        if (auth != null && auth.getPrincipal() instanceof Jwt) {
            Object tid = ((Jwt) auth.getPrincipal()).getClaim("tenant_id");
            if (tid != null) {
                return Long.parseLong(tid.toString());
            }
        }
        
        // 3. Fallback: 개발 환경 기본값
        log.warn("Tenant ID not found in header or JWT, using fallback: 1L");
        return 1L;
    }
}
