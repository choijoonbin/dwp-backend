package com.dwp.services.auth.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class IdentityProviderResponse {

    private Boolean enabled;
    private String providerType;
    private String providerKey;
}
