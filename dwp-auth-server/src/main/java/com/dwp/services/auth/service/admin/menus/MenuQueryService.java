package com.dwp.services.auth.service.admin.menus;

import com.dwp.services.auth.dto.MenuNode;
import com.dwp.services.auth.dto.admin.MenuSummary;
import com.dwp.services.auth.entity.Menu;
import com.dwp.services.auth.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PR-05B: 메뉴 조회 서비스 (CQRS: Query 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@SuppressWarnings("null")
public class MenuQueryService {
    
    private final MenuRepository menuRepository;
    
    /**
     * PR-05B: 메뉴 목록 조회
     */
    public com.dwp.services.auth.dto.admin.PageResponse<MenuSummary> getMenus(
            Long tenantId, int page, int size, String keyword, Boolean enabled, Long parentId) {
        // 페이징 크기 제한 (최대 200)
        if (size > 200) {
            size = 200;
        }
        if (size < 1) {
            size = 20;
        }
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Menu> menuPage = menuRepository.findByTenantIdAndFilters(tenantId, keyword, enabled, parentId, pageable);
        
        List<MenuSummary> summaries = menuPage.getContent().stream()
                .map(this::toMenuSummary)
                .collect(Collectors.toList());
        
        return com.dwp.services.auth.dto.admin.PageResponse.<MenuSummary>builder()
                .items(summaries)
                .page(page)
                .size(size)
                .totalItems(menuPage.getTotalElements())
                .totalPages(menuPage.getTotalPages())
                .build();
    }
    
    /**
     * PR-05B: 메뉴 트리 조회 (권한 필터 없이 전체 트리)
     */
    public List<MenuNode> getMenuTree(Long tenantId) {
        List<Menu> allMenus = menuRepository.findByTenantIdAndActive(tenantId);
        
        // Menu -> MenuNode 변환
        Map<String, MenuNode> nodeMap = new HashMap<>();
        List<MenuNode> rootNodes = new ArrayList<>();
        
        for (Menu menu : allMenus) {
            MenuNode node = MenuNode.builder()
                    .menuKey(menu.getMenuKey())
                    .menuName(menu.getMenuName())
                    .path(menu.getMenuPath())
                    .icon(menu.getMenuIcon())
                    .group(menu.getMenuGroup())
                    .depth(menu.getDepth())
                    .sortOrder(menu.getSortOrder())
                    .build();
            
            nodeMap.put(menu.getMenuKey(), node);
        }
        
        // 부모-자식 관계 구성
        for (Menu menu : allMenus) {
            MenuNode node = nodeMap.get(menu.getMenuKey());
            if (menu.getParentMenuKey() == null) {
                rootNodes.add(node);
            } else {
                MenuNode parentNode = nodeMap.get(menu.getParentMenuKey());
                if (parentNode != null) {
                    parentNode.addChild(node);
                } else {
                    // 부모가 없으면 루트로 처리
                    rootNodes.add(node);
                }
            }
        }
        
        // sortOrder 기준 정렬
        rootNodes.sort((a, b) -> Integer.compare(
                a.getSortOrder() != null ? a.getSortOrder() : 0,
                b.getSortOrder() != null ? b.getSortOrder() : 0));
        
        return rootNodes;
    }
    
    private MenuSummary toMenuSummary(Menu menu) {
        final Long[] parentMenuId = {null};
        final String[] parentMenuName = {null};
        
        if (menu.getParentMenuKey() != null) {
            menuRepository.findByTenantIdAndMenuKey(menu.getTenantId(), menu.getParentMenuKey())
                    .ifPresent(parent -> {
                        parentMenuId[0] = parent.getSysMenuId();
                        parentMenuName[0] = parent.getMenuName();
                    });
        }
        
        return MenuSummary.builder()
                .sysMenuId(menu.getSysMenuId())
                .menuKey(menu.getMenuKey())
                .menuName(menu.getMenuName())
                .menuPath(menu.getMenuPath())
                .menuIcon(menu.getMenuIcon())
                .menuGroup(menu.getMenuGroup())
                .parentMenuId(parentMenuId[0])
                .parentMenuKey(menu.getParentMenuKey())
                .parentMenuName(parentMenuName[0])
                .sortOrder(menu.getSortOrder())
                .depth(menu.getDepth())
                .isVisible("Y".equals(menu.getIsVisible()))
                .isEnabled("Y".equals(menu.getIsEnabled()))
                .description(menu.getDescription())
                .createdAt(menu.getCreatedAt())
                .updatedAt(menu.getUpdatedAt())
                .build();
    }
}
