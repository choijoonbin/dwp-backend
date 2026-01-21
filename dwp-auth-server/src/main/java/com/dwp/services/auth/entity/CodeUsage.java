package com.dwp.services.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 코드 사용 정의 엔티티 (sys_code_usages)
 * 
 * 메뉴(리소스)별 코드 사용 범위를 정의합니다.
 */
@Entity
@Table(name = "sys_code_usages",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "resource_key", "code_group_key"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodeUsage extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sys_code_usage_id")
    private Long sysCodeUsageId;
    
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    
    @Column(name = "resource_key", nullable = false, length = 200, columnDefinition = "VARCHAR(200)")
    private String resourceKey; // menu.admin.users 등
    
    @Column(name = "code_group_key", nullable = false, length = 100, columnDefinition = "VARCHAR(100)")
    private String codeGroupKey; // RESOURCE_TYPE, SUBJECT_TYPE 등
    
    @Column(name = "scope", nullable = false, length = 30, columnDefinition = "VARCHAR(30)")
    @Builder.Default
    private String scope = "MENU"; // MENU, PAGE, MODULE
    
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = true;
    
    @Column(name = "sort_order")
    private Integer sortOrder;
    
    @Column(name = "remark", length = 500)
    private String remark;
}
