package com.dwp.services.auth.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class AuthPolicyResponse {

    private Long tenantId;
    private String defaultLoginType;
    private List<String> allowedLoginTypes;
    private Boolean localLoginEnabled;
    private Boolean ssoLoginEnabled;
    private String ssoProviderKey;
    private Boolean requireMfa;
}
