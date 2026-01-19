package com.dwp.services.auth.service.admin;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RoleMember;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.entity.UserAccount;
import com.dwp.services.auth.repository.DepartmentRepository;
import com.dwp.services.auth.repository.LoginHistoryRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserAccountRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.util.CodeResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 사용자 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class UserManagementService {
    
    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;
    private final DepartmentRepository departmentRepository;
    private final RoleRepository roleRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final PasswordEncoder passwordEncoder;
    private final CodeResolver codeResolver;
    private final AuditLogService auditLogService;
    
    /**
     * 사용자 목록 조회
     */
    @Transactional(readOnly = true)
    public PageResponse<UserSummary> getUsers(Long tenantId, int page, int size, 
                                               String keyword, Long departmentId, Long roleId, String status) {
        Pageable pageable = PageRequest.of(page - 1, size); // 1-base to 0-base
        
        Page<User> userPage = userRepository.findByTenantIdAndFilters(
                tenantId, keyword, departmentId, status, pageable);
        
        // roleId 필터링이 있는 경우 추가 필터링
        if (roleId != null) {
            List<User> usersWithRole = userRepository.findByTenantIdAndRoleId(tenantId, roleId);
            List<Long> userIds = usersWithRole.stream()
                    .map(User::getUserId)
                    .collect(Collectors.toList());
            userPage = userPage.getContent().stream()
                    .filter(u -> userIds.contains(u.getUserId()))
                    .collect(Collectors.collectingAndThen(
                            Collectors.toList(),
                            list -> new org.springframework.data.domain.PageImpl<>(
                                    list, pageable, list.size())));
        }
        
        List<UserSummary> summaries = userPage.getContent().stream()
                .map(user -> toUserSummary(tenantId, user))
                .collect(Collectors.toList());
        
        return PageResponse.<UserSummary>builder()
                .items(summaries)
                .page(page)
                .size(size)
                .totalItems(userPage.getTotalElements())
                .totalPages(userPage.getTotalPages())
                .build();
    }
    
    /**
     * 사용자 생성
     */
    @Transactional
    public UserDetail createUser(Long tenantId, Long actorUserId, CreateUserRequest request,
                                  HttpServletRequest httpRequest) {
        // 이메일 중복 체크
        if (request.getEmail() != null && !request.getEmail().isEmpty()) {
            userRepository.findByTenantIdAndEmail(tenantId, request.getEmail())
                    .ifPresent(u -> {
                        throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 이메일입니다.");
                    });
        }
        
        // 부서 존재 확인
        if (request.getDepartmentId() != null) {
            departmentRepository.findByTenantIdAndDepartmentId(tenantId, request.getDepartmentId())
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부서를 찾을 수 없습니다."));
        }
        
        // 사용자 생성
        User user = User.builder()
                .tenantId(tenantId)
                .displayName(request.getUserName())
                .email(request.getEmail())
                .primaryDepartmentId(request.getDepartmentId())
                .status(request.getStatus() != null ? request.getStatus() : "ACTIVE")
                .build();
        user = userRepository.save(user);
        
        // LOCAL 계정 생성 (옵션)
        if (request.getLocalAccount() != null) {
            String providerType = "LOCAL";
            codeResolver.require("IDP_PROVIDER_TYPE", providerType);
            
            // principal 중복 체크
            userAccountRepository.findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(
                    tenantId, providerType, "local", request.getLocalAccount().getPrincipal())
                    .ifPresent(acc -> {
                        throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 principal입니다.");
                    });
            
            String password = request.getLocalAccount().getPassword();
            String passwordHash = passwordEncoder.encode(password);
            
            UserAccount account = UserAccount.builder()
                    .tenantId(tenantId)
                    .userId(user.getUserId())
                    .providerType(providerType)
                    .providerId("local")
                    .principal(request.getLocalAccount().getPrincipal())
                    .passwordHash(passwordHash)
                    .status("ACTIVE")
                    .build();
            userAccountRepository.save(account);
        }
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "USER_CREATE", "USER", user.getUserId(),
                null, user, httpRequest);
        
        return getUserDetail(tenantId, user.getUserId());
    }
    
    /**
     * 사용자 상세 조회
     */
    @Transactional(readOnly = true)
    public UserDetail getUserDetail(Long tenantId, Long userId) {
        User user = userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        // 계정 목록 조회 (lastLoginAt 포함)
        List<UserAccountInfo> accounts = userAccountRepository.findByUserId(user.getUserId()).stream()
                .map(acc -> {
                    // 마지막 로그인 시간 조회
                    java.time.LocalDateTime lastLoginAt = loginHistoryRepository
                            .findLastLoginAtByAccount(tenantId, acc.getProviderType(), acc.getProviderId(), acc.getPrincipal())
                            .orElse(null);
                    
                    return UserAccountInfo.builder()
                            .comUserAccountId(acc.getUserAccountId())
                            .providerType(acc.getProviderType())
                            .principal(acc.getPrincipal())
                            .enabled("ACTIVE".equals(acc.getStatus()))
                            .lastLoginAt(lastLoginAt)
                            .build();
                })
                .collect(Collectors.toList());
        
        // 역할 목록 조회 (assignedAt 포함)
        List<RoleMember> roleMembers = roleMemberRepository.findByTenantIdAndUserId(tenantId, userId);
        
        List<UserRoleInfo> roles = roleMembers.stream()
                .map(rm -> {
                    Role role = roleRepository.findById(rm.getRoleId())
                            .orElse(null);
                    if (role == null) return null;
                    
                    return UserRoleInfo.builder()
                            .comRoleId(role.getRoleId())
                            .roleCode(role.getCode())
                            .roleName(role.getName())
                            .assignedAt(rm.getCreatedAt())
                            .build();
                })
                .filter(r -> r != null)
                .collect(Collectors.toList());
        
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
     * 사용자 수정
     */
    @Transactional
    public UserDetail updateUser(Long tenantId, Long actorUserId, Long userId, UpdateUserRequest request,
                                 HttpServletRequest httpRequest) {
        User user = userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        User before = copyUser(user);
        
        // 수정
        if (request.getUserName() != null) {
            user.setDisplayName(request.getUserName());
        }
        if (request.getEmail() != null) {
            // 이메일 중복 체크 (본인 제외)
            userRepository.findByTenantIdAndEmail(tenantId, request.getEmail())
                    .filter(u -> !u.getUserId().equals(userId))
                    .ifPresent(u -> {
                        throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 이메일입니다.");
                    });
            user.setEmail(request.getEmail());
        }
        if (request.getDepartmentId() != null) {
            departmentRepository.findByTenantIdAndDepartmentId(tenantId, request.getDepartmentId())
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부서를 찾을 수 없습니다."));
            user.setPrimaryDepartmentId(request.getDepartmentId());
        }
        if (request.getStatus() != null) {
            user.setStatus(request.getStatus());
        }
        
        userRepository.save(user);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "USER_UPDATE", "USER", userId,
                before, user, httpRequest);
        
        return getUserDetail(tenantId, userId);
    }
    
    /**
     * 사용자 상태 변경
     */
    @Transactional
    public UserDetail updateUserStatus(Long tenantId, Long actorUserId, Long userId,
                                       UpdateUserStatusRequest request, HttpServletRequest httpRequest) {
        User user = userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        User before = copyUser(user);
        user.setStatus(request.getStatus());
        userRepository.save(user);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "USER_STATUS_UPDATE", "USER", userId,
                before, user, httpRequest);
        
        return getUserDetail(tenantId, userId);
    }
    
    /**
     * 사용자 삭제 (soft delete)
     */
    @Transactional
    public void deleteUser(Long tenantId, Long actorUserId, Long userId, HttpServletRequest httpRequest) {
        User user = userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        User before = copyUser(user);
        
        // Soft delete (status = INACTIVE)
        user.setStatus("INACTIVE");
        userRepository.save(user);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "USER_DELETE", "USER", userId,
                before, user, httpRequest);
    }
    
    /**
     * 비밀번호 재설정
     */
    @Transactional
    public ResetPasswordResponse resetPassword(Long tenantId, Long actorUserId, Long userId,
                                               ResetPasswordRequest request, HttpServletRequest httpRequest) {
        User user = userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        // LOCAL 계정 찾기
        String providerType = "LOCAL";
        codeResolver.require("IDP_PROVIDER_TYPE", providerType);
        
        UserAccount account = userAccountRepository.findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(
                        tenantId, providerType, "local", user.getEmail())
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "LOCAL 계정을 찾을 수 없습니다."));
        
        String newPassword = request.getNewPassword() != null ? request.getNewPassword() : generateTempPassword();
        String passwordHash = passwordEncoder.encode(newPassword);
        
        account.setPasswordHash(passwordHash);
        account.setStatus("ACTIVE");
        userAccountRepository.save(account);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "USER_RESET_PASSWORD", "USER", userId,
                null, null, httpRequest);
        
        ResetPasswordResponse response = ResetPasswordResponse.builder().build();
        if (request.getNewPassword() == null) {
            response.setTempPassword(newPassword);
        }
        return response;
    }
    
    /**
     * 사용자 역할 조회
     */
    @Transactional(readOnly = true)
    public List<UserRoleInfo> getUserRoles(Long tenantId, Long userId) {
        List<RoleMember> roleMembers = roleMemberRepository.findByTenantIdAndUserId(tenantId, userId);
        
        return roleMembers.stream()
                .map(rm -> {
                    Role role = roleRepository.findById(rm.getRoleId()).orElse(null);
                    if (role == null) return null;
                    
                    return UserRoleInfo.builder()
                            .comRoleId(role.getRoleId())
                            .roleCode(role.getCode())
                            .roleName(role.getName())
                            .assignedAt(rm.getCreatedAt())
                            .build();
                })
                .filter(r -> r != null)
                .collect(Collectors.toList());
    }
    
    /**
     * 사용자 역할 업데이트
     */
    @Transactional
    public void updateUserRoles(Long tenantId, Long actorUserId, Long userId,
                                UpdateUserRolesRequest request, HttpServletRequest httpRequest) {
        // 사용자 존재 확인
        userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        // 기존 역할 멤버 삭제
        roleMemberRepository.deleteByTenantIdAndUserId(tenantId, userId);
        
        // 새 역할 멤버 추가
        String subjectType = "USER";
        codeResolver.require("SUBJECT_TYPE", subjectType);
        
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            // 역할 존재 확인
            List<Role> roles = roleRepository.findByRoleIdIn(request.getRoleIds());
            if (roles.size() != request.getRoleIds().size()) {
                throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "존재하지 않는 역할이 포함되어 있습니다.");
            }
            
            for (Long roleId : request.getRoleIds()) {
                RoleMember member = RoleMember.builder()
                        .tenantId(tenantId)
                        .roleId(roleId)
                        .subjectType(subjectType)
                        .subjectId(userId)
                        .build();
                roleMemberRepository.save(member);
            }
        }
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "USER_ROLE_UPDATE", "USER", userId,
                null, request, httpRequest);
    }
    
    private UserSummary toUserSummary(Long tenantId, User user) {
        return UserSummary.builder()
                .comUserId(user.getUserId())
                .tenantId(tenantId)
                .departmentId(user.getPrimaryDepartmentId())
                .userName(user.getDisplayName())
                .email(user.getEmail())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
    
    private User copyUser(User user) {
        return User.builder()
                .userId(user.getUserId())
                .tenantId(user.getTenantId())
                .displayName(user.getDisplayName())
                .email(user.getEmail())
                .primaryDepartmentId(user.getPrimaryDepartmentId())
                .status(user.getStatus())
                .build();
    }
    
    private String generateTempPassword() {
        return UUID.randomUUID().toString().substring(0, 12);
    }
}
