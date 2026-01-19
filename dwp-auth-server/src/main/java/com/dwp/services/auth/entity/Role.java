package com.dwp.services.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 역할 엔티티 (com_roles)
 */
@Entity
@Table(name = "com_roles",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "code"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_id")
    private Long roleId;
    
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    
    @Column(name = "code", nullable = false, length = 50)
    private String code;
    
    @Column(name = "name", nullable = false, length = 200)
    private String name;
    
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}
