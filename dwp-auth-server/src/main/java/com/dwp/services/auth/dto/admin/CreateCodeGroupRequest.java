package com.dwp.services.auth.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 코드 그룹 생성 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCodeGroupRequest {
    
    @NotBlank(message = "그룹 키는 필수입니다")
    private String groupKey;
    
    @NotBlank(message = "그룹명은 필수입니다")
    private String groupName;
    
    private String description;
    
    @Builder.Default
    private Boolean isActive = true;
}
