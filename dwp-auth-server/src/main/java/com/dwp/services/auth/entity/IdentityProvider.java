package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Identity Provider 엔티티 (sys_identity_providers)
 * 
 * SSO Identity Provider 설정을 관리합니다.
 */
@Entity
@Table(name = "sys_identity_providers",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "provider_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdentityProvider extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "identity_provider_id")
    private Long identityProviderId;
    
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    
    @Column(name = "provider_type", nullable = false, length = 20)
    private String providerType; // OIDC, SAML, LDAP
    
    @Column(name = "provider_id", nullable = false, length = 100)
    private String providerId; // 테넌트 범위 유니크
    
    @Column(name = "provider_key", length = 100)
    private String providerKey; // AZURE_AD, OKTA, SAML_SKT
    
    @Column(name = "name", nullable = false, length = 200)
    private String name;
    
    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private Boolean enabled = false;
    
    @Column(name = "auth_url", length = 500)
    private String authUrl; // OIDC: authorization_endpoint
    
    @Column(name = "token_url", length = 500)
    private String tokenUrl; // OIDC: token_endpoint
    
    @Column(name = "metadata_url", length = 500)
    private String metadataUrl; // OIDC: /.well-known/openid-configuration, SAML: metadata
    
    @Column(name = "jwks_url", length = 500)
    private String jwksUrl; // OIDC: jwks_uri
    
    @Column(name = "client_id", length = 255)
    private String clientId; // OIDC client_id
    
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson; // 기타 설정 (JSON)
    
    @Column(name = "ext1", length = 500)
    private String ext1;
    
    @Column(name = "ext2", length = 500)
    private String ext2;
    
    @Column(name = "ext3", length = 500)
    private String ext3;
}
