package com.dwp.services.auth.service.sso;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PR-10C: SAML 사용자 정보 DTO (Skeleton)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SamlUserInfo {
    private String nameId; // SAML NameID
    private String email;
    private String name;
}
