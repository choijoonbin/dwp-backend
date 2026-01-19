package com.dwp.services.auth.dto.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 사용자 생성 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserRequest {
    
    private Long departmentId;
    
    @NotBlank(message = "사용자명은 필수입니다")
    private String userName; // displayName
    
    @Email(message = "올바른 이메일 형식이 아닙니다")
    private String email;
    
    @Builder.Default
    private String status = "ACTIVE";
    
    // LOCAL 계정 생성 옵션
    @Valid
    private LocalAccountRequest localAccount;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LocalAccountRequest {
        @NotBlank(message = "principal은 필수입니다")
        private String principal; // username
        
        @NotBlank(message = "password는 필수입니다")
        private String password; // BCrypt로 해시
    }
}
