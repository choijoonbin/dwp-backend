package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 목록 항목 DTO (UserListItem)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSummary {
    
    private Long comUserId; // user_id
    private Long tenantId;
    private Long departmentId;
    private String departmentName; // 부서명
    private String userName; // displayName
    private String loginId; // principal (UserAccount.principal)
    private String email;
    private String status;
    private LocalDateTime lastLoginAt; // 마지막 로그인 시간 (sys_login_histories 기반)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
