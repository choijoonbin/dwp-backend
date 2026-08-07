package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "com_role_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "role_id", "user_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleMember extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_member_id")
    private Long roleMemberId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
