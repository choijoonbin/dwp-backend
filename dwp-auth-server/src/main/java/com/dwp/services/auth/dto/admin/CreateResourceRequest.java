package com.dwp.services.auth.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 리소스 생성 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateResourceRequest {
    
    @NotBlank(message = "리소스 타입은 필수입니다")
    private String resourceType; // RESOURCE_TYPE 코드 검증
    
    @NotBlank(message = "리소스 키는 필수입니다")
    private String resourceKey;
    
    @NotBlank(message = "리소스명은 필수입니다")
    private String resourceName;
    
    private String parentResourceKey; // parentResourceId 대신 key 사용
    private String path;
    private Integer sortOrder;
    private Map<String, Object> metadata;
    
    @Builder.Default
    private Boolean enabled = true;
}
