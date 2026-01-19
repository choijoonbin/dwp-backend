package com.dwp.services.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 역할-권한 매핑 엔티티 (com_role_permissions)
 */
@Entity
@Table(name = "com_role_permissions",
    uniqueConstraints = @UniqueConstraint(
        columnNames = {"tenant_id", "role_id", "resource_id", "permission_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RolePermission extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_permission_id")
    private Long rolePermissionId;
    
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    
    @Column(name = "role_id", nullable = false)
    private Long roleId;
    
    @Column(name = "resource_id", nullable = false)
    private Long resourceId;
    
    @Column(name = "permission_id", nullable = false)
    private Long permissionId;
    
    @Column(name = "effect", nullable = false, length = 10)
    @Builder.Default
    private String effect = "ALLOW";  // ALLOW, DENY
}
