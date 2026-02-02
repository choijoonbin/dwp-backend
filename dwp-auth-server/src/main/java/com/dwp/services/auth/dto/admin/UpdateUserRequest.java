package com.dwp.services.auth.dto.admin;

import jakarta.validation.constraints.Email;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 사용자 수정 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRequest {
    
    private String userName; // displayName
    
    @Email(message = "올바른 이메일 형식이 아닙니다")
    private String email;
    
    private Long departmentId;
    private String status;
    /** MFA(2단계 인증) 사용 여부. true 시 로그인 시 2FA 검증 대상 */
    private Boolean mfaEnabled;
}
