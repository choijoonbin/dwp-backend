package com.dwp.services.auth.service.admin.users;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.UpdateUserRolesRequest;
import com.dwp.services.auth.dto.admin.UserRoleInfo;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RoleMember;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.util.CodeResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 사용자 역할 관리 서비스 (CQRS: Command 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
@SuppressWarnings("null")
public class UserRoleService {
    
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final CodeResolver codeResolver;
    private final AuditLogService auditLogService;
    
    /**
     * 사용자 역할 업데이트
     */
    public void updateUserRoles(Long tenantId, Long actorUserId, Long userId,
                                UpdateUserRolesRequest request, HttpServletRequest httpRequest) {
        // 사용자 존재 확인
        userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        String subjectType = "USER";
        codeResolver.require("SUBJECT_TYPE", subjectType);
        
        // replace=true면 기존 역할 삭제, false면 추가만
        if (Boolean.TRUE.equals(request.getReplace())) {
            roleMemberRepository.deleteByTenantIdAndUserId(tenantId, userId);
        }
        
        // 새 역할 멤버 추가
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            // 역할 존재 확인
            List<Role> roles = roleRepository.findByRoleIdIn(request.getRoleIds());
            if (roles.size() != request.getRoleIds().size()) {
                throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "존재하지 않는 역할이 포함되어 있습니다.");
            }
            
            for (Long roleId : request.getRoleIds()) {
                // 중복 체크 (replace=false인 경우)
                if (!Boolean.TRUE.equals(request.getReplace())) {
                    boolean exists = roleMemberRepository.findByTenantIdAndUserId(tenantId, userId).stream()
                            .anyMatch(rm -> rm.getRoleId().equals(roleId));
                    if (exists) {
                        continue; // 이미 있으면 스킵
                    }
                }
                
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
    
    /**
     * 사용자 역할 추가
     */
    public UserRoleInfo addUserRole(Long tenantId, Long actorUserId, Long userId, Long roleId,
                                    HttpServletRequest httpRequest) {
        // 사용자 존재 확인
        userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        // 역할 존재 확인
        Role role = roleRepository.findByTenantIdAndRoleId(tenantId, roleId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "역할을 찾을 수 없습니다."));
        
        String subjectType = "USER";
        codeResolver.require("SUBJECT_TYPE", subjectType);
        
        // 중복 체크
        boolean exists = roleMemberRepository.findByTenantIdAndUserId(tenantId, userId).stream()
                .anyMatch(rm -> rm.getRoleId().equals(roleId));
        if (exists) {
            throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 할당된 역할입니다.");
        }
        
        // 역할 멤버 추가
        RoleMember member = RoleMember.builder()
                .tenantId(tenantId)
                .roleId(roleId)
                .subjectType(subjectType)
                .subjectId(userId)
                .build();
        roleMemberRepository.save(member);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "USER_ROLE_ADD", "USER", userId,
                null, roleId, httpRequest);
        
        return UserRoleInfo.builder()
                .comRoleId(role.getRoleId())
                .roleCode(role.getCode())
                .roleName(role.getName())
                .subjectType(subjectType)
                .isDepartmentBased(false)
                .assignedAt(member.getCreatedAt())
                .build();
    }
    
    /**
     * 사용자 역할 삭제
     */
    public void removeUserRole(Long tenantId, Long actorUserId, Long userId, Long roleId,
                               HttpServletRequest httpRequest) {
        // 사용자 존재 확인
        userRepository.findByTenantIdAndUserId(tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        
        // 역할 멤버 찾기
        String userSubjectType = "USER";
        codeResolver.require("SUBJECT_TYPE", userSubjectType);
        
        RoleMember member = roleMemberRepository.findByTenantIdAndUserId(tenantId, userId).stream()
                .filter(rm -> rm.getRoleId().equals(roleId) && userSubjectType.equals(rm.getSubjectType()))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "할당된 역할을 찾을 수 없습니다."));
        
        // 부서 기반 역할은 삭제 불가
        String deptSubjectType = "DEPARTMENT";
        codeResolver.require("SUBJECT_TYPE", deptSubjectType);
        if (deptSubjectType.equals(member.getSubjectType())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "부서 기반 역할은 삭제할 수 없습니다.");
        }
        
        // 역할 멤버 삭제
        roleMemberRepository.delete(member);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "USER_ROLE_REMOVE", "USER", userId,
                roleId, null, httpRequest);
    }
}
