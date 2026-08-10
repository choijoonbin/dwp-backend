package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "sys_identity_providers",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "provider_key"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdentityProvider extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "identity_provider_id")
    private Long identityProviderId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "provider_type", nullable = false, length = 20)
    private String providerType;

    @Column(name = "provider_key", nullable = false, length = 100)
    private String providerKey;

    @Column(name = "issuer_uri", length = 500)
    private String issuerUri;

    @Column(nullable = false, length = 200)
    private String name;

    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = false;

    @Column(name = "auth_url", length = 500)
    private String authUrl;

    @Column(name = "token_url", length = 500)
    private String tokenUrl;

    @Column(name = "user_info_url", length = 500)
    private String userInfoUrl;

    @Column(name = "metadata_url", length = 500)
    private String metadataUrl;

    @Column(name = "client_id", length = 255)
    private String clientId;

    @Column(name = "client_secret_env", length = 100)
    private String clientSecretEnv;
}
