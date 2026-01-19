package com.dwp.services.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 사용자 엔티티 (com_users)
 */
@Entity
@Table(name = "com_users",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "email"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;
    
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    
    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;
    
    @Column(name = "email", length = 255)
    private String email;
    
    @Column(name = "primary_department_id")
    private Long primaryDepartmentId;
    
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";
}
