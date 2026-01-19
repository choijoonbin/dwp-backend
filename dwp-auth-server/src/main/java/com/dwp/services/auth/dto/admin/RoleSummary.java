package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 역할 요약 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleSummary {
    
    private Long comRoleId; // role_id
    private String roleCode;
    private String roleName;
    private String description;
    private LocalDateTime createdAt;
}
