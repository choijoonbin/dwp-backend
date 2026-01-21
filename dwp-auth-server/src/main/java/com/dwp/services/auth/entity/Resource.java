package com.dwp.services.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 리소스 엔티티 (com_resources)
 * 
 * 메뉴, 버튼, 섹션, API 등을 포괄합니다.
 */
@Entity
@Table(name = "com_resources",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "type", "key"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Resource extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "resource_id")
    private Long resourceId;
    
    @Column(name = "tenant_id")
    private Long tenantId;  // nullable (global resource)
    
    @Column(name = "type", nullable = false, length = 20)
    private String type;  // MENU, UI_COMPONENT, PAGE_SECTION, API
    
    @Column(name = "key", nullable = false, length = 255)
    private String key;  // menu.mail.inbox, btn.mail.send
    
    @Column(name = "name", nullable = false, length = 200)
    private String name;
    
    @Column(name = "parent_resource_id")
    private Long parentResourceId;
    
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;
    
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;
    
    @Column(name = "resource_category", nullable = false, length = 50)
    @Builder.Default
    private String resourceCategory = "MENU";  // MENU, UI_COMPONENT
    
    @Column(name = "resource_kind", nullable = false, length = 50)
    @Builder.Default
    private String resourceKind = "PAGE";  // MENU_GROUP, PAGE, BUTTON, TAB, SELECT, FILTER, SEARCH, TABLE_ACTION, DOWNLOAD, UPLOAD, MODAL, API_ACTION
    
    @Column(name = "event_key", length = 120)
    private String eventKey;  // 예: menu.admin.users:view
    
    @Column(name = "event_actions", columnDefinition = "jsonb")
    private String eventActions;  // JSON 배열: ["VIEW","CLICK","SUBMIT"]
    
    @Column(name = "tracking_enabled", nullable = false)
    @Builder.Default
    private Boolean trackingEnabled = true;
    
    @Column(name = "ui_scope", length = 30)
    private String uiScope;  // GLOBAL, MENU, PAGE, COMPONENT
    
    @Column(name = "sort_order")
    private Integer sortOrder;
}
