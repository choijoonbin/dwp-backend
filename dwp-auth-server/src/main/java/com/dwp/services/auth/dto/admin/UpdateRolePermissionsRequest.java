package com.dwp.services.auth.dto.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 역할 권한 업데이트 요청 DTO (BE P1-5 Final: resourceKey/permissionCode 기반)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRolePermissionsRequest {
    
    @Valid
    private List<RolePermissionItem> items;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RolePermissionItem {
        @NotNull(message = "resourceKey는 필수입니다")
        private String resourceKey;  // 예: menu.admin.users
        
        @NotNull(message = "permissionCode는 필수입니다")
        private String permissionCode;  // 예: VIEW, USE, EDIT, EXECUTE
        
        // effect=null이면 삭제, "ALLOW" 또는 "DENY"이면 upsert
        private String effect;  // ALLOW, DENY, null (삭제)
    }
}
