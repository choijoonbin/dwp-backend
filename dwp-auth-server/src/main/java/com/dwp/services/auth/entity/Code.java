package com.dwp.services.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 코드 엔티티 (sys_codes)
 * 
 * 공통 코드 마스터 테이블
 */
@Entity
@Table(name = "sys_codes",
    uniqueConstraints = @UniqueConstraint(columnNames = {"group_key", "code"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Code extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sys_code_id")
    private Long sysCodeId;
    
    @Column(name = "group_key", nullable = false, length = 100)
    private String groupKey;
    
    @Column(name = "code", nullable = false, length = 100)
    private String code;
    
    @Column(name = "name", nullable = false, length = 200)
    private String name;
    
    @Column(name = "description", length = 500)
    private String description;
    
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
    
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
    
    @Column(name = "ext1", length = 500)
    private String ext1;
    
    @Column(name = "ext2", length = 500)
    private String ext2;
    
    @Column(name = "ext3", length = 500)
    private String ext3;
}
