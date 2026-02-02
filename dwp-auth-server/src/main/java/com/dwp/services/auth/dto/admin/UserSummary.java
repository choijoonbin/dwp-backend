package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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
    /** 로그인 계정 유형 (com_user_accounts.provider_type, 예: LOCAL, OIDC) */
    private String providerType;
    private String email;
    private String status;
    private Boolean mfaEnabled; // MFA(2단계 인증) 사용 여부
    private LocalDateTime lastLoginAt; // 마지막 로그인 시간 (sys_login_histories 기반)
    /** 역할 목록 (Users 탭 Role 컬럼 표시용) */
    private List<UserRoleInfo> roles;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
