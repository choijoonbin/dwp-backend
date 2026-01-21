package com.dwp.services.auth.service.admin.roles;

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
    public PageResponse<RoleSummary> getRoles(Long tenantId, int page, int size, String keyword, String status) {
        // 페이징 크기 제한 (최대 200)
        if (size > 200) {
            size = 200;
        }
        if (size < 1) {
            size = 20;
        }
        Pageable pageable = PageRequest.of(page - 1, size);
        Page<Role> rolePage = roleRepository.findByTenantIdAndKeyword(tenantId, keyword, status, pageable);
        
        List<RoleSummary> summaries = rolePage.getContent().stream()
                .map(r -> {
                    // 멤버 수 계산 (USER + DEPARTMENT 분리)
                    List<RoleMember> members = roleMemberRepository.findByTenantIdAndRoleId(tenantId, r.getRoleId());
                    long userCount = members.stream().filter(m -> "USER".equals(m.getSubjectType())).count();
                    long departmentCount = members.stream().filter(m -> "DEPARTMENT".equals(m.getSubjectType())).count();
                    long totalMemberCount = members.size();
                    
                    return RoleSummary.builder()
                            .id(String.valueOf(r.getRoleId())) // 문자열로 변환
                            .comRoleId(r.getRoleId()) // 호환 유지
                            .roleCode(r.getCode())
                            .roleName(r.getName())
                            .status(r.getStatus() != null ? r.getStatus() : "ACTIVE") // 실제 status 값 사용
                            .description(r.getDescription())
                            .createdAt(r.getCreatedAt())
                            .memberCount((int) totalMemberCount) // 전체 멤버 수
                            .userCount((int) userCount) // USER 멤버 수
                            .departmentCount((int) departmentCount) // DEPARTMENT 멤버 수
                            .build();
                })
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
        
        // 멤버 수 계산 (USER + DEPARTMENT 분리)
        List<RoleMember> members = roleMemberRepository.findByTenantIdAndRoleId(tenantId, roleId);
        long userCount = members.stream().filter(m -> "USER".equals(m.getSubjectType())).count();
        long departmentCount = members.stream().filter(m -> "DEPARTMENT".equals(m.getSubjectType())).count();
        long totalMemberCount = members.size();
        
        return RoleDetail.builder()
                .id(String.valueOf(role.getRoleId())) // 문자열로 변환
                .comRoleId(role.getRoleId()) // 호환 유지
                .roleCode(role.getCode())
                .roleName(role.getName())
                .status(role.getStatus() != null ? role.getStatus() : "ACTIVE") // 실제 status 값 사용
                .description(role.getDescription())
                .createdAt(role.getCreatedAt())
                .updatedAt(role.getUpdatedAt())
                .memberCount((int) totalMemberCount) // 전체 멤버 수
                .userCount((int) userCount) // USER 멤버 수
                .departmentCount((int) departmentCount) // DEPARTMENT 멤버 수
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
                    final String[] subjectEmail = {null};
                    final String[] departmentName = {null};
                    
                    if ("USER".equals(member.getSubjectType())) {
                        userRepository.findById(member.getSubjectId())
                                .ifPresent(user -> {
                                    subjectName[0] = user.getDisplayName();
                                    subjectEmail[0] = user.getEmail();
                                    // USER의 primary department 조회
                                    if (user.getPrimaryDepartmentId() != null) {
                                        departmentRepository.findById(user.getPrimaryDepartmentId())
                                                .ifPresent(dept -> departmentName[0] = dept.getName());
                                    }
                                });
                    } else if ("DEPARTMENT".equals(member.getSubjectType())) {
                        departmentRepository.findById(member.getSubjectId())
                                .ifPresent(dept -> subjectName[0] = dept.getName());
                    }
                    
                    return RoleMemberView.builder()
                            .roleMemberId(member.getRoleMemberId())
                            .subjectType(member.getSubjectType())
                            .subjectId(member.getSubjectId())
                            .subjectName(subjectName[0])
                            .subjectEmail(subjectEmail[0])
                            .departmentName(departmentName[0])
                            .build();
                })
                .collect(Collectors.toList());
    }
    
    /**
     * 역할 권한 조회 (매트릭스 구성 가능하도록 정렬)
     * 
     * 정렬 순서:
     * 1. Resources: sort_order ASC, name ASC
     * 2. Permissions: sort_order ASC, code ASC
     */
    @Transactional(readOnly = true)
    public List<RolePermissionView> getRolePermissions(Long tenantId, Long roleId) {
        List<RolePermission> rolePermissions = rolePermissionRepository.findByTenantIdAndRoleId(tenantId, roleId);
        
        List<RolePermissionView> views = rolePermissions.stream()
                .map(rp -> {
                    Resource resource = resourceRepository.findById(rp.getResourceId()).orElse(null);
                    Permission permission = permissionRepository.findById(rp.getPermissionId()).orElse(null);
                    
                    if (resource == null || permission == null) return null;
                    
                    // PR-03C: 매트릭스 구성 가능하도록 resourceType 포함
                    return RolePermissionView.builder()
                            .comRoleId(roleId)
                            .comResourceId(resource.getResourceId())
                            .resourceKey(resource.getKey())
                            .resourceName(resource.getName())
                            .resourceType(resource.getType())  // PR-03C: resourceType 추가
                            .resourceSortOrder(resource.getSortOrder() != null ? resource.getSortOrder() : 0)
                            .comPermissionId(permission.getPermissionId())
                            .permissionCode(permission.getCode())
                            .permissionName(permission.getName())
                            .permissionSortOrder(permission.getSortOrder() != null ? permission.getSortOrder() : 0)
                            .permissionDescription(permission.getDescription())
                            .effect(rp.getEffect())
                            .build();
                })
                .filter(p -> p != null)
                .collect(Collectors.toList());
        
        // 정렬: resource sort_order ASC, permission sort_order ASC
        return views.stream()
                .sorted((a, b) -> {
                    int resourceCompare = Integer.compare(
                        a.getResourceSortOrder() != null ? a.getResourceSortOrder() : 0,
                        b.getResourceSortOrder() != null ? b.getResourceSortOrder() : 0
                    );
                    if (resourceCompare != 0) return resourceCompare;
                    
                    int permissionCompare = Integer.compare(
                        a.getPermissionSortOrder() != null ? a.getPermissionSortOrder() : 0,
                        b.getPermissionSortOrder() != null ? b.getPermissionSortOrder() : 0
                    );
                    if (permissionCompare != 0) return permissionCompare;
                    
                    // 동일한 경우 resourceName, permissionCode로 정렬
                    int nameCompare = (a.getResourceName() != null ? a.getResourceName() : "").compareTo(
                        b.getResourceName() != null ? b.getResourceName() : "");
                    if (nameCompare != 0) return nameCompare;
                    
                    return (a.getPermissionCode() != null ? a.getPermissionCode() : "").compareTo(
                        b.getPermissionCode() != null ? b.getPermissionCode() : "");
                })
                .collect(Collectors.toList());
    }
}
