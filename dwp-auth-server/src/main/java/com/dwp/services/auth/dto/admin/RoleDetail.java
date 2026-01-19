package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 역할 상세 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleDetail {
    
    private Long comRoleId; // role_id
    private String roleCode;
    private String roleName;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
