package com.dwp.services.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 인증 정책 엔티티 (sys_auth_policies)
 * 
 * 테넌트별 로그인 정책을 관리합니다.
 */
@Entity
@Table(name = "sys_auth_policies",
    uniqueConstraints = @UniqueConstraint(columnNames = "tenant_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthPolicy extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "auth_policy_id")
    private Long authPolicyId;
    
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    
    @Column(name = "default_login_type", nullable = false, length = 30)
    @Builder.Default
    private String defaultLoginType = "LOCAL"; // LOCAL, SSO
    
    @Column(name = "allowed_login_types", nullable = false, length = 100)
    @Builder.Default
    private String allowedLoginTypes = "LOCAL"; // CSV: LOCAL,SSO
    
    @Column(name = "sso_provider_key", length = 100)
    private String ssoProviderKey; // OKTA, AZURE_AD, SAML_SKT
    
    @Column(name = "local_login_enabled", nullable = false)
    @Builder.Default
    private Boolean localLoginEnabled = true;
    
    @Column(name = "sso_login_enabled", nullable = false)
    @Builder.Default
    private Boolean ssoLoginEnabled = false;
    
    @Column(name = "require_mfa", nullable = false)
    @Builder.Default
    private Boolean requireMfa = false;
    
    // 기존 컬럼 (하위 호환성)
    @Column(name = "default_login_method", length = 20)
    private String defaultLoginMethod; // LOCAL, SSO_ONLY, LOCAL_OR_SSO (deprecated)
    
    @Column(name = "allowed_providers_json", columnDefinition = "TEXT")
    private String allowedProvidersJson; // JSON (deprecated)
    
    @Column(name = "token_ttl_sec")
    private Integer tokenTtlSec;
}
