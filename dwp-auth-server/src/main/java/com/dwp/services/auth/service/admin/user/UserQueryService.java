package com.dwp.services.auth.service.admin.user;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.entity.Department;
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
import com.dwp.services.auth.util.CodeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 사용자 조회 서비스 (CQRS: Query 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@SuppressWarnings("null")
public class UserQueryService {
    
    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;
    private final DepartmentRepository departmentRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final RoleRepository roleRepository;
    private final LoginHistoryRepository loginHistoryRepository;
    private final UserMapper userMapper;
    private final CodeResolver codeResolver;
    
    /**
     * 사용자 목록 조회 (보강: idpProviderType, loginType 필터 추가)
     */
    public PageResponse<UserSummary> getUsers(Long tenantId, int page, int size, 
                                               String keyword, Long departmentId, Long roleId, 
                                               String status, String idpProviderType, String loginType) {
        try {
            Pageable pageable = PageRequest.of(page - 1, size); // 1-base to 0-base
            
            // loginType 필터 처리 (providerType으로 변환)
            String providerTypeFilter = loginType != null ? loginType : idpProviderType;
            
            Page<User> userPage = userRepository.findByTenantIdAndFilters(
                    tenantId, keyword, departmentId, status, providerTypeFilter, pageable);
            
            // roleId 필터링이 있는 경우 추가 필터링
            if (roleId != null) {
                List<User> usersWithRole = userRepository.findByTenantIdAndRoleId(tenantId, roleId);
                List<Long> userIds = usersWithRole.stream()
                        .map(User::getUserId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList());
                userPage = userPage.getContent().stream()
                        .filter(u -> u.getUserId() != null && userIds.contains(u.getUserId()))
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
        } catch (Exception e) {
            log.error("사용자 목록 조회 실패: tenantId={}, page={}, size={}, error={}", 
                    tenantId, page, size, e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "사용자 목록 조회 중 오류가 발생했습니다: " + e.getMessage());
        }
    }
    
    /**
     * 사용자 상세 조회
     */
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
                    
                    return userMapper.toUserAccountInfo(acc, lastLoginAt);
                })
                .collect(Collectors.toList());
        
        // 역할 목록 조회 (assignedAt 포함, 부서 기반 역할 포함)
        List<UserRoleInfo> roles = getUserRoles(tenantId, userId);
        
        return userMapper.toUserDetail(user, accounts, roles);
    }
    
    /**
     * 사용자 역할 조회 (부서 기반 역할 포함)
     */
    public List<UserRoleInfo> getUserRoles(Long tenantId, Long userId) {
        User user = userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        // 사용자 직접 할당 역할
        List<RoleMember> userRoleMembers = roleMemberRepository.findByTenantIdAndUserId(tenantId, userId);
        
        // 부서 기반 역할 조회
        List<RoleMember> deptRoleMembers = new ArrayList<>();
        if (user.getPrimaryDepartmentId() != null) {
            String deptSubjectType = "DEPARTMENT";
            codeResolver.require("SUBJECT_TYPE", deptSubjectType);
            
            List<Long> deptRoleIds = roleMemberRepository.findRoleIdsByTenantIdAndDepartmentId(
                    tenantId, user.getPrimaryDepartmentId());
            deptRoleIds.forEach(roleId -> {
                RoleMember deptMember = RoleMember.builder()
                        .roleId(roleId)
                        .subjectType(deptSubjectType)
                        .subjectId(user.getPrimaryDepartmentId())
                        .build();
                deptRoleMembers.add(deptMember);
            });
        }
        
        // 모든 역할 합치기 (중복 제거)
        Set<Long> seenRoleIds = new HashSet<>();
        List<UserRoleInfo> roles = new ArrayList<>();
        
        // 사용자 직접 할당 역할
        userRoleMembers.forEach(rm -> {
            if (!seenRoleIds.contains(rm.getRoleId())) {
                Role role = roleRepository.findById(rm.getRoleId()).orElse(null);
                if (role != null) {
                    roles.add(UserRoleInfo.builder()
                            .comRoleId(role.getRoleId())
                            .roleCode(role.getCode())
                            .roleName(role.getName())
                            .subjectType(rm.getSubjectType())
                            .isDepartmentBased(false)
                            .assignedAt(rm.getCreatedAt())
                            .build());
                    seenRoleIds.add(rm.getRoleId());
                }
            }
        });
        
        // 부서 기반 역할
        deptRoleMembers.forEach(rm -> {
            if (!seenRoleIds.contains(rm.getRoleId())) {
                Role role = roleRepository.findById(rm.getRoleId()).orElse(null);
                if (role != null) {
                    roles.add(UserRoleInfo.builder()
                            .comRoleId(role.getRoleId())
                            .roleCode(role.getCode())
                            .roleName(role.getName())
                            .subjectType(rm.getSubjectType())
                            .isDepartmentBased(true)
                            .assignedAt(null) // 부서 기반은 assignedAt 없음
                            .build());
                    seenRoleIds.add(rm.getRoleId());
                }
            }
        });
        
        return roles;
    }
    
    /**
     * User → UserSummary 변환 (lastLoginAt 포함)
     */
    private UserSummary toUserSummary(Long tenantId, User user) {
        if (user == null) {
            log.warn("toUserSummary: user is null");
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "사용자 정보가 null입니다.");
        }
        if (user.getUserId() == null) {
            log.warn("toUserSummary: user.getUserId() is null");
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "사용자 ID가 null입니다.");
        }
        
        // loginId 조회 (LOCAL 계정 우선, 없으면 첫 번째 계정)
        String loginId = null;
        java.time.LocalDateTime lastLoginAt = null;
        List<UserAccount> accounts = userAccountRepository.findByUserId(user.getUserId());
        if (!accounts.isEmpty()) {
            // LOCAL 계정 우선 선택
            UserAccount localAccount = accounts.stream()
                    .filter(acc -> "LOCAL".equals(acc.getProviderType()))
                    .findFirst()
                    .orElse(accounts.get(0)); // LOCAL이 없으면 첫 번째 계정
            loginId = localAccount.getPrincipal();
            
            // 마지막 로그인 시간 조회 (서브쿼리로 최신 1건만)
            lastLoginAt = loginHistoryRepository.findLastLoginAtByAccount(
                    tenantId, localAccount.getProviderType(), localAccount.getProviderId(), localAccount.getPrincipal())
                    .orElse(null);
        }
        
        // departmentName 조회
        String departmentName = null;
        if (user.getPrimaryDepartmentId() != null) {
            Optional<Department> deptOpt = departmentRepository.findByTenantIdAndDepartmentId(tenantId, user.getPrimaryDepartmentId());
            if (deptOpt.isPresent()) {
                departmentName = deptOpt.get().getName();
            }
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
                .lastLoginAt(lastLoginAt)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
