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
    private String subjectType; // USER, DEPARTMENT (부서 기반 역할 여부 표시)
    private Boolean isDepartmentBased; // 부서 기반 역할 여부 (DEPARTMENT면 true)
    private LocalDateTime assignedAt; // role_member.created_at
}
