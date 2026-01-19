package com.dwp.services.auth.service.admin;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.entity.*;
import com.dwp.services.auth.repository.*;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.util.CodeResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 역할 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class RoleManagementService {
    
    private final RoleRepository roleRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final ResourceRepository resourceRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final CodeResolver codeResolver;
    private final AuditLogService auditLogService;
    
    /**
     * 역할 목록 조회
     */
    @Transactional(readOnly = true)
    public PageResponse<RoleSummary> getRoles(Long tenantId, int page, int size, String keyword) {
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Role> rolePage = roleRepository.findByTenantIdAndKeyword(tenantId, keyword, pageable);
        
        List<RoleSummary> summaries = rolePage.getContent().stream()
                .map(r -> RoleSummary.builder()
                        .comRoleId(r.getRoleId())
                        .roleCode(r.getCode())
                        .roleName(r.getName())
                        .description(r.getDescription())
                        .createdAt(r.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
        
        return PageResponse.<RoleSummary>builder()
                .items(summaries)
                .page(page)
                .size(size)
                .totalItems(rolePage.getTotalElements())
                .totalPages(rolePage.getTotalPages())
                .build();
    }
    
    /**
     * 역할 상세 조회 (권한 포함)
     */
    @Transactional(readOnly = true)
    public RoleDetail getRoleDetail(Long tenantId, Long roleId) {
        Role role = roleRepository.findByTenantIdAndRoleId(tenantId, roleId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "역할을 찾을 수 없습니다."));
        
        return RoleDetail.builder()
                .comRoleId(role.getRoleId())
                .roleCode(role.getCode())
                .roleName(role.getName())
                .description(role.getDescription())
                .createdAt(role.getCreatedAt())
                .updatedAt(role.getUpdatedAt())
                .build();
    }
    
    /**
     * 역할 생성
     */
    @Transactional
    public RoleDetail createRole(Long tenantId, Long actorUserId, CreateRoleRequest request,
                                HttpServletRequest httpRequest) {
        // 역할 코드 중복 체크
        roleRepository.findByTenantIdAndCode(tenantId, request.getRoleCode())
                .ifPresent(r -> {
                    throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 역할 코드입니다.");
                });
        
        // ROLE_CODE 검증
        codeResolver.require("ROLE_CODE", request.getRoleCode());
        
        Role role = Role.builder()
                .tenantId(tenantId)
                .code(request.getRoleCode())
                .name(request.getRoleName())
                .description(request.getDescription())
                .build();
        role = roleRepository.save(role);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_CREATE", "ROLE", role.getRoleId(),
                null, role, httpRequest);
        
        return getRoleDetail(tenantId, role.getRoleId());
    }
    
    /**
     * 역할 수정
     */
    @Transactional
    public RoleDetail updateRole(Long tenantId, Long actorUserId, Long roleId, UpdateRoleRequest request,
                                HttpServletRequest httpRequest) {
        Role role = roleRepository.findByTenantIdAndRoleId(tenantId, roleId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "역할을 찾을 수 없습니다."));
        
        // ADMIN 역할 삭제 금지
        String adminRoleCode = "ADMIN";
        codeResolver.require("ROLE_CODE", adminRoleCode);
        if (adminRoleCode.equals(role.getCode())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "ADMIN 역할은 수정할 수 없습니다.");
        }
        
        Role before = copyRole(role);
        
        if (request.getRoleCode() != null) {
            // 역할 코드 중복 체크 (본인 제외)
            roleRepository.findByTenantIdAndCode(tenantId, request.getRoleCode())
                    .filter(r -> !r.getRoleId().equals(roleId))
                    .ifPresent(r -> {
                        throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 역할 코드입니다.");
                    });
            codeResolver.require("ROLE_CODE", request.getRoleCode());
            role.setCode(request.getRoleCode());
        }
        if (request.getRoleName() != null) {
            role.setName(request.getRoleName());
        }
        if (request.getDescription() != null) {
            role.setDescription(request.getDescription());
        }
        
        role = roleRepository.save(role);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_UPDATE", "ROLE", roleId,
                before, role, httpRequest);
        
        return getRoleDetail(tenantId, roleId);
    }
    
    /**
     * 역할 삭제
     */
    @Transactional
    public void deleteRole(Long tenantId, Long actorUserId, Long roleId, HttpServletRequest httpRequest) {
        Role role = roleRepository.findByTenantIdAndRoleId(tenantId, roleId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "역할을 찾을 수 없습니다."));
        
        // ADMIN 역할 삭제 금지
        String adminRoleCode = "ADMIN";
        codeResolver.require("ROLE_CODE", adminRoleCode);
        if (adminRoleCode.equals(role.getCode())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "ADMIN 역할은 삭제할 수 없습니다.");
        }
        
        Role before = copyRole(role);
        
        // 역할 멤버 삭제
        roleMemberRepository.deleteByTenantIdAndRoleId(tenantId, roleId);
        
        // 역할 권한 삭제
        rolePermissionRepository.deleteByTenantIdAndRoleId(tenantId, roleId);
        
        // 역할 삭제
        roleRepository.delete(role);
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_DELETE", "ROLE", roleId,
                before, null, httpRequest);
    }
    
    /**
     * 역할 멤버 조회
     */
    @Transactional(readOnly = true)
    public List<RoleMemberView> getRoleMembers(Long tenantId, Long roleId) {
        List<RoleMember> members = roleMemberRepository.findByTenantIdAndRoleId(tenantId, roleId);
        
        return members.stream()
                .map(member -> {
                    final String[] subjectName = {null};
                    if ("USER".equals(member.getSubjectType())) {
                        userRepository.findById(member.getSubjectId())
                                .ifPresent(user -> subjectName[0] = user.getDisplayName());
                    } else if ("DEPARTMENT".equals(member.getSubjectType())) {
                        departmentRepository.findById(member.getSubjectId())
                                .ifPresent(dept -> subjectName[0] = dept.getName());
                    }
                    
                    return RoleMemberView.builder()
                            .subjectType(member.getSubjectType())
                            .subjectId(member.getSubjectId())
                            .subjectName(subjectName[0])
                            .build();
                })
                .collect(Collectors.toList());
    }
    
    /**
     * 역할 멤버 업데이트
     */
    @Transactional
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
     * 역할 권한 조회
     */
    @Transactional(readOnly = true)
    public List<RolePermissionView> getRolePermissions(Long tenantId, Long roleId) {
        List<RolePermission> rolePermissions = rolePermissionRepository.findByTenantIdAndRoleId(tenantId, roleId);
        
        return rolePermissions.stream()
                .map(rp -> {
                    Resource resource = resourceRepository.findById(rp.getResourceId()).orElse(null);
                    Permission permission = permissionRepository.findById(rp.getPermissionId()).orElse(null);
                    
                    if (resource == null || permission == null) return null;
                    
                    return RolePermissionView.builder()
                            .comRoleId(roleId)
                            .comResourceId(resource.getResourceId())
                            .resourceKey(resource.getKey())
                            .resourceName(resource.getName())
                            .comPermissionId(permission.getPermissionId())
                            .permissionCode(permission.getCode())
                            .permissionName(permission.getName())
                            .effect(rp.getEffect())
                            .build();
                })
                .filter(p -> p != null)
                .collect(Collectors.toList());
    }
    
    /**
     * 역할 권한 업데이트 (Upsert)
     */
    @Transactional
    public void updateRolePermissions(Long tenantId, Long actorUserId, Long roleId,
                                     UpdateRolePermissionsRequest request, HttpServletRequest httpRequest) {
        // 역할 존재 확인
        roleRepository.findByTenantIdAndRoleId(tenantId, roleId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "역할을 찾을 수 없습니다."));
        
        // 기존 권한 삭제
        rolePermissionRepository.deleteByTenantIdAndRoleId(tenantId, roleId);
        
        // 새 권한 추가
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            for (UpdateRolePermissionsRequest.RolePermissionItem item : request.getItems()) {
                // 리소스 존재 확인
                resourceRepository.findByTenantIdAndResourceId(tenantId, item.getResourceId())
                        .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "리소스를 찾을 수 없습니다."));
                
                // 권한 존재 확인
                permissionRepository.findById(item.getPermissionId())
                        .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "권한을 찾을 수 없습니다."));
                
                // effect 검증 (ALLOW/DENY)
                String effect = item.getEffect() != null ? item.getEffect() : "ALLOW";
                if (!"ALLOW".equals(effect) && !"DENY".equals(effect)) {
                    throw new BaseException(ErrorCode.INVALID_CODE, "effect는 ALLOW 또는 DENY여야 합니다.");
                }
                
                RolePermission rolePermission = RolePermission.builder()
                        .tenantId(tenantId)
                        .roleId(roleId)
                        .resourceId(item.getResourceId())
                        .permissionId(item.getPermissionId())
                        .effect(effect)
                        .build();
                rolePermissionRepository.save(rolePermission);
            }
        }
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "ROLE_PERMISSION_UPDATE", "ROLE", roleId,
                null, request, httpRequest);
    }
    
    private Role copyRole(Role role) {
        return Role.builder()
                .roleId(role.getRoleId())
                .tenantId(role.getTenantId())
                .code(role.getCode())
                .name(role.getName())
                .description(role.getDescription())
                .build();
    }
}
