package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * PR-04D: 리소스 수정 요청 DTO (운영 수준)
 * - resourceKey 변경 금지
 * - name/meta/trackingEnabled/eventActions/enabled/parent 수정 가능
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateResourceRequest {
    
    // PR-04D: resourceKey 변경 금지 (운영 위험)
    private String resourceKey;
    
    private String resourceName;
    
    private String resourceCategory; // 코드 기반 검증
    private String resourceKind; // 코드 기반 검증
    
    private Long parentResourceId; // tenant 일치 검증
    
    private Map<String, Object> meta; // JSON metadata
    private List<String> eventActions; // UI_ACTION 코드 기반 검증
    
    private Boolean trackingEnabled;
    private Boolean enabled;
    
    // 하위 호환성
    private String resourceType;
    private String parentResourceKey;
    private String path;
    private Integer sortOrder;
    private Map<String, Object> metadata;
}
