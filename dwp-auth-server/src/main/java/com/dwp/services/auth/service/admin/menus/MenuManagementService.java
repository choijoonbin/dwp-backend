package com.dwp.services.auth.service.admin.menus;

import com.dwp.services.auth.dto.MenuNode;
import com.dwp.services.auth.dto.admin.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PR-05B: 메뉴 관리 서비스 (Facade)
 * 
 * MenuQueryService와 MenuCommandService를 통합하여 API 호환성 유지
 */
@Service
@RequiredArgsConstructor
public class MenuManagementService {
    
    private final MenuQueryService menuQueryService;
    private final MenuCommandService menuCommandService;
    
    /**
     * 메뉴 목록 조회
     */
    public PageResponse<MenuSummary> getMenus(Long tenantId, int page, int size,
                                             String keyword, Boolean enabled, Long parentId) {
        return menuQueryService.getMenus(tenantId, page, size, keyword, enabled, parentId);
    }
    
    /**
     * 메뉴 트리 조회 (권한 필터 없이 전체 트리)
     */
    public List<MenuNode> getMenuTree(Long tenantId) {
        return menuQueryService.getMenuTree(tenantId);
    }
    
    /**
     * 메뉴 생성
     */
    public MenuSummary createMenu(Long tenantId, Long actorUserId, CreateMenuRequest request,
                                 HttpServletRequest httpRequest) {
        return menuCommandService.createMenu(tenantId, actorUserId, request, httpRequest);
    }
    
    /**
     * 메뉴 수정
     */
    public MenuSummary updateMenu(Long tenantId, Long actorUserId, Long sysMenuId,
                                 UpdateMenuRequest request, HttpServletRequest httpRequest) {
        return menuCommandService.updateMenu(tenantId, actorUserId, sysMenuId, request, httpRequest);
    }
    
    /**
     * 메뉴 삭제
     */
    public void deleteMenu(Long tenantId, Long actorUserId, Long sysMenuId, HttpServletRequest httpRequest) {
        menuCommandService.deleteMenu(tenantId, actorUserId, sysMenuId, httpRequest);
    }
    
    /**
     * 메뉴 정렬/이동
     */
    public void reorderMenus(Long tenantId, Long actorUserId, ReorderMenuRequest request,
                            HttpServletRequest httpRequest) {
        menuCommandService.reorderMenus(tenantId, actorUserId, request, httpRequest);
    }
}
