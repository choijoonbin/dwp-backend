package com.dwp.services.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 로그인 요청 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {
    
    @NotBlank(message = "사용자명은 필수입니다.")
    private String username;
    
    @NotBlank(message = "비밀번호는 필수입니다.")
    private String password;
    
    /**
     * 테넌트 ID
     * 
     * 헤더(X-Tenant-ID) 또는 요청 본문에서 받을 수 있습니다.
     * 요청 본문에 포함된 경우 우선적으로 사용됩니다.
     */
    @NotBlank(message = "테넌트 ID는 필수입니다.")
    private String tenantId;
}
