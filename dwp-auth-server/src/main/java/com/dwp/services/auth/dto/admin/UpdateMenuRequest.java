package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PR-05B: 메뉴 수정 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateMenuRequest {
    
    private String menuName;
    private Long parentMenuId; // nullable (변경 시)
    private String parentMenuKey; // nullable (parentMenuId 대신 사용 가능)
    private String routePath;
    private String icon;
    private String menuGroup;
    private Integer sortOrder;
    private String remoteKey; // optional (metadata에 저장)
    private Boolean enabled; // is_enabled
    private Boolean visible; // is_visible
    private String description;
    
    // PR-05D: menuKey 변경은 운영 위험 → 금지 (별도 API 필요)
}
