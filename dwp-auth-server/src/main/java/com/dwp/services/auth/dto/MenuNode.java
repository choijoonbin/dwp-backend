package com.dwp.services.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 메뉴 트리 노드 DTO
 * 
 * 프론트엔드 사이드바 렌더링을 위한 메뉴 트리 구조
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MenuNode {
    
    /**
     * 메뉴 키 (예: menu.admin.users)
     */
    private String menuKey;
    
    /**
     * 화면 노출명 (예: 사용자 관리)
     */
    private String menuName;
    
    /**
     * 라우트 경로 (예: /admin/users)
     */
    private String path;
    
    /**
     * 아이콘 키 (예: solar:settings-bold)
     */
    private String icon;
    
    /**
     * 메뉴 그룹 (예: MANAGEMENT, APPS)
     */
    private String group;
    
    /**
     * 메뉴 깊이 (1=루트, 2=하위, 3=하하위)
     */
    private Integer depth;
    
    /**
     * 정렬 순서
     */
    private Integer sortOrder;
    
    /**
     * 자식 메뉴 목록
     */
    @Builder.Default
    private List<MenuNode> children = new ArrayList<>();
    
    /**
     * 자식 메뉴 추가
     */
    public void addChild(MenuNode child) {
        if (children == null) {
            children = new ArrayList<>();
        }
        children.add(child);
    }
}
