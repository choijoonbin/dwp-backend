package com.dwp.services.auth.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 부서 생성 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateDepartmentRequest {
    
    @NotBlank(message = "부서 코드는 필수입니다")
    private String code;
    
    @NotBlank(message = "부서명은 필수입니다")
    private String name;
    
    private Long parentDepartmentId;
    
    @Builder.Default
    private String status = "ACTIVE";
}
