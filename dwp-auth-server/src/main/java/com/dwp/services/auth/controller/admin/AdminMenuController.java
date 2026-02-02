package com.dwp.services.auth.controller.admin;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.MenuNode;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.service.admin.menus.MenuManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * PR-05B: Admin 메뉴 관리 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/admin/menus")
@RequiredArgsConstructor
public class AdminMenuController {
    
    private final MenuManagementService menuManagementService;
    
    /**
     * PR-05B: 메뉴 목록 조회
     * GET /api/admin/menus
     */
    @GetMapping
    public ApiResponse<PageResponse<MenuSummary>> getMenus(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Long parentId) {
        return ApiResponse.success(menuManagementService.getMenus(tenantId, page, size, keyword, enabled, parentId));
    }
    
    /**
     * PR-05B: 메뉴 트리 조회 (권한 필터 없이 전체 트리)
     * GET /api/admin/menus/tree
     */
    @GetMapping("/tree")
    public ApiResponse<List<MenuNode>> getMenuTree(
            @RequestHeader("X-Tenant-ID") Long tenantId) {
        return ApiResponse.success(menuManagementService.getMenuTree(tenantId));
    }
    
    /**
     * PR-05B: 메뉴 생성
     * POST /api/admin/menus
     */
    @PostMapping
    public ApiResponse<MenuSummary> createMenu(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @Valid @RequestBody CreateMenuRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        return ApiResponse.success(menuManagementService.createMenu(tenantId, actorUserId, request, httpRequest));
    }
    
    /**
     * PR-05B: 메뉴 수정
     * PATCH /api/admin/menus/{sysMenuId}
     */
    @PatchMapping("/{sysMenuId}")
    public ApiResponse<MenuSummary> updateMenu(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("sysMenuId") Long sysMenuId,
            @Valid @RequestBody UpdateMenuRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        return ApiResponse.success(menuManagementService.updateMenu(tenantId, actorUserId, sysMenuId, request, httpRequest));
    }
    
    /**
     * PR-05B: 메뉴 삭제
     * DELETE /api/admin/menus/{sysMenuId}
     */
    @DeleteMapping("/{sysMenuId}")
    public ApiResponse<?> deleteMenu(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("sysMenuId") Long sysMenuId,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        menuManagementService.deleteMenu(tenantId, actorUserId, sysMenuId, httpRequest);
        return ApiResponse.successOk();
    }
    
    /**
     * PR-05D: 메뉴 정렬/이동 (DragDrop 대비)
     * PUT /api/admin/menus/reorder
     */
    @PutMapping("/reorder")
    public ApiResponse<?> reorderMenus(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @Valid @RequestBody ReorderMenuRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        menuManagementService.reorderMenus(tenantId, actorUserId, request, httpRequest);
        return ApiResponse.successOk();
    }
    
    private Long getUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            return Long.parseLong(((Jwt) authentication.getPrincipal()).getSubject());
        }
        return null;
    }
}
