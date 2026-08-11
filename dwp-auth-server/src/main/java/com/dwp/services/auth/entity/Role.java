package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "com_roles",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "code"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_id")
    private Long roleId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "role_type", nullable = false, length = 20)
    private String roleType = "CUSTOM";

    @Builder.Default
    @Column(nullable = false)
    private Boolean privileged = false;

    @Builder.Default
    @Column(name = "assignable_to_groups", nullable = false)
    private Boolean assignableToGroups = true;

    @Column(name = "builtin_role_code", length = 50)
    private String builtinRoleCode;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Version
    @Column(nullable = false)
    private Long version;
}
