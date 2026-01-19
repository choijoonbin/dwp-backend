package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 리소스 수정 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateResourceRequest {
    
    private String resourceType;
    private String resourceKey;
    private String resourceName;
    private String parentResourceKey;
    private String path;
    private Integer sortOrder;
    private Map<String, Object> metadata;
    private Boolean enabled;
}
