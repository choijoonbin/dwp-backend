package com.dwp.services.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 메뉴 트리 응답 DTO
 * 
 * 권한 기반으로 필터링된 메뉴 트리 구조
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuTreeResponse {
    
    /**
     * 메뉴 트리 목록 (그룹별로 정렬됨)
     */
    private List<MenuNode> menus;
    
    /**
     * 그룹별 메뉴 목록 (선택적, 프론트에서 그룹별 렌더링 시 사용)
     */
    private List<MenuGroup> groups;
    
    /**
     * 메뉴 그룹 DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MenuGroup {
        /**
         * 그룹 코드 (예: MANAGEMENT, APPS)
         */
        private String groupCode;
        
        /**
         * 그룹명 (예: 관리, 앱)
         */
        private String groupName;
        
        /**
         * 해당 그룹의 메뉴 목록
         */
        private List<MenuNode> menus;
    }
}
