package com.dwp.services.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 역할 할당 엔티티 (com_role_members)
 * 
 * 사용자 또는 부서에 역할을 할당합니다.
 */
@Entity
@Table(name = "com_role_members",
    uniqueConstraints = @UniqueConstraint(
        columnNames = {"tenant_id", "role_id", "subject_type", "subject_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleMember extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_member_id")
    private Long roleMemberId;
    
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    
    @Column(name = "role_id", nullable = false)
    private Long roleId;
    
    @Column(name = "subject_type", nullable = false, length = 20)
    private String subjectType;  // USER, DEPARTMENT
    
    @Column(name = "subject_id", nullable = false)
    private Long subjectId;
}
