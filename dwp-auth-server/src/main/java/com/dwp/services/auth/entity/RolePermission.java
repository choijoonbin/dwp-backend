package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "com_role_permissions",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"tenant_id", "role_id", "resource_id", "permission_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
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

    @Builder.Default
    @Column(nullable = false, length = 10)
    private String effect = "ALLOW";
}
