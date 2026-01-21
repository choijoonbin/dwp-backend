package com.dwp.services.auth.controller.admin;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.service.admin.roles.RoleManagementService;
import com.dwp.services.auth.service.rbac.PermissionEvaluator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 역할 관리 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/admin/roles")
@RequiredArgsConstructor
public class RoleController {
    
    private final RoleManagementService roleManagementService;
    private final PermissionEvaluator permissionEvaluator;
    
    /**
     * PR-09B: 역할 목록 조회 (VIEW 권한 체크)
     * GET /api/admin/roles
     */
    @GetMapping
    public ApiResponse<PageResponse<RoleSummary>> getRoles(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        Long userId = getUserId(authentication);
        // PR-09B: VIEW 권한 체크
        permissionEvaluator.requirePermission(userId, tenantId, "menu.admin.roles", "VIEW");
        return ApiResponse.success(roleManagementService.getRoles(tenantId, page, size, keyword, status));
    }
    
    /**
     * PR-09B: 역할 상세 조회 (VIEW 권한 체크)
     * GET /api/admin/roles/{comRoleId}
     */
    @GetMapping("/{comRoleId}")
    public ApiResponse<RoleDetail> getRoleDetail(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comRoleId") Long roleId) {
        Long userId = getUserId(authentication);
        // PR-09B: VIEW 권한 체크
        permissionEvaluator.requirePermission(userId, tenantId, "menu.admin.roles", "VIEW");
        return ApiResponse.success(roleManagementService.getRoleDetail(tenantId, roleId));
    }
    
    /**
     * PR-09B: 역할 생성 (EDIT 권한 체크)
     * POST /api/admin/roles
     */
    @PostMapping
    public ApiResponse<RoleDetail> createRole(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @Valid @RequestBody CreateRoleRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크 (CREATE → EDIT)
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.roles", "EDIT");
        return ApiResponse.success(roleManagementService.createRole(tenantId, actorUserId, request, httpRequest));
    }
    
    /**
     * PR-09B: 역할 수정 (EDIT 권한 체크)
     * PUT /api/admin/roles/{comRoleId}
     */
    @PutMapping("/{comRoleId}")
    public ApiResponse<RoleDetail> updateRole(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comRoleId") Long roleId,
            @Valid @RequestBody UpdateRoleRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크 (UPDATE → EDIT)
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.roles", "EDIT");
        return ApiResponse.success(roleManagementService.updateRole(tenantId, actorUserId, roleId, request, httpRequest));
    }
    
    /**
     * PR-09B: 역할 삭제 (EDIT 권한 체크)
     * DELETE /api/admin/roles/{comRoleId}
     */
    @DeleteMapping("/{comRoleId}")
    public ApiResponse<Void> deleteRole(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comRoleId") Long roleId,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크 (DELETE → EDIT)
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.roles", "EDIT");
        roleManagementService.deleteRole(tenantId, actorUserId, roleId, httpRequest);
        return ApiResponse.success(null);
    }
    
    /**
     * PR-09B: 역할 멤버 조회 (VIEW 권한 체크)
     * GET /api/admin/roles/{comRoleId}/members
     */
    @GetMapping("/{comRoleId}/members")
    public ApiResponse<List<RoleMemberView>> getRoleMembers(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comRoleId") Long roleId) {
        Long userId = getUserId(authentication);
        // PR-09B: VIEW 권한 체크
        permissionEvaluator.requirePermission(userId, tenantId, "menu.admin.roles", "VIEW");
        return ApiResponse.success(roleManagementService.getRoleMembers(tenantId, roleId));
    }
    
    /**
     * PR-09B: 역할 멤버 업데이트 (EDIT 권한 체크)
     * PUT /api/admin/roles/{comRoleId}/members
     */
    @PutMapping("/{comRoleId}/members")
    public ApiResponse<Void> updateRoleMembers(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comRoleId") Long roleId,
            @Valid @RequestBody UpdateRoleMembersRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.roles", "EDIT");
        roleManagementService.updateRoleMembers(tenantId, actorUserId, roleId, request, httpRequest);
        return ApiResponse.success(null);
    }
    
    /**
     * PR-09B: 역할 멤버 개별 추가 (EDIT 권한 체크)
     * POST /api/admin/roles/{comRoleId}/members
     */
    @PostMapping("/{comRoleId}/members")
    public ApiResponse<RoleMemberView> addRoleMember(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comRoleId") Long roleId,
            @Valid @RequestBody AddRoleMemberRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.roles", "EDIT");
        return ApiResponse.success(roleManagementService.addRoleMember(tenantId, actorUserId, roleId, request, httpRequest));
    }
    
    /**
     * PR-09B: 역할 멤버 개별 삭제 (EDIT 권한 체크)
     * DELETE /api/admin/roles/{comRoleId}/members/{comRoleMemberId}
     */
    @DeleteMapping("/{comRoleId}/members/{comRoleMemberId}")
    public ApiResponse<Void> removeRoleMember(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comRoleId") Long roleId,
            @PathVariable("comRoleMemberId") Long roleMemberId,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.roles", "EDIT");
        roleManagementService.removeRoleMember(tenantId, actorUserId, roleId, roleMemberId, httpRequest);
        return ApiResponse.success(null);
    }
    
    /**
     * PR-09B: 역할 권한 조회 (VIEW 권한 체크)
     * GET /api/admin/roles/{comRoleId}/permissions
     */
    @GetMapping("/{comRoleId}/permissions")
    public ApiResponse<List<RolePermissionView>> getRolePermissions(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comRoleId") Long roleId) {
        Long userId = getUserId(authentication);
        // PR-09B: VIEW 권한 체크
        permissionEvaluator.requirePermission(userId, tenantId, "menu.admin.roles", "VIEW");
        return ApiResponse.success(roleManagementService.getRolePermissions(tenantId, roleId));
    }
    
    /**
     * PR-09B: 역할 권한 업데이트 (EDIT 권한 체크)
     * PUT /api/admin/roles/{comRoleId}/permissions
     */
    @PutMapping("/{comRoleId}/permissions")
    public ApiResponse<Void> updateRolePermissions(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comRoleId") Long roleId,
            @Valid @RequestBody UpdateRolePermissionsRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        // PR-09B: EDIT 권한 체크
        permissionEvaluator.requirePermission(actorUserId, tenantId, "menu.admin.roles", "EDIT");
        roleManagementService.updateRolePermissions(tenantId, actorUserId, roleId, request, httpRequest);
        return ApiResponse.success(null);
    }
    
    private Long getUserId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
            return Long.parseLong(((Jwt) authentication.getPrincipal()).getSubject());
        }
        return null;
    }
}
