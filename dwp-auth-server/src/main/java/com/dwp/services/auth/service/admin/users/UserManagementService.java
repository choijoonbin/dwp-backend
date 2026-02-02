package com.dwp.services.auth.service.admin.users;

import com.dwp.services.auth.dto.admin.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 사용자 관리 서비스 (Facade)
 * 
 * 기존 API 호환성을 유지하기 위한 Facade 패턴 적용
 * 실제 로직은 UserQueryService, UserCommandService, UserRoleService, UserPasswordService로 위임
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class UserManagementService {
    
    private final UserQueryService userQueryService;
    private final UserCommandService userCommandService;
    private final UserRoleService userRoleService;
    private final UserPasswordService userPasswordService;
    
    /**
     * 사용자 목록 조회
     * @param appCode 앱 코드(예: SYNAPSEX) 시 해당 앱 역할 사용자만 조회. 미전달 시 플랫폼 전체.
     * @param roleIds 역할 ID 목록(복수) 시 해당 역할 중 하나라도 가진 사용자만 조회. appCode 우선.
     */
    public PageResponse<UserSummary> getUsers(Long tenantId, int page, int size,
                                               String keyword, Long departmentId, Long roleId,
                                               List<Long> roleIds, String appCode,
                                               String status, String idpProviderType, String loginType) {
        return userQueryService.getUsers(tenantId, page, size, keyword, departmentId, roleId, roleIds, appCode,
                status, idpProviderType, loginType);
    }
    
    /**
     * 사용자 생성
     */
    public UserDetail createUser(Long tenantId, Long actorUserId, CreateUserRequest request,
                                  HttpServletRequest httpRequest) {
        return userCommandService.createUser(tenantId, actorUserId, request, httpRequest);
    }
    
    /**
     * 사용자 상세 조회
     */
    public UserDetail getUserDetail(Long tenantId, Long userId) {
        return userQueryService.getUserDetail(tenantId, userId);
    }
    
    /**
     * 사용자 수정
     */
    public UserDetail updateUser(Long tenantId, Long actorUserId, Long userId, UpdateUserRequest request,
                                 HttpServletRequest httpRequest) {
        return userCommandService.updateUser(tenantId, actorUserId, userId, request, httpRequest);
    }
    
    /**
     * 사용자 상태 변경
     */
    public UserDetail updateUserStatus(Long tenantId, Long actorUserId, Long userId,
                                       UpdateUserStatusRequest request, HttpServletRequest httpRequest) {
        return userCommandService.updateUserStatus(tenantId, actorUserId, userId, request, httpRequest);
    }
    
    /**
     * 사용자 삭제 (soft delete)
     */
    public void deleteUser(Long tenantId, Long actorUserId, Long userId, HttpServletRequest httpRequest) {
        userCommandService.deleteUser(tenantId, actorUserId, userId, httpRequest);
    }
    
    /**
     * 비밀번호 재설정
     */
    public ResetPasswordResponse resetPassword(Long tenantId, Long actorUserId, Long userId,
                                               ResetPasswordRequest request, HttpServletRequest httpRequest) {
        return userPasswordService.resetPassword(tenantId, actorUserId, userId, request, httpRequest);
    }
    
    /**
     * 사용자 역할 조회
     */
    public List<UserRoleInfo> getUserRoles(Long tenantId, Long userId) {
        return userQueryService.getUserRoles(tenantId, userId);
    }
    
    /**
     * 사용자 역할 업데이트
     */
    public void updateUserRoles(Long tenantId, Long actorUserId, Long userId,
                                UpdateUserRolesRequest request, HttpServletRequest httpRequest) {
        userRoleService.updateUserRoles(tenantId, actorUserId, userId, request, httpRequest);
    }
    
    /**
     * 사용자 역할 추가
     */
    public UserRoleInfo addUserRole(Long tenantId, Long actorUserId, Long userId, Long roleId,
                                    HttpServletRequest httpRequest) {
        return userRoleService.addUserRole(tenantId, actorUserId, userId, roleId, httpRequest);
    }
    
    /**
     * 사용자 역할 삭제
     */
    public void removeUserRole(Long tenantId, Long actorUserId, Long userId, Long roleId,
                               HttpServletRequest httpRequest) {
        userRoleService.removeUserRole(tenantId, actorUserId, userId, roleId, httpRequest);
    }
}
