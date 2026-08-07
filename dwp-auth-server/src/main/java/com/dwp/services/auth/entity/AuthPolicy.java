package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "sys_auth_policies",
        uniqueConstraints = @UniqueConstraint(columnNames = "tenant_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthPolicy extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "auth_policy_id")
    private Long authPolicyId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Builder.Default
    @Column(name = "default_login_type", nullable = false, length = 30)
    private String defaultLoginType = "LOCAL";

    @Builder.Default
    @Column(name = "allowed_login_types", nullable = false, length = 100)
    private String allowedLoginTypes = "LOCAL";

    @Builder.Default
    @Column(name = "local_login_enabled", nullable = false)
    private Boolean localLoginEnabled = true;

    @Builder.Default
    @Column(name = "sso_login_enabled", nullable = false)
    private Boolean ssoLoginEnabled = false;

    @Column(name = "sso_provider_key", length = 100)
    private String ssoProviderKey;

    @Builder.Default
    @Column(name = "require_mfa", nullable = false)
    private Boolean requireMfa = false;

    @Column(name = "token_ttl_sec")
    private Integer tokenTtlSec;
}
