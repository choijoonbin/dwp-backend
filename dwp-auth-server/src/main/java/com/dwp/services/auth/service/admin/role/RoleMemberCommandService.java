package com.dwp.services.auth.service.admin.role;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.AddRoleMemberRequest;
import com.dwp.services.auth.dto.admin.RoleMemberView;
import com.dwp.services.auth.dto.admin.UpdateRoleMembersRequest;
import com.dwp.services.auth.entity.Department;
import com.dwp.services.auth.entity.RoleMember;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.DepartmentRepository;
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

/**
 * 역할 멤버 관리 서비스 (CQRS: Command 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
@SuppressWarnings("null")
public class RoleMemberCommandService {
    
    private final RoleRepository roleRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final CodeResolver codeResolver;
    private final AuditLogService auditLogService;
    
    /**
     * 역할 멤버 업데이트
     */
    public void updateRoleMembers(Long tenantId, Long actorUserId, Long roleId,
                                  UpdateRoleMembersRequest request, HttpServletRequest httpRequest) {
        // 역할 존재 확인
        roleRepository.findByTenantIdAndRoleId(tenantId, roleId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "역할을 찾을 수 없습니다."));
        
        // 기존 멤버 삭제
        roleMemberRepository.deleteByTenantIdAndRoleId(tenantId, roleId);
        
        String userSubjectType = "USER";
        String deptSubjectType = "DEPARTMENT";
        codeResolver.require("SUBJECT_TYPE", userSubjectType);
        codeResolver.require("SUBJECT_TYPE", deptSubjectType);
        
        // 사용자 멤버 추가
        if (request.getUserIds() != null && !request.getUserIds().isEmpty()) {
            for (Long userId : request.getUserIds()) {
                userRepository.findByTenantIdAndUserId(tenantId, userId)
                        .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
                
                RoleMember member = RoleMember.builder()
                        .tenantId(tenantId)
                        .roleId(roleId)
                        .subjectType(userSubjectType)
                        .subjectId(userId)
                        .build();
                roleMemberRepository.save(member);
            }
        }
        
        // 부서 멤버 추가
        if (request.getDepartmentIds() != null && !request.getDepartmentIds().isEmpty()) {
            for (Long departmentId : request.getDepartmentIds()) {
                departmentRepository.findByTenantIdAndDepartmentId(tenantId, departmentId)
                        .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부서를 찾을 수 없습니다."));
                
                RoleMember member = RoleMember.builder()
                        .tenantId(tenantId)
                        .roleId(roleId)
                        .subjectType(deptSubjectType)
                        .subjectId(departmentId)
                        .build();
                roleMemberRepository.save(member);
            }
        }
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_MEMBER_UPDATE", "ROLE", roleId,
                null, request, httpRequest);
    }
    
    /**
     * 역할 멤버 개별 추가
     */
    public RoleMemberView addRoleMember(Long tenantId, Long actorUserId, Long roleId,
                                        AddRoleMemberRequest request, HttpServletRequest httpRequest) {
        // 역할 존재 확인
        roleRepository.findByTenantIdAndRoleId(tenantId, roleId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "역할을 찾을 수 없습니다."));
        
        // SUBJECT_TYPE 검증
        codeResolver.require("SUBJECT_TYPE", request.getSubjectType());
        
        // 중복 체크
        roleMemberRepository.findByTenantIdAndRoleId(tenantId, roleId).stream()
                .filter(m -> m.getSubjectType().equals(request.getSubjectType()) 
                        && m.getSubjectId().equals(request.getSubjectId()))
                .findFirst()
                .ifPresent(m -> {
                    throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 할당된 멤버입니다.");
                });
        
        // subject 존재 확인
        if ("USER".equals(request.getSubjectType())) {
            userRepository.findByTenantIdAndUserId(tenantId, request.getSubjectId())
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "사용자를 찾을 수 없습니다."));
        } else if ("DEPARTMENT".equals(request.getSubjectType())) {
            departmentRepository.findByTenantIdAndDepartmentId(tenantId, request.getSubjectId())
                    .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "부서를 찾을 수 없습니다."));
        } else {
            throw new BaseException(ErrorCode.INVALID_CODE, "유효하지 않은 subjectType입니다.");
        }
        
        // 멤버 추가
        RoleMember member = RoleMember.builder()
                .tenantId(tenantId)
                .roleId(roleId)
                .subjectType(request.getSubjectType())
                .subjectId(request.getSubjectId())
                .build();
        member = roleMemberRepository.save(member);
        
        // subjectName 조회
        String subjectName = null;
        if ("USER".equals(member.getSubjectType())) {
            subjectName = userRepository.findById(member.getSubjectId())
                    .map(User::getDisplayName)
                    .orElse(null);
        } else if ("DEPARTMENT".equals(member.getSubjectType())) {
            subjectName = departmentRepository.findById(member.getSubjectId())
                    .map(Department::getName)
                    .orElse(null);
        }
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_MEMBER_ADD", "ROLE", roleId,
                null, request, httpRequest);
        
        return RoleMemberView.builder()
                .roleMemberId(member.getRoleMemberId())
                .subjectType(member.getSubjectType())
                .subjectId(member.getSubjectId())
                .subjectName(subjectName)
                .build();
    }
    
    /**
     * 역할 멤버 개별 삭제
     */
    public void removeRoleMember(Long tenantId, Long actorUserId, Long roleId, Long roleMemberId,
                                 HttpServletRequest httpRequest) {
        // 역할 존재 확인
        roleRepository.findByTenantIdAndRoleId(tenantId, roleId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "역할을 찾을 수 없습니다."));
        
        // 멤버 존재 확인
        RoleMember member = roleMemberRepository.findById(roleMemberId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "멤버를 찾을 수 없습니다."));
        
        // tenantId 및 roleId 일치 확인
        if (!member.getTenantId().equals(tenantId) || !member.getRoleId().equals(roleId)) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "멤버를 찾을 수 없습니다.");
        }
        
        // 삭제
        roleMemberRepository.delete(member);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_MEMBER_REMOVE", "ROLE", roleId,
                member, null, httpRequest);
    }
}
