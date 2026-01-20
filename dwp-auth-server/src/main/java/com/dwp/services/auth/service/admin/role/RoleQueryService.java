package com.dwp.services.auth.service.admin.role;

import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.entity.RoleMember;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.repository.DepartmentRepository;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
import com.dwp.services.auth.repository.RoleRepository;
import com.dwp.services.auth.repository.UserRepository;
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
 * 역할 조회 서비스 (CQRS: Query 전용)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class RoleQueryService {
    
    private final RoleRepository roleRepository;
    private final RoleMemberRepository roleMemberRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final ResourceRepository resourceRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    
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
                .orElseThrow(() -> new com.dwp.core.exception.BaseException(
                        com.dwp.core.common.ErrorCode.ENTITY_NOT_FOUND, "역할을 찾을 수 없습니다."));
        
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
                            .roleMemberId(member.getRoleMemberId())
                            .subjectType(member.getSubjectType())
                            .subjectId(member.getSubjectId())
                            .subjectName(subjectName[0])
                            .build();
                })
                .collect(Collectors.toList());
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
}
