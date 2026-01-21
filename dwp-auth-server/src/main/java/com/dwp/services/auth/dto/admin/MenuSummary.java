package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PR-05B: 메뉴 요약 DTO (Admin 목록 조회용)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuSummary {
    
    private Long sysMenuId; // sys_menu_id
    private String menuKey;
    private String menuName;
    private String menuPath;
    private String menuIcon;
    private String menuGroup;
    private Long parentMenuId; // parent_menu_key로 조회한 sys_menu_id
    private String parentMenuKey;
    private String parentMenuName;
    private Integer sortOrder;
    private Integer depth;
    private Boolean isVisible; // "Y" -> true, "N" -> false
    private Boolean isEnabled; // "Y" -> true, "N" -> false
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
