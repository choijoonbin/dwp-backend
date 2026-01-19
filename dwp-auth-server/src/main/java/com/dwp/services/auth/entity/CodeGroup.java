package com.dwp.services.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 코드 그룹 엔티티 (sys_code_groups)
 * 
 * 공통 코드의 그룹을 관리하는 마스터 테이블
 */
@Entity
@Table(name = "sys_code_groups",
    uniqueConstraints = @UniqueConstraint(columnNames = "group_key")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodeGroup extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sys_code_group_id")
    private Long sysCodeGroupId;
    
    @Column(name = "group_key", nullable = false, length = 100, unique = true)
    private String groupKey;
    
    @Column(name = "group_name", nullable = false, length = 200)
    private String groupName;
    
    @Column(name = "description", length = 500)
    private String description;
    
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;
}
