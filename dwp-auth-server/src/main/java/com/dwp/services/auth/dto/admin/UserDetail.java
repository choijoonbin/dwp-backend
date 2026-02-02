package com.dwp.services.auth.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 사용자 상세 응답 DTO (UserDetailResponse)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDetail {
    
    private Long comUserId; // user_id
    private Long tenantId;
    private Long departmentId;
    private String userName; // displayName
    private String email;
    private String status;
    private Boolean mfaEnabled; // MFA(2단계 인증) 사용 여부
    private List<UserAccountInfo> accounts;
    private List<UserRoleInfo> roles;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
