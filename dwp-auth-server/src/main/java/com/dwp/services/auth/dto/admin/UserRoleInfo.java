package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 역할 정보 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRoleInfo {
    
    private Long comRoleId; // role_id
    private String roleCode;
    private String roleName;
    private LocalDateTime assignedAt; // role_member.created_at
}
