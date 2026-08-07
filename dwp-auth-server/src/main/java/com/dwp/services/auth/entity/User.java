package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "com_users",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "email"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(length = 255)
    private String email;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Builder.Default
    @Column(name = "mfa_enabled", nullable = false)
    private Boolean mfaEnabled = false;
}
