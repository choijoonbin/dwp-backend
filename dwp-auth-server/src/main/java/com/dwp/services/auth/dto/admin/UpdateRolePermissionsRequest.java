package com.dwp.services.auth.dto.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 역할 권한 업데이트 요청 DTO
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
        @NotNull(message = "resourceId는 필수입니다")
        private Long resourceId;
        
        @NotNull(message = "permissionId는 필수입니다")
        private Long permissionId;
        
        @Builder.Default
        private String effect = "ALLOW"; // ALLOW, DENY
    }
}
