package com.dwp.services.auth.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class IdentityProviderResponse {

    private Long tenantId;
    private Boolean enabled;
    private String providerType;
    private String providerKey;
    private String authUrl;
    private String metadataUrl;
    private String clientId;
}
