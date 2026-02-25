package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.MenuEntryDto;
import com.dwp.services.auth.dto.MenuTreeResponse;
import com.dwp.services.auth.service.MenuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantIdHeader,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {
        
        Jwt jwt = (Jwt) authentication.getPrincipal();
        Long userId = Long.parseLong(jwt.getSubject());
        Long tenantId = parseTenantId(tenantIdHeader, authentication);
        Long headerUserId = parseUserId(userIdHeader);
        Object jwtTenantClaim = jwt.getClaim("tenant_id");

        log.info("TRACE /auth/menus/tree input: jwtSubUserId={}, headerUserId={}, headerTenantId={}, jwtTenantClaim={}",
                userId, headerUserId, tenantIdHeader, jwtTenantClaim);
        if (headerUserId != null && !headerUserId.equals(userId)) {
            log.warn("TRACE /auth/menus/tree mismatch: headerUserId={} != jwtSubUserId={}. JWT subject is used.",
                    headerUserId, userId);
        }
        log.info("TRACE /auth/menus/tree resolved: effectiveUserId={}, effectiveTenantId={}", userId, tenantId);
        
        MenuTreeResponse response = menuService.getMenuTree(userId, tenantId);
        int rootCount = response != null && response.getMenus() != null ? response.getMenus().size() : 0;
        log.info("TRACE /auth/menus/tree result: effectiveUserId={}, effectiveTenantId={}, rootMenuCount={}",
                userId, tenantId, rootCount);
        return ApiResponse.success(response);
    }

    /**
     * 메뉴 엔트리 조회 (워크벤치 deepLink 등 연동용).
     * GET /api/auth/menus/entries?keys=menu.knowledge-policy.rag,menu.knowledge-policy.policies
     * 요청한 keys 중 사용자가 VIEW 권한이 있는 메뉴만 반환 (menuKey, label, deepLink).
     */
    @GetMapping("/entries")
    public ApiResponse<List<MenuEntryDto>> getMenuEntries(
            Authentication authentication,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantIdHeader,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestParam("keys") List<String> keys) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        Long userId = Long.parseLong(jwt.getSubject());
        Long tenantId = parseTenantId(tenantIdHeader, authentication);
        Long headerUserId = parseUserId(userIdHeader);
        log.info("TRACE /auth/menus/entries input: jwtSubUserId={}, headerUserId={}, headerTenantId={}, requestedKeys={}",
                userId, headerUserId, tenantIdHeader, keys != null ? keys.size() : 0);
        if (headerUserId != null && !headerUserId.equals(userId)) {
            log.warn("TRACE /auth/menus/entries mismatch: headerUserId={} != jwtSubUserId={}. JWT subject is used.",
                    headerUserId, userId);
        }
        List<MenuEntryDto> entries = menuService.getMenuEntries(userId, tenantId, keys);
        log.info("TRACE /auth/menus/entries result: effectiveUserId={}, effectiveTenantId={}, entryCount={}",
                userId, tenantId, entries != null ? entries.size() : 0);
        return ApiResponse.success(entries);
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

    private Long parseUserId(String header) {
        if (header == null || header.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid X-User-ID header: {}", header);
            return null;
        }
    }
}
