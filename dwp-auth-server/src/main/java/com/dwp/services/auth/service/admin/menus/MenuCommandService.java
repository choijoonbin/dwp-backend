package com.dwp.services.auth.service.admin.menus;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.CreateMenuRequest;
import com.dwp.services.auth.dto.admin.MenuSummary;
import com.dwp.services.auth.dto.admin.ReorderMenuRequest;
import com.dwp.services.auth.dto.admin.UpdateMenuRequest;
import com.dwp.services.auth.entity.Menu;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.repository.MenuRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.util.CodeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * PR-05B: 메뉴 명령 서비스 (CQRS: Command 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
@SuppressWarnings("null")
public class MenuCommandService {
    
    private final MenuRepository menuRepository;
    private final ResourceRepository resourceRepository;
    @SuppressWarnings("unused") // 향후 코드 검증 시 사용 예정
    private final CodeResolver codeResolver;
    private final AuditLogService auditLogService;
    @SuppressWarnings("unused") // 향후 metadata 처리 시 사용 예정
    private final ObjectMapper objectMapper;
    
    /**
     * PR-05B: 메뉴 생성
     * PR-05C: com_resources에 대응 MENU 리소스 자동 생성
     */
    public MenuSummary createMenu(Long tenantId, Long actorUserId, CreateMenuRequest request,
                                 HttpServletRequest httpRequest) {
        // 중복 체크
        menuRepository.findByTenantIdAndMenuKey(tenantId, request.getMenuKey())
                .ifPresent(m -> {
                    throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 메뉴 키입니다.");
                });
        
        // 부모 메뉴 확인
        String parentMenuKey = null;
        Integer depth = 1;
        if (request.getParentMenuId() != null) {
            Menu parent = menuRepository.findByTenantIdAndSysMenuId(tenantId, request.getParentMenuId())
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부모 메뉴를 찾을 수 없습니다."));
            parentMenuKey = parent.getMenuKey();
            depth = parent.getDepth() + 1;
        } else if (request.getParentMenuKey() != null && !request.getParentMenuKey().isEmpty()) {
            Menu parent = menuRepository.findByTenantIdAndMenuKey(tenantId, request.getParentMenuKey())
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부모 메뉴를 찾을 수 없습니다."));
            parentMenuKey = parent.getMenuKey();
            depth = parent.getDepth() + 1;
        }
        
        // sortOrder 기본값
        Integer sortOrder = request.getSortOrder() != null ? request.getSortOrder() : 0;
        
        Menu menu = Menu.builder()
                .tenantId(tenantId)
                .menuKey(request.getMenuKey())
                .menuName(request.getMenuName())
                .menuPath(request.getRoutePath())
                .menuIcon(request.getIcon())
                .menuGroup(request.getMenuGroup())
                .parentMenuKey(parentMenuKey)
                .sortOrder(sortOrder)
                .depth(depth)
                .isVisible(request.getVisible() != null && request.getVisible() ? "Y" : "N")
                .isEnabled(request.getEnabled() != null && request.getEnabled() ? "Y" : "N")
                .description(request.getDescription())
                .build();
        menu = menuRepository.save(menu);
        
        // PR-05C: com_resources에 대응 MENU 리소스 자동 생성 (upsert)
        syncResourceFromMenu(tenantId, menu);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "MENU_CREATE", "MENU", menu.getSysMenuId(),
                null, menu, httpRequest);
        
        return toMenuSummary(menu);
    }
    
    /**
     * PR-05B: 메뉴 수정
     */
    public MenuSummary updateMenu(Long tenantId, Long actorUserId, Long sysMenuId,
                                 UpdateMenuRequest request, HttpServletRequest httpRequest) {
        Menu menu = menuRepository.findByTenantIdAndSysMenuId(tenantId, sysMenuId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "메뉴를 찾을 수 없습니다."));
        
        Menu before = copyMenu(menu);
        
        if (request.getMenuName() != null) {
            menu.setMenuName(request.getMenuName());
        }
        if (request.getRoutePath() != null) {
            menu.setMenuPath(request.getRoutePath());
        }
        if (request.getIcon() != null) {
            menu.setMenuIcon(request.getIcon());
        }
        if (request.getMenuGroup() != null) {
            menu.setMenuGroup(request.getMenuGroup());
        }
        if (request.getSortOrder() != null) {
            menu.setSortOrder(request.getSortOrder());
        }
        if (request.getDescription() != null) {
            menu.setDescription(request.getDescription());
        }
        if (request.getEnabled() != null) {
            menu.setIsEnabled(request.getEnabled() ? "Y" : "N");
        }
        if (request.getVisible() != null) {
            menu.setIsVisible(request.getVisible() ? "Y" : "N");
        }
        
        // 부모 메뉴 변경
        if (request.getParentMenuId() != null || (request.getParentMenuKey() != null && !request.getParentMenuKey().isEmpty())) {
            String newParentMenuKey = null;
            Integer newDepth = 1;
            
            if (request.getParentMenuId() != null) {
                Menu parent = menuRepository.findByTenantIdAndSysMenuId(tenantId, request.getParentMenuId())
                        .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부모 메뉴를 찾을 수 없습니다."));
                newParentMenuKey = parent.getMenuKey();
                newDepth = parent.getDepth() + 1;
            } else {
                Menu parent = menuRepository.findByTenantIdAndMenuKey(tenantId, request.getParentMenuKey())
                        .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부모 메뉴를 찾을 수 없습니다."));
                newParentMenuKey = parent.getMenuKey();
                newDepth = parent.getDepth() + 1;
            }
            
            // 자기 자신을 부모로 설정 금지
            if (newParentMenuKey != null && newParentMenuKey.equals(menu.getMenuKey())) {
                throw new BaseException(ErrorCode.INVALID_STATE, "자기 자신을 부모로 설정할 수 없습니다.");
            }
            
            menu.setParentMenuKey(newParentMenuKey);
            menu.setDepth(newDepth);
        }
        
        menu = menuRepository.save(menu);
        
        // PR-05C: com_resources 동기화 (메뉴명 변경 시)
        syncResourceFromMenu(tenantId, menu);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "MENU_UPDATE", "MENU", sysMenuId,
                before, menu, httpRequest);
        
        return toMenuSummary(menu);
    }
    
    /**
     * PR-05B: 메뉴 삭제 (Soft Delete + 하위 메뉴 충돌 정책)
     */
    public void deleteMenu(Long tenantId, Long actorUserId, Long sysMenuId, HttpServletRequest httpRequest) {
        Menu menu = menuRepository.findByTenantIdAndSysMenuId(tenantId, sysMenuId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "메뉴를 찾을 수 없습니다."));
        
        // PR-05B: 하위 메뉴 존재 확인
        long childCount = menuRepository.countByTenantIdAndParentMenuId(tenantId, sysMenuId);
        if (childCount > 0) {
            throw new BaseException(ErrorCode.RESOURCE_HAS_CHILDREN,
                    String.format("하위 메뉴가 존재합니다 (%d개). 하위 메뉴를 먼저 제거해주세요.", childCount));
        }
        
        Menu before = copyMenu(menu);
        
        // Soft delete (is_enabled = 'N')
        menu.setIsEnabled("N");
        menu.setIsVisible("N");
        menuRepository.save(menu);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "MENU_DELETE", "MENU", sysMenuId,
                before, menu, httpRequest);
    }
    
    /**
     * PR-05D: 메뉴 정렬/이동 (DragDrop 대비)
     */
    public void reorderMenus(Long tenantId, Long actorUserId, ReorderMenuRequest request,
                            HttpServletRequest httpRequest) {
        for (ReorderMenuRequest.MenuOrderItem item : request.getItems()) {
            Menu menu = menuRepository.findByTenantIdAndSysMenuId(tenantId, item.getMenuId())
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND,
                            String.format("메뉴를 찾을 수 없습니다: menuId=%d", item.getMenuId())));
            
            Menu before = copyMenu(menu);
            
            // 부모 변경
            String newParentMenuKey = null;
            Integer newDepth = 1;
            if (item.getParentId() != null) {
                Menu parent = menuRepository.findByTenantIdAndSysMenuId(tenantId, item.getParentId())
                        .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND,
                                String.format("부모 메뉴를 찾을 수 없습니다: parentId=%d", item.getParentId())));
                newParentMenuKey = parent.getMenuKey();
                newDepth = parent.getDepth() + 1;
            } else if (item.getParentMenuKey() != null && !item.getParentMenuKey().isEmpty()) {
                Menu parent = menuRepository.findByTenantIdAndMenuKey(tenantId, item.getParentMenuKey())
                        .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND,
                                String.format("부모 메뉴를 찾을 수 없습니다: parentMenuKey=%s", item.getParentMenuKey())));
                newParentMenuKey = parent.getMenuKey();
                newDepth = parent.getDepth() + 1;
            }
            
            menu.setParentMenuKey(newParentMenuKey);
            menu.setDepth(newDepth);
            menu.setSortOrder(item.getSortOrder());
            menuRepository.save(menu);
            
            // 감사 로그
            auditLogService.recordAuditLog(tenantId, actorUserId, "MENU_REORDER", "MENU", item.getMenuId(),
                    before, menu, httpRequest);
        }
    }
    
    /**
     * PR-05C: Menu 생성/수정 시 com_resources에 대응 MENU 리소스 자동 생성 (upsert)
     */
    private void syncResourceFromMenu(Long tenantId, Menu menu) {
        List<Resource> existingResources = resourceRepository.findByTenantIdAndKey(tenantId, menu.getMenuKey());
        Resource resource = existingResources.stream()
                .filter(r -> r.getTenantId() != null && r.getTenantId().equals(tenantId))
                .findFirst()
                .orElse(null);
        
        if (resource == null) {
            // 신규 생성
            String resourceKind = menu.getParentMenuKey() == null ? "MENU_GROUP" : "PAGE";
            String eventKey = menu.getMenuKey() + ":view";
            String eventActions = "[\"VIEW\",\"USE\"]";
            
            resource = Resource.builder()
                    .tenantId(tenantId)
                    .type("MENU")
                    .key(menu.getMenuKey())
                    .name(menu.getMenuName())
                    .resourceCategory("MENU")
                    .resourceKind(resourceKind)
                    .eventKey(eventKey)
                    .eventActions(eventActions)
                    .trackingEnabled(true)
                    .enabled("Y".equals(menu.getIsEnabled()))
                    .build();
        } else {
            // 기존 리소스 업데이트 (메뉴명 동기화)
            resource.setName(menu.getMenuName());
            resource.setEnabled("Y".equals(menu.getIsEnabled()));
        }
        
        resourceRepository.save(resource);
        log.info("Synced resource from menu: tenantId={}, menuKey={}, resourceId={}, resourceKey={}", 
                tenantId, menu.getMenuKey(), resource.getResourceId(), resource.getKey());
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
    
    private Menu copyMenu(Menu menu) {
        return Menu.builder()
                .sysMenuId(menu.getSysMenuId())
                .tenantId(menu.getTenantId())
                .menuKey(menu.getMenuKey())
                .menuName(menu.getMenuName())
                .menuPath(menu.getMenuPath())
                .menuIcon(menu.getMenuIcon())
                .menuGroup(menu.getMenuGroup())
                .parentMenuKey(menu.getParentMenuKey())
                .sortOrder(menu.getSortOrder())
                .depth(menu.getDepth())
                .isVisible(menu.getIsVisible())
                .isEnabled(menu.getIsEnabled())
                .description(menu.getDescription())
                .build();
    }
}
