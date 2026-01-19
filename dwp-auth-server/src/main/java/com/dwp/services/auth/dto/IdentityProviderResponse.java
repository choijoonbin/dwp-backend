package com.dwp.services.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Identity Provider 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdentityProviderResponse {
    
    private Long tenantId;
    private Boolean enabled;
    private String providerType; // OIDC, SAML
    private String providerKey; // AZURE_AD, OKTA 등
    private String authUrl;
    private String metadataUrl;
    private String clientId;
    private Map<String, Object> ext; // ext1, ext2, ext3을 맵으로 변환
}
