package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 역할 권한 뷰 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RolePermissionView {
    
    private Long comRoleId; // role_id
    private Long comResourceId; // resource_id
    private String resourceKey;
    private String resourceName;
    private String resourceType; // PR-03C: 매트릭스 구성용 (MENU, UI_COMPONENT, PAGE_SECTION, API)
    private Long comPermissionId; // permission_id
    private String permissionCode;
    private String permissionName;
    private Integer permissionSortOrder; // 매트릭스 컬럼 정렬 순서
    private String permissionDescription; // 권한 설명 (툴팁용)
    private String effect; // ALLOW, DENY
    private Integer resourceSortOrder; // 리소스 트리 정렬 순서
}
