package com.dwp.services.auth.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * PR-04C: 리소스 생성 요청 DTO (운영 수준)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateResourceRequest {
    
    @NotBlank(message = "리소스 키는 필수입니다")
    private String resourceKey; // 예: menu.admin.monitoring / btn.mail.send
    
    @NotBlank(message = "리소스명은 필수입니다")
    private String resourceName;
    
    @NotBlank(message = "리소스 카테고리는 필수입니다")
    private String resourceCategory; // MENU / UI_COMPONENT (코드 기반 검증)
    
    @NotBlank(message = "리소스 종류는 필수입니다")
    private String resourceKind; // MENU_GROUP / PAGE / BUTTON / TAB / SELECT 등 (코드 기반 검증)
    
    private Long parentResourceId; // nullable
    
    private Map<String, Object> meta; // JSON metadata
    
    @Builder.Default
    private Boolean trackingEnabled = true;
    
    private List<String> eventActions; // UI_ACTION 코드 기반 검증 (예: ["VIEW","CLICK","SUBMIT"])
    
    @Builder.Default
    private Boolean enabled = true;
    
    // 하위 호환성: resourceType (deprecated, resourceCategory로 대체)
    private String resourceType;
    
    // 하위 호환성: parentResourceKey (deprecated, parentResourceId로 대체)
    private String parentResourceKey;
    
    // 하위 호환성: path, sortOrder (meta에 포함)
    private String path;
    private Integer sortOrder;
    private Map<String, Object> metadata;
}
