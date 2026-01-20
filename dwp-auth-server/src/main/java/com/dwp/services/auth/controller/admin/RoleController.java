package com.dwp.services.auth.controller.admin;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.admin.*;
import com.dwp.services.auth.service.admin.RoleManagementService;
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
    
    /**
     * 역할 목록 조회
     * GET /api/admin/roles
     */
    @GetMapping
    public ApiResponse<PageResponse<RoleSummary>> getRoles(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(roleManagementService.getRoles(tenantId, page, size, keyword));
    }
    
    /**
     * 역할 상세 조회
     * GET /api/admin/roles/{comRoleId}
     */
    @GetMapping("/{comRoleId}")
    public ApiResponse<RoleDetail> getRoleDetail(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @PathVariable("comRoleId") Long roleId) {
        return ApiResponse.success(roleManagementService.getRoleDetail(tenantId, roleId));
    }
    
    /**
     * 역할 생성
     * POST /api/admin/roles
     */
    @PostMapping
    public ApiResponse<RoleDetail> createRole(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @Valid @RequestBody CreateRoleRequest request,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        return ApiResponse.success(roleManagementService.createRole(tenantId, actorUserId, request, httpRequest));
    }
    
    /**
     * 역할 수정
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
        return ApiResponse.success(roleManagementService.updateRole(tenantId, actorUserId, roleId, request, httpRequest));
    }
    
    /**
     * 역할 삭제
     * DELETE /api/admin/roles/{comRoleId}
     */
    @DeleteMapping("/{comRoleId}")
    public ApiResponse<Void> deleteRole(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            Authentication authentication,
            @PathVariable("comRoleId") Long roleId,
            HttpServletRequest httpRequest) {
        Long actorUserId = getUserId(authentication);
        roleManagementService.deleteRole(tenantId, actorUserId, roleId, httpRequest);
        return ApiResponse.success(null);
    }
    
    /**
     * 역할 멤버 조회
     * GET /api/admin/roles/{comRoleId}/members
     */
    @GetMapping("/{comRoleId}/members")
    public ApiResponse<List<RoleMemberView>> getRoleMembers(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @PathVariable("comRoleId") Long roleId) {
        return ApiResponse.success(roleManagementService.getRoleMembers(tenantId, roleId));
    }
    
    /**
     * 역할 멤버 업데이트 (Bulk)
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
        roleManagementService.updateRoleMembers(tenantId, actorUserId, roleId, request, httpRequest);
        return ApiResponse.success(null);
    }
    
    /**
     * 역할 멤버 개별 추가 (BE P1-5 Final)
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
        return ApiResponse.success(roleManagementService.addRoleMember(tenantId, actorUserId, roleId, request, httpRequest));
    }
    
    /**
     * 역할 멤버 개별 삭제 (BE P1-5 Final)
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
        roleManagementService.removeRoleMember(tenantId, actorUserId, roleId, roleMemberId, httpRequest);
        return ApiResponse.success(null);
    }
    
    /**
     * 역할 권한 조회
     * GET /api/admin/roles/{comRoleId}/permissions
     */
    @GetMapping("/{comRoleId}/permissions")
    public ApiResponse<List<RolePermissionView>> getRolePermissions(
            @RequestHeader("X-Tenant-ID") Long tenantId,
            @PathVariable("comRoleId") Long roleId) {
        return ApiResponse.success(roleManagementService.getRolePermissions(tenantId, roleId));
    }
    
    /**
     * 역할 권한 업데이트
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
