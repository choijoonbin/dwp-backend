package com.dwp.services.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 인증 정책 응답 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthPolicyResponse {
    
    private Long tenantId;
    private String defaultLoginType; // LOCAL, SSO
    private List<String> allowedLoginTypes; // ["LOCAL"], ["LOCAL", "SSO"], ["SSO"]
    private Boolean localLoginEnabled;
    private Boolean ssoLoginEnabled;
    private String ssoProviderKey; // null 또는 "AZURE_AD", "OKTA" 등
    private Boolean requireMfa;
}
