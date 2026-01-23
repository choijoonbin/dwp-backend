package com.dwp.services.auth.service.admin.roles;

import com.dwp.services.auth.dto.admin.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 권한 관리 서비스 (Facade)
 * 
 * 리팩토링: Query/Command 분리
 * - RoleQueryService: 조회 전용
 * - RoleCommandService: 역할 CRUD
 * - RoleMemberCommandService: 역할 멤버 관리
 * - RolePermissionCommandService: 역할 권한 관리
 * 
 * 기존 API 호환성을 위해 Facade 패턴으로 유지
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class RoleManagementService {
    
    private final RoleQueryService roleQueryService;
    private final RoleCommandService roleCommandService;
    private final RoleMemberCommandService roleMemberCommandService;
    private final RolePermissionCommandService rolePermissionCommandService;
    
    /**
     * 역할 목록 조회
     */
    public PageResponse<RoleSummary> getRoles(Long tenantId, int page, int size, String keyword, String status) {
        return roleQueryService.getRoles(tenantId, page, size, keyword, status);
    }
    
    /**
     * 역할 상세 조회 (권한 포함)
     */
    public RoleDetail getRoleDetail(Long tenantId, Long roleId) {
        return roleQueryService.getRoleDetail(tenantId, roleId);
    }
    
    /**
     * 역할 생성
     */
    public RoleDetail createRole(Long tenantId, Long actorUserId, CreateRoleRequest request,
                                HttpServletRequest httpRequest) {
        return roleCommandService.createRole(tenantId, actorUserId, request, httpRequest);
    }
    
    /**
     * 역할 수정
     */
    public RoleDetail updateRole(Long tenantId, Long actorUserId, Long roleId, UpdateRoleRequest request,
                                HttpServletRequest httpRequest) {
        return roleCommandService.updateRole(tenantId, actorUserId, roleId, request, httpRequest);
    }
    
    /**
     * 역할 삭제
     */
    public void deleteRole(Long tenantId, Long actorUserId, Long roleId, HttpServletRequest httpRequest) {
        roleCommandService.deleteRole(tenantId, actorUserId, roleId, httpRequest);
    }
    
    /**
     * 역할 멤버 조회
     */
    public List<RoleMemberView> getRoleMembers(Long tenantId, Long roleId) {
        return roleQueryService.getRoleMembers(tenantId, roleId);
    }
    
    /**
     * 역할 멤버 업데이트
     */
    public void updateRoleMembers(Long tenantId, Long actorUserId, Long roleId,
                                  UpdateRoleMembersRequest request, HttpServletRequest httpRequest) {
        roleMemberCommandService.updateRoleMembers(tenantId, actorUserId, roleId, request, httpRequest);
    }
    
    /**
     * 역할 멤버 개별 추가
     */
    public RoleMemberView addRoleMember(Long tenantId, Long actorUserId, Long roleId,
                                        AddRoleMemberRequest request, HttpServletRequest httpRequest) {
        return roleMemberCommandService.addRoleMember(tenantId, actorUserId, roleId, request, httpRequest);
    }
    
    /**
     * 역할 멤버 개별 삭제
     */
    public void removeRoleMember(Long tenantId, Long actorUserId, Long roleId, Long roleMemberId,
                                 HttpServletRequest httpRequest) {
        roleMemberCommandService.removeRoleMember(tenantId, actorUserId, roleId, roleMemberId, httpRequest);
    }
    
    /**
     * 역할 권한 조회
     */
    public List<RolePermissionView> getRolePermissions(Long tenantId, Long roleId) {
        return roleQueryService.getRolePermissions(tenantId, roleId);
    }
    
    /**
     * 역할 권한 업데이트 (Bulk Upsert/Delete)
     */
    public void updateRolePermissions(Long tenantId, Long actorUserId, Long roleId,
                                     UpdateRolePermissionsRequest request, HttpServletRequest httpRequest) {
        rolePermissionCommandService.updateRolePermissions(tenantId, actorUserId, roleId, request, httpRequest);
    }
}
