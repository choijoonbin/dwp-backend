package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.MenuNode;
import com.dwp.services.auth.dto.MenuTreeResponse;
import com.dwp.services.auth.entity.*;
import com.dwp.services.auth.repository.*;
import com.dwp.services.auth.util.CodeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 메뉴 트리 서비스
 * 
 * 권한 기반으로 필터링된 메뉴 트리를 구성하여 반환합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class MenuService {
    
    private final RoleMemberRepository roleMemberRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final ResourceRepository resourceRepository;
    private final PermissionRepository permissionRepository;
    private final MenuRepository menuRepository;
    private final CodeResolver codeResolver;
    
    /**
     * 사용자별 권한 기반 메뉴 트리 조회
     * 
     * @param userId 사용자 ID
     * @param tenantId 테넌트 ID
     * @return 메뉴 트리 응답
     */
    @Transactional(readOnly = true)
    public MenuTreeResponse getMenuTree(Long userId, Long tenantId) {
        try {
            log.debug("Getting menu tree for userId={}, tenantId={}", userId, tenantId);
            
            // 1. 사용자의 역할 ID 목록 조회
            List<Long> roleIds = roleMemberRepository.findRoleIdsByTenantIdAndUserId(tenantId, userId);
            log.debug("Found {} roles for user", roleIds.size());
            if (roleIds.isEmpty()) {
                log.debug("No roles found, returning empty menu tree");
                return MenuTreeResponse.builder()
                        .menus(Collections.emptyList())
                        .groups(Collections.emptyList())
                        .build();
            }
            
            // 2. VIEW 권한 ID 조회
            Permission viewPermission = permissionRepository.findByCode("VIEW")
                    .orElseThrow(() -> {
                        log.error("VIEW permission not found in database");
                        return new IllegalStateException("VIEW permission not found");
                    });
            log.debug("Found VIEW permission: {}", viewPermission.getPermissionId());
            
            // 3. 역할-권한 매핑에서 MENU 타입 리소스의 VIEW=ALLOW 권한 조회
            List<RolePermission> rolePermissions = rolePermissionRepository.findByTenantIdAndRoleIdIn(tenantId, roleIds);
            List<Long> resourceIds = rolePermissions.stream()
                    .filter(rp -> rp.getPermissionId().equals(viewPermission.getPermissionId()))
                    .map(RolePermission::getResourceId)
                    .distinct()
                    .collect(Collectors.toList());
            
            log.debug("Found {} resources with VIEW permission", resourceIds.size());
            if (resourceIds.isEmpty()) {
                log.debug("No resources found, returning empty menu tree");
                return MenuTreeResponse.builder()
                        .menus(Collections.emptyList())
                        .groups(Collections.emptyList())
                        .build();
            }
            
            // 4. 리소스 조회 (MENU 타입만 필터링)
            List<Resource> resources = resourceRepository.findByResourceIdIn(resourceIds);
            String menuTypeCode = "MENU";
            Set<String> allowedMenuKeys = resources.stream()
                    .filter(r -> codeResolver.validate("RESOURCE_TYPE", r.getType()) && 
                                 menuTypeCode.equals(r.getType()))
                    .map(Resource::getKey)
                    .collect(Collectors.toSet());
            
            log.debug("Found {} menu keys with permission", allowedMenuKeys.size());
            if (allowedMenuKeys.isEmpty()) {
                log.debug("No menu keys found, returning empty menu tree");
                return MenuTreeResponse.builder()
                        .menus(Collections.emptyList())
                        .groups(Collections.emptyList())
                        .build();
            }
            
            // 5. sys_menus에서 메뉴 메타 조회
            List<Menu> menus = menuRepository.findByTenantIdAndMenuKeyIn(tenantId, new ArrayList<>(allowedMenuKeys));
            log.debug("Found {} menus from sys_menus", menus.size());
            
            // 6. 부모 메뉴 키 수집 (자식이 허용되면 부모도 포함)
            Set<String> parentMenuKeys = menus.stream()
                    .map(Menu::getParentMenuKey)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            
            // 부모 메뉴도 조회 (권한이 없어도 자식이 있으면 포함)
            if (!parentMenuKeys.isEmpty()) {
                log.debug("Found {} parent menu keys, fetching parent menus", parentMenuKeys.size());
                List<Menu> parentMenus = menuRepository.findByTenantIdAndMenuKeyIn(tenantId, new ArrayList<>(parentMenuKeys));
                // 중복 제거를 위해 Set으로 변환 후 다시 List로
                Map<String, Menu> menuMap = menus.stream()
                        .collect(Collectors.toMap(Menu::getMenuKey, m -> m, (m1, m2) -> m1));
                parentMenus.forEach(m -> menuMap.putIfAbsent(m.getMenuKey(), m));
                menus = new ArrayList<>(menuMap.values());
                log.debug("Total menus after adding parents: {}", menus.size());
            }
            
            // 7. 메뉴 트리 구성
            Map<String, MenuNode> nodeMap = new HashMap<>();
            List<MenuNode> rootNodes = new ArrayList<>();
            
            // 모든 메뉴를 MenuNode로 변환
            for (Menu menu : menus) {
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
            for (Menu menu : menus) {
                MenuNode node = nodeMap.get(menu.getMenuKey());
                if (menu.getParentMenuKey() == null) {
                    // 루트 메뉴
                    rootNodes.add(node);
                } else {
                    // 자식 메뉴
                    MenuNode parentNode = nodeMap.get(menu.getParentMenuKey());
                    if (parentNode != null) {
                        parentNode.addChild(node);
                        // 부모의 path가 없으면 첫번째 자식의 path로 fallback
                        if (parentNode.getPath() == null && node.getPath() != null) {
                            parentNode.setPath(node.getPath());
                        }
                    } else {
                        // 부모가 없으면 루트로 처리
                        rootNodes.add(node);
                    }
                }
            }
            
            // 8. 정렬 (sortOrder 기준)
            rootNodes.sort(Comparator.comparing(MenuNode::getSortOrder));
            nodeMap.values().forEach(node -> {
                if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                    node.getChildren().sort(Comparator.comparing(MenuNode::getSortOrder));
                }
            });
            
            // 9. 그룹별 분류 (선택적)
            Map<String, List<MenuNode>> groupMap = rootNodes.stream()
                    .collect(Collectors.groupingBy(
                            node -> node.getGroup() != null ? node.getGroup() : "DEFAULT",
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
            
            List<MenuTreeResponse.MenuGroup> groups = groupMap.entrySet().stream()
                    .map(entry -> MenuTreeResponse.MenuGroup.builder()
                            .groupCode(entry.getKey())
                            .groupName(getGroupName(entry.getKey()))
                            .menus(entry.getValue())
                            .build())
                    .collect(Collectors.toList());
            
            log.debug("Built menu tree with {} root nodes, {} groups", rootNodes.size(), groups.size());
            return MenuTreeResponse.builder()
                    .menus(rootNodes)
                    .groups(groups)
                    .build();
        } catch (Exception e) {
            log.error("Error building menu tree for userId={}, tenantId={}", userId, tenantId, e);
            throw e;
        }
    }
    
    /**
     * 그룹 코드를 그룹명으로 변환
     */
    private String getGroupName(String groupCode) {
        return switch (groupCode) {
            case "MANAGEMENT" -> "관리";
            case "APPS" -> "앱";
            default -> "기타";
        };
    }
}
