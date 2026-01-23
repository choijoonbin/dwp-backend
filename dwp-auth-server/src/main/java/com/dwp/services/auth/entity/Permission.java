package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 권한 엔티티 (com_permissions)
 */
@Entity
@Table(name = "com_permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "permission_id")
    private Long permissionId;
    
    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;  // VIEW, USE, EDIT, APPROVE, EXECUTE
    
    @Column(name = "name", nullable = false, length = 200)
    private String name;
    
    @Column(name = "sort_order")
    private Integer sortOrder;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}
