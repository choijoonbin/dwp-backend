package com.dwp.services.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 응답 DTO
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    
    /**
     * JWT 액세스 토큰
     */
    private String accessToken;
    
    /**
     * 토큰 타입 (항상 "Bearer")
     */
    @Builder.Default
    private String tokenType = "Bearer";
    
    /**
     * 토큰 만료 시간 (초 단위)
     */
    private Long expiresIn;
    
    /**
     * 사용자 ID (JWT의 sub 클레임과 동일)
     */
    private String userId;
    
    /**
     * 테넌트 ID (JWT의 tenant_id 클레임과 동일)
     */
    private String tenantId;
}
