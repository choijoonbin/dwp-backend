package com.dwp.services.auth.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 코드 사용 정의 생성 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCodeUsageRequest {
    
    @NotBlank(message = "리소스 키는 필수입니다")
    private String resourceKey;
    
    @NotBlank(message = "코드 그룹 키는 필수입니다")
    private String codeGroupKey;
    
    @Builder.Default
    private String scope = "MENU"; // MENU, PAGE, MODULE
    
    @Builder.Default
    private Boolean enabled = true;
    
    private Integer sortOrder;
    private String remark;
}
