package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 로그인 계정 엔티티 (com_user_accounts)
 * 
 * LOCAL/SSO 등 다양한 인증 방식을 지원합니다.
 */
@Entity
@Table(name = "com_user_accounts",
    uniqueConstraints = @UniqueConstraint(
        columnNames = {"tenant_id", "provider_type", "provider_id", "principal"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAccount extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_account_id")
    private Long userAccountId;
    
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    @Column(name = "provider_type", nullable = false, length = 20)
    private String providerType;  // LOCAL, OIDC, SAML, LDAP
    
    @Column(name = "provider_id", nullable = false, length = 100)
    private String providerId;
    
    @Column(name = "principal", nullable = false, length = 255)
    private String principal;  // username, email, sub, nameId
    
    @Column(name = "password_hash", length = 255)
    private String passwordHash;  // LOCAL 전용, BCrypt
    
    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;
    
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";
}
