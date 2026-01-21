package com.dwp.services.auth.dto.admin;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PR-05B: 메뉴 생성 요청 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateMenuRequest {
    
    @NotBlank(message = "메뉴 키는 필수입니다")
    private String menuKey; // 유니크
    
    @NotBlank(message = "메뉴명은 필수입니다")
    private String menuName;
    
    private Long parentMenuId; // nullable (parentMenuKey로도 가능)
    private String parentMenuKey; // nullable (parentMenuId 대신 사용 가능)
    
    private String routePath; // menu_path
    private String icon; // menu_icon
    private String menuGroup; // 예: MANAGEMENT, APPS
    private Integer sortOrder;
    private String remoteKey; // optional (metadata에 저장)
    
    @Builder.Default
    private Boolean enabled = true; // is_enabled
    @Builder.Default
    private Boolean visible = true; // is_visible
    
    private String description;
}
