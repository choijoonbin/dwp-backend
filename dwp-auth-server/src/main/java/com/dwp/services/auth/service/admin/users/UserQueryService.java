package com.dwp.services.auth.service.admin.users;

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
import com.dwp.services.auth.util.AppScopeResolver;
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
import java.util.Objects;
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
     * 사용자 목록 조회
     * - appCode 있음: 해당 앱 역할 사용자만 조회 (이름 검색도 앱 스코프 내에서만)
     * - roleIds 있음: 해당 역할 중 하나라도 가진 사용자만 조회
     * - roleId 있음: 해당 역할 사용자만 조회 (기존 단일 역할 필터)
     * - 모두 없음: 플랫폼 전체 사용자 (DWP 통합 어드민)
     */
    public PageResponse<UserSummary> getUsers(Long tenantId, int page, int size,
                                               String keyword, Long departmentId, Long roleId,
                                               List<Long> roleIds, String appCode,
                                               String status, String idpProviderType, String loginType) {
        try {
            if (size > 200) size = 200;
            if (size < 1) size = 20;
            Pageable pageable = PageRequest.of(page - 1, size);
            String providerTypeFilter = loginType != null ? loginType : idpProviderType;

            List<Long> scopeUserIds = resolveScopeUserIds(tenantId, appCode, roleId, roleIds);
            Page<User> userPage;

            if (scopeUserIds != null) {
                if (scopeUserIds.isEmpty()) {
                    return PageResponse.<UserSummary>builder()
                            .items(List.of())
                            .page(page)
                            .size(size)
                            .totalItems(0L)
                            .totalPages(0)
                            .build();
                }
                userPage = userRepository.findByTenantIdAndFiltersAndUserIdIn(
                        tenantId, keyword, departmentId, status, providerTypeFilter, scopeUserIds, pageable);
            } else {
                userPage = userRepository.findByTenantIdAndFilters(
                        tenantId, keyword, departmentId, status, providerTypeFilter, pageable);
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
     * 앱/역할 스코프에 해당하는 사용자 ID 목록 반환.
     * @return null = 플랫폼 전체(스코프 없음), empty = 해당 스코프 사용자 없음
     */
    private List<Long> resolveScopeUserIds(Long tenantId, String appCode, Long roleId, List<Long> roleIds) {
        List<Long> targetRoleIds = null;

        if (appCode != null && !appCode.isBlank()) {
            List<String> roleCodes = AppScopeResolver.getRoleCodesByAppCode(appCode);
            if (roleCodes.isEmpty()) {
                return List.of();
            }
            List<Role> roles = roleRepository.findByTenantIdAndCodeIn(tenantId, roleCodes);
            targetRoleIds = roles.stream().map(Role::getRoleId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        } else if (roleIds != null && !roleIds.isEmpty()) {
            targetRoleIds = new ArrayList<>(roleIds);
        } else if (roleId != null) {
            targetRoleIds = List.of(roleId);
        }

        if (targetRoleIds == null) {
            return null;
        }
        if (targetRoleIds.isEmpty()) {
            return List.of();
        }

        List<Long> userIds = roleMemberRepository.findUserIdsByTenantIdAndRoleIdIn(tenantId, targetRoleIds);
        return userIds != null ? userIds.stream().distinct().collect(Collectors.toList()) : List.of();
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
        return getUserRoles(tenantId, user);
    }

    /**
     * 사용자 역할 조회 (User 엔티티 전달, 목록 조회 시 중복 user 조회 방지)
     */
    private List<UserRoleInfo> getUserRoles(Long tenantId, User user) {
        if (user == null || user.getUserId() == null) {
            return List.of();
        }
        long userId = user.getUserId();

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
        
        // loginId, providerType 조회 (LOCAL 계정 우선, 없으면 첫 번째 계정)
        String loginId = null;
        String providerType = null;
        java.time.LocalDateTime lastLoginAt = null;
        List<UserAccount> accounts = userAccountRepository.findByUserId(user.getUserId());
        if (!accounts.isEmpty()) {
            // LOCAL 계정 우선 선택
            UserAccount primaryAccount = accounts.stream()
                    .filter(acc -> "LOCAL".equals(acc.getProviderType()))
                    .findFirst()
                    .orElse(accounts.get(0)); // LOCAL이 없으면 첫 번째 계정
            loginId = primaryAccount.getPrincipal();
            providerType = primaryAccount.getProviderType();
            // 마지막 로그인 시간 조회 (서브쿼리로 최신 1건만)
            lastLoginAt = loginHistoryRepository.findLastLoginAtByAccount(
                    tenantId, primaryAccount.getProviderType(), primaryAccount.getProviderId(), primaryAccount.getPrincipal())
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
        
        // 역할 목록 조회 (Users 탭 Role 컬럼 표시용, User 전달로 중복 조회 방지)
        List<UserRoleInfo> roles = getUserRoles(tenantId, user);

        return UserSummary.builder()
                .comUserId(user.getUserId())
                .tenantId(tenantId)
                .departmentId(user.getPrimaryDepartmentId())
                .departmentName(departmentName)
                .userName(user.getDisplayName())
                .loginId(loginId)
                .providerType(providerType)
                .email(user.getEmail())
                .status(user.getStatus())
                .mfaEnabled(user.getMfaEnabled())
                .lastLoginAt(lastLoginAt)
                .roles(roles)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
