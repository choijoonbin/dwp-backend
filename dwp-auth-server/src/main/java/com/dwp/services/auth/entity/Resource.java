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
}
