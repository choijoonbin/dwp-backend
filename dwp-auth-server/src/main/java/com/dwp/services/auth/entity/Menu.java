package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 메뉴 엔티티 (sys_menus)
 * 
 * 메뉴 트리 구조, 정렬, 라우트, 아이콘, 노출 제어를 위한 메타 테이블
 * 권한은 com_resources + com_role_permissions에서 관리
 */
@Entity
@Table(name = "sys_menus",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "menu_key"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Menu extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sys_menu_id")
    private Long sysMenuId;
    
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    
    @Column(name = "menu_key", nullable = false, length = 255)
    private String menuKey;
    
    @Column(name = "menu_name", nullable = false, length = 200)
    private String menuName;
    
    @Column(name = "menu_path", length = 500)
    private String menuPath;
    
    @Column(name = "menu_icon", length = 100)
    private String menuIcon;
    
    @Column(name = "menu_group", length = 50)
    private String menuGroup;
    
    @Column(name = "parent_menu_key", length = 255)
    private String parentMenuKey;
    
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
    
    @Column(name = "depth", nullable = false)
    @Builder.Default
    private Integer depth = 1;
    
    @Column(name = "is_visible", nullable = false, length = 1)
    @Builder.Default
    private String isVisible = "Y";
    
    @Column(name = "is_enabled", nullable = false, length = 1)
    @Builder.Default
    private String isEnabled = "Y";
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    /**
     * 메뉴가 활성화되어 있고 노출 가능한지 확인
     */
    public boolean isActiveAndVisible() {
        return "Y".equals(isEnabled) && "Y".equals(isVisible);
    }
}
