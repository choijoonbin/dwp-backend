package com.dwp.services.auth.service.sso;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PR-10B: OIDC 사용자 정보 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OidcUserInfo {
    private String sub; // Subject (사용자 고유 ID)
    private String email;
    private String name;
}
