package com.dwp.services.auth.service.admin.users;

import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RoleMember;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.entity.UserAccount;
import com.dwp.services.auth.util.CodeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 사용자 Entity ↔ DTO 변환 컴포넌트
 * 
 * Entity와 DTO 간 변환만 담당합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserMapper {
    
    private final CodeResolver codeResolver;
    
    /**
     * User → UserSummary 변환
     * 
     * @param tenantId 테넌트 ID
     * @param user 사용자 엔티티
     * @param loginId 로그인 ID (principal)
     * @param departmentName 부서명
     * @return UserSummary DTO
     */
    public UserSummary toUserSummary(Long tenantId, User user, String loginId, String departmentName) {
        if (user == null) {
            log.warn("toUserSummary: user is null");
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.INTERNAL_SERVER_ERROR, "사용자 정보가 null입니다.");
        }
        if (user.getUserId() == null) {
            log.warn("toUserSummary: user.getUserId() is null");
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.INTERNAL_SERVER_ERROR, "사용자 ID가 null입니다.");
        }
        
        return UserSummary.builder()
                .comUserId(user.getUserId())
                .tenantId(tenantId)
                .departmentId(user.getPrimaryDepartmentId())
                .departmentName(departmentName)
                .userName(user.getDisplayName())
                .loginId(loginId)
                .email(user.getEmail())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
    
    /**
     * User → UserDetail 변환
     * 
     * @param user 사용자 엔티티
     * @param accounts 계정 목록
     * @param roles 역할 목록
     * @return UserDetail DTO
     */
    public UserDetail toUserDetail(User user, List<UserAccountInfo> accounts, List<UserRoleInfo> roles) {
        return UserDetail.builder()
                .comUserId(user.getUserId())
                .tenantId(user.getTenantId())
                .departmentId(user.getPrimaryDepartmentId())
                .userName(user.getDisplayName())
                .email(user.getEmail())
                .status(user.getStatus())
                .accounts(accounts)
                .roles(roles)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
    
    /**
     * UserAccount → UserAccountInfo 변환
     * 
     * @param account 계정 엔티티
     * @param lastLoginAt 마지막 로그인 시간
     * @return UserAccountInfo DTO
     */
    public UserAccountInfo toUserAccountInfo(UserAccount account, java.time.LocalDateTime lastLoginAt) {
        // 계정 상태 비교 (하드코딩 제거: CodeResolver로 ACTIVE 상태 확인)
        String activeStatus = "ACTIVE";
        boolean isEnabled = account.getStatus() != null 
                && codeResolver.validate("USER_STATUS", account.getStatus())
                && codeResolver.validate("USER_STATUS", activeStatus)
                && activeStatus.equals(account.getStatus());
        
        return UserAccountInfo.builder()
                .comUserAccountId(account.getUserAccountId())
                .providerType(account.getProviderType())
                .principal(account.getPrincipal())
                .enabled(isEnabled)
                .lastLoginAt(lastLoginAt)
                .build();
    }
    
    /**
     * RoleMember + Role → UserRoleInfo 변환
     * 
     * @param roleMember 역할 멤버 엔티티
     * @param role 역할 엔티티
     * @return UserRoleInfo DTO
     */
    public UserRoleInfo toUserRoleInfo(RoleMember roleMember, Role role) {
        if (role == null) {
            return null;
        }
        
        return UserRoleInfo.builder()
                .comRoleId(role.getRoleId())
                .roleCode(role.getCode())
                .roleName(role.getName())
                .assignedAt(roleMember.getCreatedAt())
                .build();
    }
    
    /**
     * User 복사 (감사 로그용)
     * 
     * @param user 사용자 엔티티
     * @return 복사된 User 엔티티
     */
    public User copyUser(User user) {
        return User.builder()
                .userId(user.getUserId())
                .tenantId(user.getTenantId())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .primaryDepartmentId(user.getPrimaryDepartmentId())
                .status(user.getStatus())
                .build();
    }
}
