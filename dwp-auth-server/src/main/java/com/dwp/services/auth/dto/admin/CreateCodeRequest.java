package com.dwp.services.auth.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 코드 생성 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCodeRequest {
    
    @NotBlank(message = "그룹 키는 필수입니다")
    private String groupKey;
    
    @NotBlank(message = "코드 키는 필수입니다")
    private String codeKey; // code
    
    @NotBlank(message = "코드명은 필수입니다")
    private String codeName; // name
    
    private String description;
    
    @Builder.Default
    private Integer sortOrder = 0;
    
    @Builder.Default
    private Boolean enabled = true; // isActive
    
    private String ext1;
    private String ext2;
    private String ext3;
}
